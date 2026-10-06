package dev.skynet.runner.agent;

import dev.skynet.protocol.AgentEventType;
import dev.skynet.protocol.runner.ResumeFrom;
import dev.skynet.protocol.runner.RunnerCommand;
import dev.skynet.protocol.runner.StartAgent;
import dev.skynet.runner.journal.Journal;
import dev.skynet.runner.provider.ParsedEvent;
import dev.skynet.runner.provider.claude.ClaudeCodeProvider;
import dev.skynet.runner.provider.claude.ClaudeSessions;
import dev.skynet.runner.provider.claude.ClaudeStreamParser;
import dev.skynet.runner.supervisor.ProcessExit;
import dev.skynet.runner.supervisor.ProcessSupervisor;
import dev.skynet.runner.supervisor.SupervisedProcess;
import dev.skynet.runner.workspace.Workspace;
import dev.skynet.runner.workspace.WorkspaceManager;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Ejecuta las invocaciones (arranques, reanudaciones y forks): prepara el worktree, lanza Claude
 * Code, traduce su salida a eventos en el journal y registra el fin del proceso. Toda invocación
 * que recibe termina con un único evento {@code agent.process.exited}, también si no llega a
 * arrancar.
 */
public final class AgentExecutor implements AutoCloseable {

  private static final Logger LOG = Logger.getLogger(AgentExecutor.class.getName());

