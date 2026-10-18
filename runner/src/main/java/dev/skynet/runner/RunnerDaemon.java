package dev.skynet.runner;

import dev.skynet.protocol.AgentProviderKind;
import dev.skynet.protocol.runner.HeartbeatReply;
import dev.skynet.protocol.runner.ProviderInfo;
import dev.skynet.protocol.runner.RunnerCommand;
import dev.skynet.protocol.runner.RunnerHeartbeat;
import dev.skynet.protocol.runner.RunnerRegistered;
import dev.skynet.protocol.runner.RunnerRegistration;
import dev.skynet.runner.agent.AgentExecutor;
import dev.skynet.runner.agent.ArtifactSender;
import dev.skynet.runner.agent.EventSender;
import dev.skynet.runner.journal.Journal;
import dev.skynet.runner.quota.QuotaPoller;
import dev.skynet.runner.supervisor.ProcessSupervisor;
import dev.skynet.runner.transport.ControlPlaneClient;
import dev.skynet.runner.transport.UnauthorizedException;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bucle principal del runner: se registra (o reutiliza su token), recoge lo que dejó a medias una
 * ejecución anterior, y a partir de ahí recibe órdenes por long-poll, envía latidos y vacía el
 * journal de eventos.
 */
public final class RunnerDaemon implements AutoCloseable {

  private static final Logger LOG = Logger.getLogger(RunnerDaemon.class.getName());
  private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

