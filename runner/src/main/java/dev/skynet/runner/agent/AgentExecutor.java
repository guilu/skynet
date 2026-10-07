package dev.skynet.runner.agent;

import dev.skynet.protocol.AgentEventType;
import dev.skynet.protocol.runner.ArtifactType;
import dev.skynet.protocol.runner.ResumeFrom;
import dev.skynet.protocol.runner.RunVerification;
import dev.skynet.protocol.runner.RunnerCommand;
import dev.skynet.protocol.runner.StartAgent;
import dev.skynet.runner.journal.Journal;
import dev.skynet.runner.provider.ParsedEvent;
import dev.skynet.runner.provider.claude.ClaudeCodeProvider;
import dev.skynet.runner.provider.claude.ClaudeSessions;
import dev.skynet.runner.provider.claude.ClaudeStreamParser;
import dev.skynet.runner.provider.claude.CostEstimator;
import dev.skynet.runner.supervisor.ProcessExit;
import dev.skynet.runner.supervisor.ProcessSupervisor;
import dev.skynet.runner.supervisor.SupervisedProcess;
import dev.skynet.runner.verify.JUnitReports;
import dev.skynet.runner.workspace.GitIndexer;
import dev.skynet.runner.workspace.Workspace;
import dev.skynet.runner.workspace.WorkspaceManager;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ejecuta las invocaciones (arranques, reanudaciones y forks): prepara el worktree, lanza Claude
 * Code, traduce su salida a eventos en el journal y registra el fin del proceso. Toda invocación
 * que recibe termina con un único evento {@code agent.process.exited}, también si no llega a
 * arrancar.
 */
public final class AgentExecutor implements AutoCloseable {

  private static final String ERROR = "error";

  private static final Logger LOG = Logger.getLogger(AgentExecutor.class.getName());
  private static final ObjectMapper JSON = JsonMapper.builder().build();
  private static final String TEXT = "text/plain; charset=utf-8";
  private static final String JSON_TYPE = "application/json";
  private static final String EXIT_CODE = "exitCode";

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
  private final Map<UUID, SupervisedProcess> verifications = new ConcurrentHashMap<>();
  private final ClaudeSessions sessions;
  private final ArtifactSpool spool;