  private final Journal journal;
  private final ProcessSupervisor supervisor;
  private final WorkspaceManager workspaces;
  private final ClaudeCodeProvider provider;
  private final Map<String, String> runnerEnv;
  private final Path logs;
  private final Duration cancelGrace;
  private final Runnable eventsAvailable;
  private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
  private final ScheduledExecutorService timer =
      Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().factory());
  private final Map<UUID, Execution> executions = new ConcurrentHashMap<>();
  private final Map<Path, UUID> busyWorkspaces = new ConcurrentHashMap<>();
  private final ClaudeSessions sessions;

  public AgentExecutor(
      Journal journal,
      ProcessSupervisor supervisor,
      WorkspaceManager workspaces,
      ClaudeCodeProvider provider,
      Map<String, String> runnerEnv,
      Path logs,
      Duration cancelGrace,
      Runnable eventsAvailable) {
    this.journal = journal;
    this.supervisor = supervisor;
    this.workspaces = workspaces;
    this.provider = provider;
    this.runnerEnv = Map.copyOf(runnerEnv);
    this.logs = logs;
    this.cancelGrace = cancelGrace;
    this.eventsAvailable = eventsAvailable;
    this.sessions = ClaudeSessions.of(runnerEnv).orElse(null);
  }

  /** Invocaciones en curso en este runner. */
  public List<UUID> running() {
    return List.copyOf(executions.keySet());
  }

  public void start(RunnerCommand command) {
    UUID id = command.agentRunId();
    Execution execution = new Execution();
    if (journal.isFinished(id) || executions.putIfAbsent(id, execution) != null) {
      return;
    }
    pool.submit(() -> run(id, command.start(), execution));
  }

  /**
   * Verifica el worktree de un agente. Todavía no está implementado (M5-B): responde con un error
   * para que la verificación no quede en cola y no bloquee el worktree.
   */
  public void verify(RunnerCommand command) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("verificationRunId", command.verify().verificationRunId().toString());
    payload.put("error", "Este runner todavía no sabe ejecutar verificaciones");
    emit(command.agentRunId(), AgentEventType.VERIFICATION_COMPLETED, payload);
  }

  /**
   * Cancela una invocación: termina su árbol de procesos. Si no se está ejecutando aquí (no llegó a
   * arrancar, o se perdió en un reinicio), registra directamente su fin para que el control plane
   * la cierre.
   */
  public void cancel(UUID agentRunId) {
    Execution execution = executions.get(agentRunId);
    if (execution == null) {
      if (!journal.isFinished(agentRunId)) {
        finish(agentRunId, failure("Cancelado: el agente no se estaba ejecutando en este runner"));
      }
      return;
    }
    SupervisedProcess process = execution.cancel();
    if (process != null) {
      pool.submit(() -> process.terminate(cancelGrace));
    }
  }

  private void run(UUID id, StartAgent start, Execution execution) {
    try {
      Workspace workspace = prepareOrFinish(id, start);
      if (workspace != null) {
        runExclusively(id, start, execution, workspace);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (RuntimeException e) {
      LOG.log(Level.SEVERE, e, () -> "Fallo inesperado en la invocación " + id);
      finish(id, failure("Fallo interno del runner: " + e));
    } finally {
      executions.remove(id);
    }
  }

  /** Prepara el worktree; si no se puede, registra el fin de la invocación y devuelve null. */
  private Workspace prepareOrFinish(UUID id, StartAgent start) throws InterruptedException {
    try {
      return prepare(id, start);
    } catch (IOException e) {
      finish(id, failure("No se pudo preparar el worktree: " + e.getMessage()));
      return null;
    }
  }

  /** Un solo escritor por worktree, aunque el control plane ya lo garantiza. */
  private void runExclusively(UUID id, StartAgent start, Execution execution, Workspace workspace)
      throws InterruptedException {
    if (busyWorkspaces.putIfAbsent(workspace.path(), id) != null) {
      finish(id, failure("Ya hay otra invocación en curso en el worktree " + workspace.path()));
      return;
    }
    try {
      run(id, start, execution, workspace);
    } finally {
      busyWorkspaces.remove(workspace.path(), id);
    }
  }

  /**
   * Worktree de la invocación: uno nuevo para un arranque, el de la invocación anterior para
   * reanudar y uno nuevo desde ese para bifurcar, con la sesión copiada para que {@code --resume}
   * la encuentre desde allí.
   */
  private Workspace prepare(UUID id, StartAgent start) throws IOException, InterruptedException {
    ResumeFrom resume = start.resume();
    if (resume == null) {
      return workspaces.create(
          Path.of(start.repositoryPath()),
          start.baseBranch(),
          start.workflowRunId(),
          start.workItemKey(),
          id);
    }
    Path parent = Path.of(resume.workspacePath());
    if (!resume.fork()) {
      return workspaces.existing(parent);
    }
    Workspace fork = workspaces.fork(parent, start.workflowRunId(), start.workItemKey(), id);
    if (sessions == null || !sessions.copy(resume.sessionId(), parent, fork.path())) {
      LOG.warning(
          () ->
              "No se encontró la sesión "
                  + resume.sessionId()
                  + " de "
                  + parent
                  + " para copiarla");
    }
    return fork;
  }

  private void run(UUID id, StartAgent start, Execution execution, Workspace workspace)
      throws InterruptedException {
    Map<String, Object> ready = new LinkedHashMap<>();
    ready.put("path", workspace.path().toString());
    ready.put("branch", workspace.branch());
    ready.put("baseCommit", workspace.baseCommit());
    emit(id, AgentEventType.WORKSPACE_READY, ready);

    ClaudeStreamParser parser = new ClaudeStreamParser();
    SupervisedProcess process;
    try {
      process =
          execution.launch(
              () ->
                  supervisor.start(
                      provider.command(start),
                      workspace.path(),
                      provider.environment(runnerEnv),
                      logs.resolve(id + ".ndjson"),
                      line -> parser.parse(line).forEach(e -> emit(id, e))));
    } catch (IOException e) {
      finish(id, failure("No se pudo lanzar el agente: " + e.getMessage()));
      return;
    }
    if (process == null) {
      finish(id, failure("Cancelado antes de arrancar"));
      return;
    }
    journal.processStarted(id, process.pid(), process.startedAt());

    Duration timeout = start.limits().timeout();
    ScheduledFuture<?> deadline =
        timeout == null
            ? null
            : timer.schedule(
                () -> {
                  execution.timedOut = true;
                  process.terminate(cancelGrace);
                },
                timeout.toMillis(),
                TimeUnit.MILLISECONDS);
    ProcessExit exit = process.awaitExit(cancelGrace);
    if (deadline != null) {
      deadline.cancel(false);
    }

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("exitCode", exit.exitCode());
    if (exit.signal() != null) {
      payload.put("signal", exit.signal());
    }
    if (execution.stopping) {
      payload.put("error", "El runner se detuvo durante la ejecución");
    } else if (execution.timedOut && !execution.cancelled()) {
      payload.put("error", "Se agotó el tiempo máximo (" + timeout + ")");
    }
    if (!parser.sawResult() && exit.exitCode() != 0 && !exit.stderrTail().isBlank()) {
      payload.put("stderr", exit.stderrTail());
    }
    finish(id, payload);
  }

  private void emit(UUID id, ParsedEvent event) {
    emit(id, event.type(), event.payload());
  }

  private void emit(UUID id, AgentEventType type, Map<String, Object> payload) {
    journal.append(id, type, payload, Instant.now());
    eventsAvailable.run();
  }

  private void finish(UUID id, Map<String, Object> payload) {
    journal.finish(id, payload, Instant.now());
    eventsAvailable.run();
  }

  private static Map<String, Object> failure(String error) {
    return Map.of("error", error);
  }

  /** Termina todas las invocaciones en curso (al parar el runner) y espera su evento de fin. */
  @Override
  public void close() {
    for (UUID id : running()) {
      Execution execution = executions.get(id);
      if (execution != null) {
        execution.stopping = true;
      }
      cancel(id);
    }
    pool.shutdown();
    try {
      if (!pool.awaitTermination(cancelGrace.multipliedBy(2).toMillis(), TimeUnit.MILLISECONDS)) {
        LOG.warning("Quedan invocaciones sin terminar al parar el runner");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    timer.shutdownNow();
  }

  /** Estado de una invocación en curso. Lanzar y cancelar se excluyen mutuamente. */
  private static final class Execution {
    private SupervisedProcess process;
    private boolean cancelled;
    volatile boolean timedOut;
    volatile boolean stopping;

    /** Lanza el proceso salvo que ya se haya cancelado; en ese caso devuelve {@code null}. */
    synchronized SupervisedProcess launch(Launcher launcher) throws IOException {
      if (cancelled) {
        return null;
      }
      process = launcher.launch();
      return process;
    }

    /** Marca la invocación como cancelada y devuelve su proceso, si ya se lanzó. */
    synchronized SupervisedProcess cancel() {
      cancelled = true;
      return process;
    }

    synchronized boolean cancelled() {
      return cancelled;
    }
  }

  @FunctionalInterface
  private interface Launcher {
    SupervisedProcess launch() throws IOException;
  }
}
