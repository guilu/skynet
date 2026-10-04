package dev.skynet.runner;

import dev.skynet.protocol.runner.RunnerCommand;
import dev.skynet.protocol.runner.RunnerHeartbeat;
import dev.skynet.protocol.runner.RunnerRegistered;
import dev.skynet.protocol.runner.RunnerRegistration;
import dev.skynet.runner.agent.AgentExecutor;
import dev.skynet.runner.agent.EventSender;
import dev.skynet.runner.journal.Journal;
import dev.skynet.runner.supervisor.ProcessSupervisor;
import dev.skynet.runner.transport.ControlPlaneClient;
import dev.skynet.runner.transport.UnauthorizedException;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
  private final ProcessSupervisor supervisor;
  private final String providerVersion;
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
      ProcessSupervisor supervisor,
      String providerVersion) {
    this.config = config;
    this.client = client;
    this.journal = journal;
    this.executor = executor;
    this.sender = sender;
    this.supervisor = supervisor;
    this.providerVersion = providerVersion;
  }

  public void start() throws IOException, InterruptedException {
    authenticate();
    recoverUnfinished();
    sender.start();
    running = true;
    heartbeats.scheduleWithFixedDelay(
        this::heartbeat, 0, config.heartbeatInterval().toMillis(), TimeUnit.MILLISECONDS);
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
                providerVersion));
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
      case START -> executor.start(command);
      case CANCEL -> executor.cancel(command.agentRunId());
      default -> LOG.warning("Orden no soportada todavía: " + command.type());
    }
  }

  private void heartbeat() {
    try {
      client.heartbeat(new RunnerHeartbeat(config.capacity(), executor.running()));
    } catch (IOException e) {
      LOG.log(Level.FINE, "No se pudo enviar el latido", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
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
    executor.close();
    sender.flush();
  }
}