  public AgentExecutor(
      Journal journal,
      ProcessSupervisor supervisor,
      WorkspaceManager workspaces,
      ClaudeCodeProvider provider,
      Map<String, String> runnerEnv,
      Path logs,
      Duration cancelGrace,
      Runnable eventsAvailable,
      ArtifactSpool spool) {
    this.journal = journal;
    this.supervisor = supervisor;
    this.workspaces = workspaces;
    this.provider = provider;
    this.runnerEnv = Map.copyOf(runnerEnv);
    this.logs = logs;
    this.cancelGrace = cancelGrace;
    this.eventsAvailable = eventsAvailable;
    this.sessions = ClaudeSessions.of(runnerEnv).orElse(null);
    this.spool = spool;
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
   * Verifica el worktree de un agente: ejecuta el comando de validación del repositorio, lee los
   * informes JUnit y sube su salida y su resultado. Toda verificación recibida termina con un único
   * evento {@code agent.verification.completed}.
   */
  public void verify(RunnerCommand command) {
    RunVerification verification = command.verify();
    if (!journal.firstVerification(verification.verificationRunId(), command.agentRunId())) {
      return;
    }
    pool.submit(() -> runVerification(command.agentRunId(), verification));
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

    List<String> refused = provider.refusedEnvironment(start);
    if (!refused.isEmpty()) {
      LOG.warning(
          () ->
              "La invocación "
                  + id
                  + " pide variables que el runner no permite (SKYNET_AGENT_ENV): "
                  + refused);
    }
    ClaudeStreamParser parser = new ClaudeStreamParser();
    BigDecimal budget = start.limits().maxBudgetUsd();
    CostEstimator cost = provider.costEstimator();
    SupervisedProcess process;
    try {
      process =
          execution.launch(
              () ->
                  supervisor.start(
                      provider.command(start),
                      workspace.path(),
                      provider.environment(runnerEnv, start.environment()),
                      logs.resolve(id + ".ndjson"),
                      line -> {
                        parser.parse(line).forEach(e -> emit(id, e));
                        cost.accept(line);
                        if (budget != null && cost.estimatedUsd().compareTo(budget) > 0) {
                          overBudget(id, execution);
                        }
                      }));
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
    // Antes del fin: así el agente termina con sus artefactos ya en cola.
    collectArtifacts(id, start, workspace, logs.resolve(id + ".ndjson"));

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put(EXIT_CODE, exit.exitCode());
    if (exit.signal() != null) {
      payload.put("signal", exit.signal());
    }
    if (execution.stopping) {
      payload.put(ERROR, "El runner se detuvo durante la ejecución");
    } else if (execution.timedOut && !execution.cancelled()) {
      payload.put(ERROR, "Se agotó el tiempo máximo (" + timeout + ")");
    } else if (execution.overBudget && !execution.cancelled()) {
      payload.put(
          ERROR,
          "Presupuesto agotado: el coste estimado ("
              + usd(cost.estimatedUsd())
              + ") supera el máximo de "
              + usd(budget));
    }
    payload.put("estimatedCostUsd", cost.estimatedUsd().setScale(6, RoundingMode.HALF_UP));
    if (!cost.unpricedModels().isEmpty()) {
      payload.put("unpricedModels", List.copyOf(cost.unpricedModels()));
    }
    if (!parser.sawResult() && exit.exitCode() != 0 && !exit.stderrTail().isBlank()) {
      payload.put("stderr", exit.stderrTail());
    }
    finish(id, payload);
  }

  /**
   * Artefactos de la invocación: prompt, NDJSON bruto, resultado final, y rama, commits y diff del
   * worktree. Un artefacto que falla no impide los demás ni el fin de la invocación.
   */
  private void collectArtifacts(UUID id, StartAgent start, Workspace workspace, Path log)
      throws InterruptedException {
    artifact(
        id,
        "PROMPT",
        () ->
            spool.add(
                id,
                null,
                ArtifactType.PROMPT,
                "prompt.txt",
                TEXT,
                start.prompt().getBytes(StandardCharsets.UTF_8),
                Map.of()));
    if (Files.exists(log)) {
      artifact(
          id,
          "LOG",
          () ->
              spool.addFile(
                  id,
                  null,
                  ArtifactType.LOG,
                  "agent.ndjson",
                  "application/x-ndjson",
                  log,
                  Map.of("bytes", Files.size(log))));
      artifact(
          id,
          "RESULT",
          () -> {
            java.util.Optional<String> result = resultLine(log);
            if (result.isPresent()) {
              addResult(id, result.get());
            }
          });
    }
    Path diff = logs.resolve(id + ".diff");
    try {
      GitIndexer.Changes changes = GitIndexer.index(workspace.path(), workspace.baseCommit(), diff);
      artifact(
          id,
          "GIT_CHANGES",
          () ->
              spool.add(
                  id,
                  null,
                  ArtifactType.GIT_CHANGES,
                  "changes.json",
                  JSON_TYPE,
                  JSON.writeValueAsBytes(changes.changes()),
                  changes.summary()));
      if (Files.size(diff) == 0) {
        return; // Sin cambios no hay diff que subir.
      }
      artifact(
          id,
          "DIFF",
          () ->
              spool.addFile(
                  id,
                  null,
                  ArtifactType.DIFF,
                  "changes.diff",
                  "text/x-diff",
                  diff,
                  changes.summary()));
    } catch (IOException e) {
      LOG.warning(
          () -> "No se pudo indexar el worktree " + workspace.path() + ": " + e.getMessage());
    } finally {
      deleteQuietly(diff);
    }
  }

  private void addResult(UUID id, String result) throws IOException {
    spool.add(
        id,
        null,
        ArtifactType.RESULT,
        "result.json",
        JSON_TYPE,
        result.getBytes(StandardCharsets.UTF_8),
        Map.of());
  }

  /** Última línea {@code result} del NDJSON: el resultado final que dio el proveedor. */
  private static java.util.Optional<String> resultLine(Path log) throws IOException {
    String last = null;
    try (var lines = Files.lines(log, StandardCharsets.UTF_8)) {
      for (String line : (Iterable<String>) lines::iterator) {
        if (line.contains("\"result\"") && isResult(line)) {
          last = line;
        }
      }
    }
    return java.util.Optional.ofNullable(last);
  }

  private static boolean isResult(String line) {
    try {
      return "result".equals(JSON.readTree(line).path("type").asString(""));
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static void artifact(UUID id, String what, ArtifactWork work) {
    try {
      work.run();
    } catch (IOException | RuntimeException e) {
      LOG.log(Level.WARNING, e, () -> "No se pudo guardar el artefacto " + what + " de " + id);
    }
  }

  @FunctionalInterface
  private interface ArtifactWork {
    void run() throws IOException;
  }

  private static void deleteQuietly(Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      LOG.log(Level.FINE, e, () -> "No se pudo borrar " + file);
    }
  }

  private void runVerification(UUID agentRunId, RunVerification verification) {
    UUID id = verification.verificationRunId();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("verificationRunId", id.toString());
    try {
      Workspace workspace = workspaces.existing(Path.of(verification.workspacePath()));
      if (busyWorkspaces.putIfAbsent(workspace.path(), id) != null) {
        result.put(ERROR, "Hay una invocación en curso en el worktree " + workspace.path());
        return;
      }
      try {
        verifyIn(agentRunId, verification, workspace, result);
      } finally {
        busyWorkspaces.remove(workspace.path(), id);
      }
    } catch (IOException e) {
      result.put(ERROR, "No se pudo verificar el worktree: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      result.put(ERROR, "El runner se detuvo durante la verificación");
    } catch (RuntimeException e) {
      LOG.log(Level.SEVERE, e, () -> "Fallo inesperado en la verificación " + id);
      result.put(ERROR, "Fallo interno del runner: " + e);
    } finally {
      journal.finishVerification(id, agentRunId, result, Instant.now());
      eventsAvailable.run();
    }
  }

  private void verifyIn(
      UUID agentRunId,
      RunVerification verification,
      Workspace workspace,
      Map<String, Object> result)
      throws IOException, InterruptedException {
    UUID id = verification.verificationRunId();
    Map<String, Object> started = new LinkedHashMap<>();
    started.put("verificationRunId", id.toString());
    started.put("command", verification.command());
    emit(agentRunId, AgentEventType.VERIFICATION_STARTED, started);

    Instant since = Instant.now().minusSeconds(1);
    Path log = logs.resolve("verification-" + id + ".log");
    SupervisedProcess process;
    try {
      // stderr al mismo sitio que stdout: la salida se guarda entera, en orden.
      process =
          supervisor.start(
              List.of("sh", "-c", "exec 2>&1\n" + verification.command()),
              workspace.path(),
              provider.environment(runnerEnv),
              log,
              line -> {});
    } catch (IOException e) {
      result.put(ERROR, "No se pudo ejecutar el comando de verificación: " + e.getMessage());
      return;
    }
    verifications.put(id, process);
    journal.processStarted(id, process.pid(), process.startedAt());
    java.util.concurrent.atomic.AtomicBoolean timedOut =
        new java.util.concurrent.atomic.AtomicBoolean();
    ScheduledFuture<?> deadline =
        timer.schedule(
            () -> {
              timedOut.set(true);
              process.terminate(cancelGrace);
            },
            verification.timeout().toMillis(),
            TimeUnit.MILLISECONDS);
    ProcessExit exit;
    try {
      exit = process.awaitExit(cancelGrace);
    } finally {
      deadline.cancel(false);
      verifications.remove(id);
    }
    result.put(EXIT_CODE, exit.exitCode());
    if (exit.signal() != null) {
      result.put("signal", exit.signal());
    }
    if (timedOut.get()) {
      result.put(ERROR, "Se agotó el tiempo máximo (" + verification.timeout() + ")");
    }

    JUnitReports.Summary tests =
        JUnitReports.read(workspace.path(), verification.testReportPaths(), since);
    if (tests.found()) {
      result.put("tests", tests.totals());
    }
    result.put("reports", tests.reports().size());
    if (Files.exists(log)) {
      artifact(
          agentRunId,
          "VERIFICATION_LOG",
          () ->
              spool.addFile(
                  agentRunId,
                  id,
                  ArtifactType.VERIFICATION_LOG,
                  "verification.log",
                  TEXT,
                  log,
                  Map.of(EXIT_CODE, exit.exitCode())));
    }
    if (tests.found() || !tests.unreadable().isEmpty()) {
      artifact(
          agentRunId,
          "TEST_REPORT",
          () ->
              spool.add(
                  agentRunId,
                  id,
                  ArtifactType.TEST_REPORT,
                  "tests.json",
                  JSON_TYPE,
                  JSON.writeValueAsBytes(tests.toMap()),
                  tests.totals()));
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
    return Map.of(ERROR, error);
  }

  /** Termina todas las invocaciones en curso (al parar el runner) y espera su evento de fin. */
  @Override
  public void close() {
    for (SupervisedProcess process : verifications.values()) {
      pool.submit(() -> process.terminate(cancelGrace));
    }
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
  /**
   * El coste estimado pasa del presupuesto: termina el proceso una sola vez. {@code
   * --max-budget-usd} solo se comprueba al final de cada turno, y un turno largo puede gastar mucho
   * más.
   */
  private void overBudget(UUID id, Execution execution) {
    SupervisedProcess process = execution.exceedBudget();
    if (process != null) {
      LOG.warning(() -> "La invocación " + id + " ha agotado su presupuesto: se termina");
      pool.submit(() -> process.terminate(cancelGrace));
    }
  }

  private static String usd(BigDecimal amount) {
    return amount.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + " US$";
  }

  private static final class Execution {
    private SupervisedProcess process;
    private boolean cancelled;
    volatile boolean timedOut;
    volatile boolean stopping;
    volatile boolean overBudget;

    /**
     * Marca la invocación como fuera de presupuesto y devuelve su proceso la primera vez; después,
     * {@code null}.
     */
    synchronized SupervisedProcess exceedBudget() {
      if (overBudget) {
        return null;
      }
      overBudget = true;
      return process;
    }

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
