package dev.skynet.runner.agent;

import dev.skynet.protocol.AgentEventType;
import dev.skynet.protocol.runner.RunnerCommand;
import dev.skynet.protocol.runner.StartAgent;
import dev.skynet.runner.journal.Journal;
import dev.skynet.runner.provider.ParsedEvent;
import dev.skynet.runner.provider.claude.ClaudeCodeProvider;
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
 * Ejecuta las invocaciones: prepara el worktree, lanza Claude Code, traduce su salida a eventos en
 * el journal y registra el fin del proceso. Toda invocación que recibe termina con un único evento
 * {@code agent.process.exited}, también si no llega a arrancar.
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
      Workspace workspace;
      try {
        workspace =
            workspaces.create(
                Path.of(start.repositoryPath()),
                start.baseBranch(),
                start.workflowRunId(),
                start.workItemKey(),
                id);
      } catch (IOException e) {
        finish(id, failure("No se pudo preparar el worktree: " + e.getMessage()));
        return;
      }
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
      ProcessExit exit = process.awaitExit();
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
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (RuntimeException e) {
      LOG.log(Level.SEVERE, "Fallo inesperado en la invocación " + id, e);
      finish(id, failure("Fallo interno del runner: " + e));
    } finally {
      executions.remove(id);
    }
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