  private final RunnerConfig config;
  private final ControlPlaneClient client;
  private final Journal journal;
  private final AgentExecutor executor;
  private final EventSender sender;
  private final ArtifactSender artifactSender;
  private final ProcessSupervisor supervisor;
  private final List<ProviderInfo> providers;
  private final QuotaPoller quotas;
  private final ScheduledExecutorService heartbeats =
      Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().factory());
  private volatile boolean running;
  private Thread poller;

  public RunnerDaemon(
      RunnerConfig config,
      ControlPlaneClient client,
      Journal journal,
      AgentExecutor executor,
      EventSender sender,
      ArtifactSender artifactSender,
      ProcessSupervisor supervisor,
      List<ProviderInfo> providers) {
    this(
        config,
        client,
        journal,
        executor,
        sender,
        artifactSender,
        supervisor,
        providers,
        new QuotaPoller(List.of(), null));
  }

  public RunnerDaemon(
      RunnerConfig config,
      ControlPlaneClient client,
      Journal journal,
      AgentExecutor executor,
      EventSender sender,
      ArtifactSender artifactSender,
      ProcessSupervisor supervisor,
      List<ProviderInfo> providers,
      QuotaPoller quotas) {
    this.quotas = quotas;
    this.config = config;
    this.client = client;
    this.journal = journal;
    this.executor = executor;
    this.sender = sender;
    this.artifactSender = artifactSender;
    this.supervisor = supervisor;
    this.providers = List.copyOf(providers);
  }

  public void start() throws IOException, InterruptedException {
    authenticate();
    recoverUnfinished();
    sender.start();
    artifactSender.start();
    running = true;
    quotas.start();
    heartbeats.scheduleWithFixedDelay(
        this::heartbeat, 0, config.heartbeatInterval().toMillis(), TimeUnit.MILLISECONDS);
    heartbeats.scheduleWithFixedDelay(this::purgeLogs, 0, 1, TimeUnit.HOURS);
    poller = Thread.ofPlatform().name("command-poller").start(this::pollLoop);
  }

  /**
   * Cierra las invocaciones que una ejecución anterior del runner dejó a medias: mata sus procesos
   * si siguen vivos (ya no hay quien lea su salida) y registra su fin.
   */
  void recoverUnfinished() {
    Map<UUID, Journal.TrackedProcess> processes =
        journal.processes().stream()
            .collect(java.util.stream.Collectors.toMap(Journal.TrackedProcess::agentRunId, p -> p));
    for (UUID agentRunId : journal.unfinishedStarts()) {
      Journal.TrackedProcess process = processes.get(agentRunId);
      if (process != null) {
        supervisor.terminateOrphan(process.pid(), process.startedAt(), config.cancelGrace());
      }
      journal.finish(
          agentRunId, Map.of("error", "El runner se reinició durante la ejecución"), Instant.now());
    }
    for (Journal.PendingVerification verification : journal.unfinishedVerifications()) {
      Journal.TrackedProcess process = processes.get(verification.verificationRunId());
      if (process != null) {
        supervisor.terminateOrphan(process.pid(), process.startedAt(), config.cancelGrace());
      }
      journal.finishVerification(
          verification.verificationRunId(),
          verification.agentRunId(),
          Map.of(
              "verificationRunId",
              verification.verificationRunId().toString(),
              "error",
              "El runner se reinició durante la verificación"),
          Instant.now());
    }
    sender.wakeUp();
  }

  private void authenticate() throws IOException, InterruptedException {
    String url = config.controlPlane().toString();
    var saved = journal.credentials().filter(c -> c.controlPlane().equals(url));
    if (saved.isPresent()) {
      client.useToken(saved.get().token());
      return;
    }
    register();
  }

  private void register() throws IOException, InterruptedException {
    if (config.registrationToken() == null || config.registrationToken().isBlank()) {
      throw new IOException(
          "El runner no está registrado: define SKYNET_RUNNER_REGISTRATION_TOKEN");
    }
    RunnerRegistered registered =
        client.register(
            new RunnerRegistration(
                config.name(),
                config.registrationToken(),
                RunnerMain.version(),
                config.capacity(),
                claudeVersion(),
                providers));
    journal.saveCredentials(
        new Journal.Credentials(
            config.controlPlane().toString(), registered.runnerId(), registered.token()));
    client.useToken(registered.token());
    LOG.info("Registrado como " + config.name() + " (" + registered.runnerId() + ")");
  }

  private void pollLoop() {
    Duration backoff = Duration.ofSeconds(1);
    while (running) {
      try {
        List<RunnerCommand> commands = client.commands(config.pollWait());
        for (RunnerCommand command : commands) {
          dispatch(command);
          client.ack(command.id());
        }
        backoff = Duration.ofSeconds(1);
      } catch (UnauthorizedException e) {
        LOG.warning("Token rechazado; registrando de nuevo");
        try {
          register();
        } catch (IOException ex) {
          LOG.log(Level.SEVERE, "No se pudo registrar el runner", ex);
          backoff = sleep(backoff);
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
          return;
        }
      } catch (IOException e) {
        LOG.log(Level.FINE, "Sin conexión con el control plane", e);
        backoff = sleep(backoff);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private void dispatch(RunnerCommand command) {
    // Una orden reentregada (no llegó el ack) no se ejecuta dos veces.
    if (!journal.firstDelivery(command.id(), command.agentRunId(), command.type().name())) {
      return;
    }
    switch (command.type()) {
      case START, RESUME -> executor.start(command);
      case CANCEL -> executor.cancel(command.agentRunId());
      case VERIFY -> executor.verify(command);
      case CLEANUP -> executor.cleanup(command);
    }
  }

  /** Versión de Claude Code, para los control planes que aún no leen {@code providers}. */
  private String claudeVersion() {
    return providers.stream()
        .filter(p -> p.id().equals(AgentProviderKind.CLAUDE_CODE.id()))
        .map(ProviderInfo::version)
        .findFirst()
        .orElse(null);
  }

  private void heartbeat() {
    try {
      // También las que ya terminaron con eventos por enviar: el control plane da por perdida
      // una invocación activa que deja de aparecer aquí.
      Set<UUID> agents = new LinkedHashSet<>(executor.running());
      agents.addAll(journal.agentsWithPendingEvents());
      send(new RunnerHeartbeat(config.capacity(), List.copyOf(agents), providers, quotas.drain()));
    } catch (IOException e) {
      LOG.log(Level.FINE, "No se pudo enviar el latido", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** Si el latido no llega, sus cuotas van en el siguiente. */
  private void send(RunnerHeartbeat heartbeat) throws IOException, InterruptedException {
    try {
      HeartbeatReply reply = client.heartbeat(heartbeat);
      if (reply.refreshQuotas()) {
        quotas.refresh();
      }
    } catch (IOException | RuntimeException e) {
      quotas.restore(heartbeat.quotas());
      throw e;
    }
  }

  private void purgeLogs() {
    try {
      int deleted = executor.purgeLogs(config.logRetention());
      if (deleted > 0) {
        LOG.info(() -> "Borrados " + deleted + " logs con más de " + config.logRetention());
      }
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "No se pudieron limpiar los logs", e);
    }
  }

  private Duration sleep(Duration backoff) {
    try {
      Thread.sleep(backoff);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      running = false;
    }
    Duration next = backoff.multipliedBy(2);
    return next.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : next;
  }

  /** Para de pedir órdenes, termina los agentes en curso y envía lo pendiente si puede. */
  @Override
  public void close() {
    running = false;
    if (poller != null) {
      poller.interrupt();
    }
    heartbeats.shutdownNow();
    quotas.close();
    executor.close();
    sender.flush();
    artifactSender.flush();
    artifactSender.close();
  }
}
