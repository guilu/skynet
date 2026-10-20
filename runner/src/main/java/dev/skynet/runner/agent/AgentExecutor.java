package dev.skynet.runner.agent;

import dev.skynet.protocol.AgentEventType;
import dev.skynet.protocol.AgentFailure;
import dev.skynet.protocol.runner.ArtifactType;
import dev.skynet.protocol.runner.CleanupWorkspace;
import dev.skynet.protocol.runner.ResumeFrom;
import dev.skynet.protocol.runner.RunVerification;
import dev.skynet.protocol.runner.RunnerCommand;
import dev.skynet.protocol.runner.StartAgent;
import dev.skynet.runner.journal.Journal;
import dev.skynet.runner.provider.AgentProvider;
import dev.skynet.runner.provider.CostMeter;
import dev.skynet.runner.provider.EventParser;
import dev.skynet.runner.provider.ParsedEvent;
import dev.skynet.runner.provider.ProviderRegistry;
import dev.skynet.runner.provider.StructuredOutput;
import dev.skynet.runner.supervisor.ProcessExit;
import dev.skynet.runner.supervisor.ProcessSupervisor;
import dev.skynet.runner.supervisor.SupervisedProcess;
import dev.skynet.runner.verify.JUnitReports;
import dev.skynet.runner.verify.StageChecks;
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
 * Ejecuta las invocaciones (arranques, reanudaciones y forks): prepara el worktree, lanza el CLI
 * del proveedor del agente, traduce su salida a eventos en el journal y registra el fin del
 * proceso. Toda invocación que recibe termina con un único evento {@code agent.process.exited},
 * también si no llega a arrancar.
 */
public final class AgentExecutor implements AutoCloseable {

  private static final String ERROR = "error";
  private static final String FAILURE = "failure";

  private static final Logger LOG = Logger.getLogger(AgentExecutor.class.getName());
  private static final ObjectMapper JSON = JsonMapper.builder().build();
  private static final String TEXT = "text/plain; charset=utf-8";
  private static final String JSON_TYPE = "application/json";
  private static final String EXIT_CODE = "exitCode";

  /** Salida de un comando que va en su evento; la completa se sube como artefacto. */
  private static final int MAX_OUTPUT = 16 * 1024;

  private final Journal journal;
  private final ProcessSupervisor supervisor;
  private final WorkspaceManager workspaces;
  private final ProviderRegistry providers;
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
  private final ArtifactSpool spool;

  public AgentExecutor(
      Journal journal,
      ProcessSupervisor supervisor,
      WorkspaceManager workspaces,
      ProviderRegistry providers,
      Map<String, String> runnerEnv,
      Path logs,
      Duration cancelGrace,
      Runnable eventsAvailable,
      ArtifactSpool spool) {
    this.journal = journal;
    this.supervisor = supervisor;
    this.workspaces = workspaces;
    this.providers = providers;
    this.runnerEnv = Map.copyOf(runnerEnv);
    this.logs = logs;
    this.cancelGrace = cancelGrace;
    this.eventsAvailable = eventsAvailable;
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
   * Elimina el worktree de un agente que ya terminó (retención o petición desde la web). Siempre
   * responde con un evento {@code agent.workspace.removed}; con {@code error} si no se pudo.
   */
  public void cleanup(RunnerCommand command) {
    pool.submit(() -> runCleanup(command.agentRunId(), command.id(), command.cleanup()));
  }

  private void runCleanup(UUID agentRunId, UUID commandId, CleanupWorkspace cleanup) {
    Path path = Path.of(cleanup.workspacePath()).toAbsolutePath().normalize();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("workspaceId", cleanup.workspaceId().toString());
    payload.put("path", cleanup.workspacePath());
    if (busyWorkspaces.putIfAbsent(path, commandId) != null) {
      payload.put(ERROR, "Hay una invocación en curso en el worktree " + path);
    } else {
      try {
        workspaces.remove(path);
      } catch (IOException e) {
        payload.put(ERROR, "No se pudo eliminar el worktree: " + e.getMessage());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        payload.put(ERROR, "El runner se detuvo mientras eliminaba el worktree");
      } finally {
        busyWorkspaces.remove(path, commandId);
      }
    }
    emit(agentRunId, AgentEventType.WORKSPACE_REMOVED, payload);
  }

  /**
   * Borra los logs locales (NDJSON del agente y salida de las verificaciones) modificados hace más
   * de {@code retention}. Para entonces ya están subidos como artefactos: el spool guarda su propia
   * copia. Los de las invocaciones en curso no se tocan. Devuelve cuántos borró.
   */
  public int purgeLogs(Duration retention) {
    if (!Files.isDirectory(logs)) {
      return 0;
    }
    Instant cutoff = Instant.now().minus(retention);
    List<String> active = running().stream().map(UUID::toString).toList();
    int deleted = 0;
    try (var files = Files.list(logs)) {
      for (Path file : files.toList()) {
        String name = file.getFileName().toString();
        if (!Files.isRegularFile(file)
            || active.stream().anyMatch(name::startsWith)
            || Files.getLastModifiedTime(file).toInstant().isAfter(cutoff)) {
          continue;
        }
        if (Files.deleteIfExists(file)) {
          deleted++;
        }
      }
    } catch (IOException e) {
      LOG.log(Level.WARNING, e, () -> "No se pudieron limpiar los logs de " + logs);
    }
    return deleted;
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
      AgentProvider provider = null;
      if (start.command() == null) {
        provider = providers.get(start.providerOrDefault()).orElse(null);
        if (provider == null) {
          finish(
              id,
              failure(
                  AgentFailure.START,
                  "Este runner no tiene "
                      + ProviderRegistry.displayName(start.providerOrDefault())
                      + " disponible"));
          return;
        }
      }
      Workspace workspace = prepareOrFinish(id, start, provider);
      if (workspace != null) {
        runExclusively(id, start, provider, execution, workspace);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (RuntimeException e) {
      LOG.log(Level.SEVERE, e, () -> "Fallo inesperado en la invocación " + id);
      finish(id, failure(AgentFailure.ERROR, "Fallo interno del runner: " + e));
    } finally {
      executions.remove(id);
    }
  }

  /** Prepara el worktree; si no se puede, registra el fin de la invocación y devuelve null. */
  private Workspace prepareOrFinish(UUID id, StartAgent start, AgentProvider provider)
      throws InterruptedException {
    try {
      return prepare(id, start, provider);
    } catch (IOException e) {
      finish(id, failure(AgentFailure.START, "No se pudo preparar el worktree: " + e.getMessage()));
      return null;
    }
  }

  /** Un solo escritor por worktree, aunque el control plane ya lo garantiza. */
  private void runExclusively(
      UUID id, StartAgent start, AgentProvider provider, Execution execution, Workspace workspace)
      throws InterruptedException {
    if (busyWorkspaces.putIfAbsent(workspace.path(), id) != null) {
      finish(
          id,
          failure(
              AgentFailure.START,
              "Ya hay otra invocación en curso en el worktree " + workspace.path()));
      return;
    }
    try {
      run(id, start, provider, execution, workspace);
    } finally {
      busyWorkspaces.remove(workspace.path(), id);
    }
  }

  /**
   * Worktree de la invocación: uno nuevo para un arranque, el indicado para una fase que continúa
   * el de otra, uno nuevo desde una instantánea del indicado para un hijo de una fase {@code
   * parallel}, el de la invocación anterior para reanudar y uno nuevo desde ese para bifurcar, con
   * la sesión copiada si el proveedor la guarda por directorio.
   */
  private Workspace prepare(UUID id, StartAgent start, AgentProvider provider)
      throws IOException, InterruptedException {
    ResumeFrom resume = start.resume();
    if (resume == null && start.workspace() != null) {
      Path path = Path.of(start.workspace().path());
      return start.workspace().copy()
          ? workspaces.fork(path, start.workflowRunId(), start.workItemKey(), id)
          : workspaces.continued(path, start.workspace().restore(), id);
    }
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
    if (!provider.copySession(resume.sessionId(), parent, fork.path())) {
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

  private void run(
      UUID id, StartAgent start, AgentProvider provider, Execution execution, Workspace workspace)
      throws InterruptedException {
    Map<String, Object> ready = new LinkedHashMap<>();
    ready.put("path", workspace.path().toString());
    ready.put("branch", workspace.branch());
    ready.put("baseCommit", workspace.baseCommit());
    if (workspace.snapshot() != null) {
      ready.put("snapshot", workspace.snapshot());
    }
    emit(id, AgentEventType.WORKSPACE_READY, ready);
    if (start.command() != null) {
      runCommand(id, start, execution, workspace);
      return;
    }
    StartAgent resolved;
    try {
      resolved =
          start.withOutputSchema(StructuredOutput.resolve(start.outputSchema(), workspace.path()));
    } catch (IllegalArgumentException | IOException e) {
      finish(
          id,
          failure(AgentFailure.VERIFICATION, "Salida estructurada de la fase: " + e.getMessage()));
      return;
    }
    launch(id, resolved, provider, execution, workspace);
  }

  /** Lanza el CLI del proveedor en el worktree y sigue su salida hasta que termina. */
  private void launch(
      UUID id, StartAgent start, AgentProvider provider, Execution execution, Workspace workspace)
      throws InterruptedException {

    List<String> refused = provider.refusedEnvironment(start);
    if (!refused.isEmpty()) {
      LOG.warning(
          () ->
              "La invocación "
                  + id
                  + " pide variables que el runner no permite (SKYNET_AGENT_ENV): "
                  + refused);
    }
    EventParser parser = provider.newParser(start);
    BigDecimal budget = start.limits().maxBudgetUsd();
    CostMeter cost = provider.newCostMeter(start);
    SupervisedProcess process;
    try {
      process =
          execution.launch(
              () ->
                  supervisor.start(
                      provider.command(start, logs.resolve(id.toString())),
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
      finish(id, failure(AgentFailure.START, "No se pudo lanzar el agente: " + e.getMessage()));
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
    // Lo que el CLI no dice en su salida (la cuota de Codex), antes del fin.
    provider.afterExit(parser, runnerEnv).forEach(e -> emit(id, e));
    // Antes del fin: así el agente termina con sus artefactos ya en cola.
    collectArtifacts(id, start, workspace, logs.resolve(id + ".ndjson"));

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put(EXIT_CODE, exit.exitCode());
    if (exit.signal() != null) {
      payload.put("signal", exit.signal());
    }
    if (execution.stopping) {
      payload.put(ERROR, "El runner se detuvo durante la ejecución");
      payload.put(FAILURE, AgentFailure.LOST.id());
    } else if (execution.timedOut && !execution.cancelled()) {
      payload.put(ERROR, "Se agotó el tiempo máximo (" + timeout + ")");
      payload.put(FAILURE, AgentFailure.TIMEOUT.id());
    } else if (execution.overBudget && !execution.cancelled()) {
      payload.put(FAILURE, AgentFailure.BUDGET.id());
      payload.put(
          ERROR,
          "Presupuesto agotado: el coste estimado ("
              + usd(cost.estimatedUsd())
              + ") supera el máximo de "
              + usd(budget));
    }
    payload.put("estimatedCostUsd", cost.estimatedUsd().setScale(6, RoundingMode.HALF_UP));
    // Tokens que ningún evento llevó (cancelado justo después de pensar): no se pierden.
    Map<String, Object> unreported = parser.unreportedUsage();
    if (unreported != null) {
      payload.put("usage", unreported);
    }
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
    collectChanges(id, workspace);
  }

  /** Rama, commits y diff del worktree respecto a su commit de partida. */
  private void collectChanges(UUID id, Workspace workspace) throws InterruptedException {
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

  /**
   * Fase {@code command}: ejecuta el comando con {@code sh -c} en el worktree, con el entorno de un
   * agente. Se cuenta como una sola herramienta {@code Bash} (empieza y termina con su salida), y
   * termina bien solo con código 0. Sube la salida completa y los cambios del worktree.
   */
  private void runCommand(UUID id, StartAgent start, Execution execution, Workspace workspace)
      throws InterruptedException {
    String toolUseId = "command-" + id;
    Map<String, Object> started = new LinkedHashMap<>();
    started.put("toolUseId", toolUseId);
    started.put("name", "Bash");
    started.put("input", Map.of("command", start.command()));
    emit(id, AgentEventType.TOOL_STARTED, started);

    Path log = logs.resolve(id + ".log");
    SupervisedProcess process;
    try {
      // stderr al mismo sitio que stdout: la salida se guarda entera, en orden.
      process =
          execution.launch(
              () ->
                  supervisor.start(
                      List.of("sh", "-c", "exec 2>&1\n" + start.command()),
                      workspace.path(),
                      providers.commandEnvironment(runnerEnv, start.environment()),
                      log,
                      line -> {}));
    } catch (IOException e) {
      finish(id, failure(AgentFailure.START, "No se pudo ejecutar el comando: " + e.getMessage()));
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

    String output = tail(log);
    Map<String, Object> completed = new LinkedHashMap<>();
    completed.put("toolUseId", toolUseId);
    completed.put("name", "Bash");
    completed.put("isError", exit.exitCode() != 0);
    completed.put("output", output);
    if (output.length() < sizeOf(log)) {
      completed.put("outputTruncated", true);
    }
    emit(id, AgentEventType.TOOL_COMPLETED, completed);

    artifact(
        id,
        "PROMPT",
        () ->
            spool.add(
                id,
                null,
                ArtifactType.PROMPT,
                "command.txt",
                TEXT,
                start.command().getBytes(StandardCharsets.UTF_8),
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
                  "command.log",
                  TEXT,
                  log,
                  Map.of(EXIT_CODE, exit.exitCode(), "bytes", Files.size(log))));
    }
    collectChanges(id, workspace);

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put(EXIT_CODE, exit.exitCode());
    if (exit.signal() != null) {
      payload.put("signal", exit.signal());
    }
    if (execution.stopping) {
      payload.put(ERROR, "El runner se detuvo durante la ejecución");
      payload.put(FAILURE, AgentFailure.LOST.id());
    } else if (execution.timedOut && !execution.cancelled()) {
      payload.put(ERROR, "Se agotó el tiempo máximo (" + timeout + ")");
      payload.put(FAILURE, AgentFailure.TIMEOUT.id());
    }
    finish(id, payload);
  }

  /** Los últimos {@link #MAX_OUTPUT} bytes de la salida de un comando, como texto. */
  private static String tail(Path log) {
    try {
      long size = sizeOf(log);
      if (size == 0) {
        return "";
      }
      try (var channel = java.nio.channels.FileChannel.open(log)) {
        long from = Math.max(0, size - MAX_OUTPUT);
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate((int) (size - from));
        channel.read(buffer, from);
        return new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
      }
    } catch (IOException e) {
      LOG.log(Level.WARNING, e, () -> "No se pudo leer la salida " + log);
      return "";
    }
  }

  private static long sizeOf(Path file) {
    try {
      return Files.exists(file) ? Files.size(file) : 0;
    } catch (IOException e) {
      return 0;
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
    if (verification.command() != null) {
      started.put("command", verification.command());
    }
    if (verification.hasChecks()) {
      started.put("checks", verification.checks().stream().map(c -> c.name()).toList());
    }
    emit(agentRunId, AgentEventType.VERIFICATION_STARTED, started);

    StageChecks.TestsRun tests = new StageChecks.TestsRun(false, null, false, null);
    if (verification.command() != null) {
      tests = runValidation(agentRunId, verification, workspace, result);
      if (tests == null) {
        return;
      }
    }
    if (verification.hasChecks()) {
      result.put(
          "checks",
          StageChecks.run(
              workspace.path(),
              verification.checks(),
              verification.baseCommit(),
              tests,
              (command, index) ->
                  runCheckCommand(agentRunId, verification, workspace, command, index)));
    }
  }

  /**
   * El comando de validación del repositorio y sus informes JUnit. Devuelve {@code null} si no se
   * pudo lanzar (el error queda en {@code result}).
   */
  private StageChecks.TestsRun runValidation(
      UUID agentRunId,
      RunVerification verification,
      Workspace workspace,
      Map<String, Object> result)
      throws IOException, InterruptedException {
    UUID id = verification.verificationRunId();
    Instant since = Instant.now().minusSeconds(1);
    Path log = logs.resolve("verification-" + id + ".log");
    Shell shell;
    try {
      shell = shell(id, verification.command(), workspace, log, verification.timeout());
    } catch (IOException e) {
      result.put(ERROR, "No se pudo ejecutar el comando de verificación: " + e.getMessage());
      return null;
    }
    result.put(EXIT_CODE, shell.exit().exitCode());
    if (shell.exit().signal() != null) {
      result.put("signal", shell.exit().signal());
    }
    // En la verificación de una fase, el tiempo agotado es el motivo del check `tests`.
    if (shell.timedOut() && !verification.hasChecks()) {
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
                  Map.of(EXIT_CODE, shell.exit().exitCode())));
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
    return new StageChecks.TestsRun(
        true, shell.exit().exitCode(), shell.timedOut(), tests.found() ? tests.totals() : null);
  }

  /** Un check {@code command}: su salida se sube como {@code check-<n>.log}. */
  private StageChecks.CommandRun runCheckCommand(
      UUID agentRunId, RunVerification verification, Workspace workspace, String command, int index)
      throws InterruptedException {
    UUID id = verification.verificationRunId();
    String name = "check-" + (index + 1) + ".log";
    Path log = logs.resolve("verification-" + id + "-" + name);
    Shell shell;
    try {
      shell = shell(id, command, workspace, log, verification.timeout());
    } catch (IOException e) {
      return new StageChecks.CommandRun(
          null, false, "No se pudo ejecutar `" + command + "`: " + e.getMessage());
    }
    if (Files.exists(log)) {
      artifact(
          agentRunId,
          "VERIFICATION_LOG",
          () ->
              spool.addFile(
                  agentRunId,
                  id,
                  ArtifactType.VERIFICATION_LOG,
                  name,
                  TEXT,
                  log,
                  Map.of(EXIT_CODE, shell.exit().exitCode(), "command", command)));
    }
    return new StageChecks.CommandRun(shell.exit().exitCode(), shell.timedOut(), null);
  }

  /** Cómo terminó un comando de una verificación. */
  private record Shell(ProcessExit exit, boolean timedOut) {}

  /**
   * Ejecuta {@code command} con {@code sh -c} en el worktree, con el entorno de los comandos, la
   * salida (stdout y stderr, en orden) en {@code log} y {@code timeout} como tiempo máximo.
   */
  private Shell shell(UUID id, String command, Workspace workspace, Path log, Duration timeout)
      throws IOException, InterruptedException {
    SupervisedProcess process =
        supervisor.start(
            List.of("sh", "-c", "exec 2>&1\n" + command),
            workspace.path(),
            providers.commandEnvironment(runnerEnv, null),
            log,
            line -> {});
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
            timeout.toMillis(),
            TimeUnit.MILLISECONDS);
    try {
      return new Shell(process.awaitExit(cancelGrace), timedOut.get());
    } finally {
      deadline.cancel(false);
      verifications.remove(id);
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

  /** Fin sin proceso que sabe por qué falló: {@code failure} decide si la fase se reintenta. */
  private static Map<String, Object> failure(AgentFailure kind, String error) {
    return Map.of(ERROR, error, FAILURE, kind.id());
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

  /** Estado de una invocación en curso. Lanzar y cancelar se excluyen mutuamente. */
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
