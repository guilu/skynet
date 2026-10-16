package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.definition.AgentDefinition;
import dev.skynet.controlplane.definition.Condition;
import dev.skynet.controlplane.definition.PublishedDefinition;
import dev.skynet.controlplane.definition.StageDefinition;
import dev.skynet.controlplane.definition.StageDefinition.ChildDefinition;
import dev.skynet.controlplane.definition.StageDefinition.Dependency;
import dev.skynet.controlplane.definition.StageDefinition.WorkspaceMode;
import dev.skynet.controlplane.definition.StageType;
import dev.skynet.controlplane.definition.WorkflowDefinition;
import dev.skynet.controlplane.project.CodeRepository;
import dev.skynet.controlplane.project.ProjectService;
import dev.skynet.controlplane.shared.ConflictException;
import dev.skynet.controlplane.shared.TimeSource;
import dev.skynet.controlplane.workitem.WorkItem;
import dev.skynet.controlplane.workitem.WorkItemService;
import dev.skynet.protocol.StageStatus;
import dev.skynet.protocol.WorkflowRunStatus;
import dev.skynet.protocol.runner.AgentLimits;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Los pasos con los que el motor hace avanzar una ejecución: leerla, preparar, omitir, arrancar o
 * cancelar una fase y cerrarla. El motor decide cuáles y cuándo; aquí se aplican con sus eventos,
 * dentro de la transacción del motor. Ninguno publica {@link WorkflowRunChanged}: los cambios que
 * hace el motor no lo vuelven a despertar.
 */
@Component
public class RunSteps {

  /** Estado de una ejecución, tal como lo ve el motor. */
  public record RunState(
      UUID runId, UUID definitionId, WorkflowRunStatus status, Map<String, StageState> stages) {}

  /**
   * Una fase de la ejecución: su último intento y si ya tiene agente.
   *
   * @param hasAgent ya se le ha puesto en cola un agente (no hay que arrancarla otra vez)
   * @param ending {@code FAILED} o {@code CANCELLED} si la fase sigue en curso pero uno de sus
   *     agentes (un hijo de una fase {@code parallel}) ya ha fallado o se ha cancelado: así acabará
   *     la fase en cuanto terminen los demás; {@code null} si no
   */
  public record StageState(String key, StageStatus status, boolean hasAgent, StageStatus ending) {

    public StageState(String key, StageStatus status, boolean hasAgent) {
      this(key, status, hasAgent, null);
    }
  }

  /** Cómo ha ido arrancar una fase. */
  public enum StartOutcome {
    /** Su agente está en cola. */
    STARTED,
    /** El worktree que continúa está ocupado (una verificación): se intenta más tarde. */
    DEFERRED,
    /** No se ha podido arrancar y la fase ha fallado. */
    FAILED,
    /** Su condición es falsa y se ha omitido. */
    SKIPPED,
    /** Es una {@code conditional} y su condición es verdadera: ha terminado bien. */
    SUCCEEDED
  }

  private final RunService runs;
  private final WorkflowRunRepository workflowRuns;
  private final StageRunRepository stageRuns;
  private final AgentRunRepository agentRuns;
  private final RunTransitions transitions;
  private final AgentQueue agentQueue;
  private final Workspaces workspaces;
  private final WorkItemService workItems;
  private final ProjectService projects;
  private final JdbcClient jdbc;
  private final ObjectMapper json;
  private final VerificationProperties verification;
  private final Verifications verifications;
  private final StageVerifications stageVerifications;
  private final Approvals approvals;
  private final TimeSource time;

  RunSteps(
      RunService runs,
      WorkflowRunRepository workflowRuns,
      StageRunRepository stageRuns,
      AgentRunRepository agentRuns,
      RunTransitions transitions,
      AgentQueue agentQueue,
      Workspaces workspaces,
      WorkItemService workItems,
      ProjectService projects,
      JdbcClient jdbc,
      ObjectMapper json,
      VerificationProperties verification,
      Verifications verifications,
      StageVerifications stageVerifications,
      Approvals approvals,
      TimeSource time) {
    this.runs = runs;
    this.workflowRuns = workflowRuns;
    this.stageRuns = stageRuns;
    this.agentRuns = agentRuns;
    this.transitions = transitions;
    this.agentQueue = agentQueue;
    this.workspaces = workspaces;
    this.workItems = workItems;
    this.projects = projects;
    this.jdbc = jdbc;
    this.json = json;
    this.verification = verification;
    this.verifications = verifications;
    this.stageVerifications = stageVerifications;
    this.approvals = approvals;
    this.time = time;
  }

  /**
   * Bloquea la ejecución hasta el final de la transacción y devuelve su estado, o vacío si ya no
   * existe (se eliminó). Con {@code skipIfBusy}, si otra transacción la tiene bloqueada no espera y
   * devuelve vacío (ver {@link #exists}).
   */
  public Optional<RunState> lock(UUID runId, boolean skipIfBusy) {
    boolean locked =
        jdbc.sql(
                "SELECT id FROM workflow_run WHERE id = ? FOR UPDATE"
                    + (skipIfBusy ? " SKIP LOCKED" : ""))
            .param(runId)
            .query(UUID.class)
            .optional()
            .isPresent();
    if (!locked) {
      return Optional.empty();
    }
    WorkflowRun run = workflowRuns.findById(runId).orElseThrow();
    Map<String, StageState> stages = new LinkedHashMap<>();
    for (StageRun stage : latestStages(runId).values()) {
      List<AgentRun> agents =
          agentRuns.findByStageRunIdInOrderByCreatedAtAsc(List.of(stage.getId()));
      List<AgentRun> finished = agents.stream().filter(a -> a.getStatus().isTerminal()).toList();
      StageStatus ending =
          stage.getStatus().isTerminal()
              ? null
              : switch (RunTransitions.outcome(finished)) {
                // Un hijo que terminó bien pero no pasó su verificación también hace fallar la
                // fase.
                case COMPLETED ->
                    verifications.stageFailed(stage.getId()) ? StageStatus.FAILED : null;
                case CANCELLED -> StageStatus.CANCELLED;
                default -> StageStatus.FAILED;
              };
      stages.put(
          stage.getStageKey(),
          new StageState(stage.getStageKey(), stage.getStatus(), !agents.isEmpty(), ending));
    }
    return Optional.of(new RunState(runId, run.getDefinitionId(), run.getStatus(), stages));
  }

  /** Si la ejecución existe, sin bloquearla. */
  public boolean exists(UUID runId) {
    return jdbc.sql("SELECT count(*) FROM workflow_run WHERE id = ?")
            .param(runId)
            .query(Integer.class)
            .single()
        > 0;
  }

  /** La fase puede empezar: sus dependencias han terminado bien. */
  public void ready(UUID runId, String stageKey) {
    transitions.readyStage(stage(runId, stageKey), time.now());
  }

  /** La fase no se ejecutará. */
  public void skip(UUID runId, String stageKey, String reason) {
    transitions.skipStage(stage(runId, stageKey), reason, time.now());
  }

  /**
   * Cancela la fase (ver {@link RunService#cancelStage}). Devuelve si ha cambiado de estado: con el
   * agente en un runner no cambia hasta que este termine.
   */
  public boolean cancel(UUID runId, String stageKey, String reason) {
    return runs.cancelStage(stage(runId, stageKey), reason, time.now());
  }

  /** Cierra la ejecución, cuando todas sus fases han terminado. */
  public void finish(UUID runId, WorkflowRunStatus status, String reason) {
    transitions.advanceRun(workflowRuns.findById(runId).orElseThrow(), status, reason, time.now());
  }

  /**
   * Arranca una fase lista: renderiza su prompt, calcula lo que se le permite a su agente y lo pone
   * en cola, en el worktree de la fase de la que depende si lo continúa. Una fase {@code command}
   * se pone en cola igual, como una invocación que el runner ejecuta con {@code sh -c}, con el
   * entorno del repositorio y el tiempo máximo de las verificaciones. Una fase {@code parallel}
   * pone en cola un agente por hijo, cada uno en un worktree nuevo: una copia del heredado si lo
   * hay. Una fase {@code human-approval} pasa a esperar con su petición de aprobación. Si no se
   * puede (el repositorio o el trabajo se archivaron, el worktree se eliminó, no hay prompt), la
   * fase falla con el motivo.
   */
  public StartOutcome start(UUID runId, String stageKey, PublishedDefinition published) {
    Instant now = time.now();
    WorkflowRun run = workflowRuns.findById(runId).orElseThrow();
    StageRun stage = stage(runId, stageKey);
    WorkflowDefinition definition = published.definition();
    StageDefinition stageDefinition =
        definition
            .stage(stageKey)
            .orElseThrow(() -> new IllegalStateException("La fase " + stageKey + " no existe"));
    try {
      if (!stageDefinition.type().executable()) {
        throw new ConflictException(
            "Las fases de tipo `" + stageDefinition.type().yaml() + "` todavía no se ejecutan");
      }
      if (stageDefinition.condition() != null) {
        boolean holds = condition(run, stage, stageDefinition, definition, now);
        if (!holds) {
          return StartOutcome.SKIPPED;
        }
        if (stageDefinition.type() == StageType.CONDITIONAL) {
          StageRun started =
              transitions.advanceStage(stage, StageStatus.STARTING, "condition-true", now);
          transitions.advanceStage(started, StageStatus.SUCCEEDED, "condition-true", now);
          return StartOutcome.SUCCEEDED;
        }
      }
      WorkItem workItem = workItems.get(run.getWorkItemId());
      CodeRepository repository = projects.getRepository(repositoryOf(run));
      workItems.requireActive(workItem);
      projects.requireActive(repository);
      if (stageDefinition.type() == StageType.HUMAN_APPROVAL) {
        String action =
            new PromptRenderer(
                    workItem,
                    projects.get(workItem.getProjectId()),
                    inputsOf(run),
                    new ConditionData(run, definition)::value)
                .render(stageDefinition.approval().action());
        approvals.request(run, stage, published, action, workItem, repository, now);
        return StartOutcome.STARTED;
      }
      WorkspaceView source = sourceWorkspace(runId, stageDefinition, definition);
      if (source != null) {
        workspaces.lock(source.id());
        source = workspaces.find(source.id()).orElseThrow();
        RunService.requireUsable(source);
        if (workspaces.hasLiveInvocation(source.id())) {
          return StartOutcome.DEFERRED;
        }
      }
      if (stageDefinition.type() == StageType.VERIFICATION) {
        if (source == null) {
          throw new ConflictException(
              "La fase " + stageKey + " no tiene un worktree que comprobar");
        }
        stageVerifications.startNode(
            runId, repository.getId(), stage, stageDefinition, source, now);
        return StartOutcome.STARTED;
      }
      Map<String, Invocation> invocations = new LinkedHashMap<>();
      switch (stageDefinition.type()) {
        case COMMAND -> {
          Invocation command = commandInvocation(stageDefinition, repository);
          invocations.put(
              null,
              source == null
                  ? command
                  : Invocation.inWorkspace(command.prompt(), command.policy(), source)
                      .asCommand(command.command()));
        }
        case PARALLEL -> {
          for (ChildDefinition child : stageDefinition.children()) {
            Invocation invocation =
                agentInvocation(
                    run,
                    "El hijo " + child.id() + " de la fase " + stageKey,
                    child.agent(),
                    child.prompt(),
                    null,
                    definition,
                    workItem,
                    repository);
            invocations.put(
                child.id(),
                source == null
                    ? invocation
                    : Invocation.inCopyOf(invocation.prompt(), invocation.policy(), source));
          }
        }
        default -> {
          Invocation invocation =
              agentInvocation(
                  run,
                  "La fase " + stageKey,
                  stageDefinition.agent(),
                  stageDefinition.prompt(),
                  stageDefinition.outputSchemaJson(),
                  definition,
                  workItem,
                  repository);
          invocations.put(
              null,
              source == null
                  ? invocation
                  : Invocation.inWorkspace(invocation.prompt(), invocation.policy(), source));
        }
      }
      invocations.forEach(
          (child, invocation) ->
              agentQueue.queue(run, stage, workItem, repository, invocation, child, now));
      return StartOutcome.STARTED;
    } catch (ConflictException e) {
      fail(stage, e.getMessage(), now);
      return StartOutcome.FAILED;
    }
  }

  /**
   * Evalúa la condición de la fase con los datos de la ejecución y lo deja en {@code
   * stage.condition.evaluated} con cada valor leído. Si es falsa, omite la fase ({@code
   * condition-false}). Si no se puede evaluar, lanza {@link ConflictException} y la fase falla.
   */
  private boolean condition(
      WorkflowRun run,
      StageRun stage,
      StageDefinition stageDefinition,
      WorkflowDefinition definition,
      Instant now) {
    Map<String, Object> values = new LinkedHashMap<>();
    boolean holds;
    try {
      Condition.Expr expr = Condition.parse(stageDefinition.condition());
      ConditionData data = new ConditionData(run, definition);
      for (Condition.Path path : Condition.paths(expr)) {
        values.put(path.text(), data.value(path.parts()));
      }
      holds = Condition.test(expr, values::get);
    } catch (Condition.SyntaxError | Condition.EvaluationError e) {
      throw new ConflictException(
          "No se pudo evaluar la condición `"
              + stageDefinition.condition()
              + "`: "
              + e.getMessage());
    }
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("condition", stageDefinition.condition());
    details.put("values", values);
    Map<String, Object> evaluated = new LinkedHashMap<>(details);
    evaluated.put("stageKey", stage.getStageKey());
    evaluated.put("result", holds);
    transitions.append(
        "stage_run", stage.getId(), "stage.condition.evaluated", run.getId(), evaluated, now);
    if (!holds) {
      transitions.skipStage(stage, "condition-false", details, now);
    }
    return holds;
  }

  /**
   * Los datos que lee una condición: {@code status} de cada fase, {@code exitCode} de un comando,
   * los campos de la salida estructurada de un agente o de los hijos de una {@code parallel} y los
   * datos de entrada. Lo que falta vale {@code null}.
   */
  private final class ConditionData {
    private final WorkflowRun run;
    private final WorkflowDefinition definition;
    private final Map<String, StageRun> stages;

    ConditionData(WorkflowRun run, WorkflowDefinition definition) {
      this.run = run;
      this.definition = definition;
      this.stages = latestStages(run.getId());
    }

    Object value(List<String> parts) {
      if (parts.getFirst().equals("inputs")) {
        return inputsOf(run).get(parts.get(1));
      }
      StageRun stage = stages.get(parts.getFirst());
      StageDefinition stageDefinition = definition.stage(parts.getFirst()).orElse(null);
      if (stage == null || stageDefinition == null || parts.size() < 2) {
        return null;
      }
      String field = parts.get(1);
      if (parts.size() == 2 && field.equals("status")) {
        return stage.getStatus().name();
      }
      List<AgentRun> agents =
          agentRuns.findByStageRunIdInOrderByCreatedAtAsc(List.of(stage.getId()));
      if (parts.size() == 2 && field.equals("exitCode")) {
        return agents.isEmpty() ? null : agents.getLast().getExitCode();
      }
      if (stageDefinition.type() == StageType.PARALLEL) {
        if (parts.size() == 3) {
          return agents.stream()
              .filter(a -> field.equals(a.getChildKey()))
              .reduce((first, second) -> second)
              .map(a -> output(a, parts.get(2)))
              .orElse(null);
        }
        // Un booleano de los hijos: verdadero si alguno lo devuelve verdadero.
        Object any = null;
        for (AgentRun agent : agents) {
          Object value = output(agent, field);
          if (Boolean.TRUE.equals(value)) {
            return true;
          }
          if (value != null) {
            any = value;
          }
        }
        return any;
      }
      return agents.isEmpty() ? null : output(agents.getLast(), field);
    }

    private Object output(AgentRun agent, String field) {
      if (agent.getStructuredOutput() == null) {
        return null;
      }
      JsonNode value = json.readTree(agent.getStructuredOutput()).get(field);
      return value == null || value.isNull() ? null : json.treeToValue(value, Object.class);
    }
  }

  /**
   * Un agente con su prompt (el propio o, si no tiene, el de {@code agentName}), con los datos de
   * otras fases que use, y lo que se le permite; {@code who} lo nombra en el error si se queda sin
   * prompt. {@code outputSchema} es el de la fase, que manda sobre el {@code output} del agente.
   */
  private Invocation agentInvocation(
      WorkflowRun run,
      String who,
      String agentName,
      String promptTemplate,
      String outputSchema,
      WorkflowDefinition definition,
      WorkItem workItem,
      CodeRepository repository) {
    AgentDefinition agent = agentName == null ? null : definition.agent(agentName).orElseThrow();
    String template =
        promptTemplate != null ? promptTemplate : agent == null ? null : agent.prompt();
    String prompt =
        template == null
            ? ""
            : new PromptRenderer(
                    workItem,
                    projects.get(workItem.getProjectId()),
                    inputsOf(run),
                    new ConditionData(run, definition)::value)
                .render(template);
    if (prompt.isBlank()) {
      throw new ConflictException(who + " se queda sin prompt");
    }
    EffectivePolicy policy =
        EffectivePolicy.of(projects.agentPolicy(repository), agent, launchLimitsOf(run))
            .withOutputSchema(outputSchema);
    if (agent == null) {
      policy = policy.withAgent(LaunchAgent.of(jdbc, run.getId()));
    }
    return Invocation.fresh(AgentRunKind.START, null, prompt, policy);
  }

  /**
   * El comando de una fase {@code command}: el entorno que el repositorio permite a sus agentes y,
   * como tiempo máximo, el de las verificaciones.
   */
  private Invocation commandInvocation(StageDefinition stage, CodeRepository repository) {
    EffectivePolicy policy =
        EffectivePolicy.of(
            projects.agentPolicy(repository), new AgentLimits(null, null, verification.timeout()));
    return Invocation.fresh(AgentRunKind.START, null, stage.command(), policy)
        .asCommand(stage.command());
  }

  /** La fase falla sin llegar a tener agente: queda el motivo en su evento. */
  private void fail(StageRun stage, String message, Instant now) {
    stage = transitions.advanceStage(stage, StageStatus.STARTING, "start-failed", now);
    transitions.advanceStage(stage, StageStatus.FAILED, "start-failed", now);
    transitions.append(
        "stage_run",
        stage.getId(),
        "stage.start.failed",
        stage.getWorkflowRunId(),
        Map.of("stageKey", stage.getStageKey(), "error", message),
        now);
  }

  /**
   * De dónde sale el worktree de la fase {@code stage}, o {@code null} para uno nuevo: de la fase
   * (o de {@code <fase>.<hijo>}) que devuelve {@link #worktreeSource}.
   */
  private WorkspaceView sourceWorkspace(
      UUID runId, StageDefinition stage, WorkflowDefinition definition) {
    String source = worktreeSource(stage, definition, latestStages(runId));
    return source == null ? null : worktreeOf(runId, source, definition);
  }

  /**
   * Fase cuyo worktree continúa {@code stage}, o {@code null} para uno nuevo: la de {@code
   * workspaceFrom} (que puede ser un hijo, {@code <fase>.<hijo>}) o, si no, su única dependencia
   * con worktree. Una dependencia opcional que se omitió no deja worktree.
   */
  static String worktreeSource(
      StageDefinition stage, WorkflowDefinition definition, Map<String, StageRun> stages) {
    if (stage.workspace() == WorkspaceMode.ISOLATED) {
      return null;
    }
    String source = stage.workspaceFrom();
    if (source == null) {
      List<String> agentDependencies =
          stage.dependsOn().stream()
              .map(Dependency::stage)
              .filter(d -> definition.stage(d).map(s -> s.type().hasWorkspace()).orElse(false))
              .toList();
      source = agentDependencies.size() == 1 ? agentDependencies.getFirst() : null;
    }
    StageRun run = source == null ? null : stages.get(stageOf(source));
    return run != null && run.getStatus() == StageStatus.SUCCEEDED ? source : null;
  }

  /** La fase de {@code source}: {@code review} en {@code review.security}. */
  private static String stageOf(String source) {
    int dot = source.indexOf('.');
    return dot < 0 ? source : source.substring(0, dot);
  }

  /**
   * Worktree que deja {@code source}: el del último agente de una fase, el de un hijo ({@code
   * <fase>.<hijo>}) o, en una fase {@code parallel} o {@code human-approval}, el que heredó (los
   * hijos trabajan en copias; una puerta no lo toca); {@code null} si no heredó ninguno.
   */
  private WorkspaceView worktreeOf(UUID runId, String source, WorkflowDefinition definition) {
    String stageKey = stageOf(source);
    String child = stageKey.equals(source) ? null : source.substring(stageKey.length() + 1);
    StageDefinition stageDefinition = definition.stage(stageKey).orElseThrow();
    if (child == null
        && (stageDefinition.type() == StageType.PARALLEL
            || stageDefinition.type() == StageType.HUMAN_APPROVAL)) {
      String inherited = worktreeSource(stageDefinition, definition, latestStages(runId));
      return inherited == null ? null : worktreeOf(runId, inherited, definition);
    }
    StageRun stage = stage(runId, stageKey);
    return agentRuns.findByStageRunIdInOrderByCreatedAtAsc(List.of(stage.getId())).stream()
        .filter(a -> child == null || child.equals(a.getChildKey()))
        .map(AgentRun::getWorkspaceId)
        .filter(java.util.Objects::nonNull)
        .reduce((first, second) -> second)
        .flatMap(workspaces::find)
        .orElseThrow(
            () ->
                new ConflictException("La fase " + source + " no dejó un worktree que continuar"));
  }

  /** Último intento de cada fase, en el orden en que se crearon. */
  private Map<String, StageRun> latestStages(UUID runId) {
    Map<String, StageRun> latest = new LinkedHashMap<>();
    stageRuns.findByWorkflowRunIdInOrderByCreatedAtAsc(List.of(runId)).stream()
        .sorted(Comparator.comparing(StageRun::getAttempt))
        .forEach(s -> latest.put(s.getStageKey(), s));
    return latest;
  }

  private StageRun stage(UUID runId, String stageKey) {
    StageRun stage = latestStages(runId).get(stageKey);
    if (stage == null) {
      throw new IllegalStateException("La ejecución " + runId + " no tiene la fase " + stageKey);
    }
    return stage;
  }

  private UUID repositoryOf(WorkflowRun run) {
    return jdbc.sql("SELECT repository_id FROM workflow_run WHERE id = ?")
        .param(run.getId())
        .query(UUID.class)
        .single();
  }

  private Map<String, Object> inputsOf(WorkflowRun run) {
    String raw =
        jdbc.sql("SELECT CAST(inputs AS text) FROM workflow_run WHERE id = ?")
            .param(run.getId())
            .query(String.class)
            .single();
    return json.readValue(raw, new TypeReference<LinkedHashMap<String, Object>>() {});
  }

  private AgentLimits launchLimitsOf(WorkflowRun run) {
    JsonNode limits =
        json.readTree(
            jdbc.sql("SELECT CAST(launch_limits AS text) FROM workflow_run WHERE id = ?")
                .param(run.getId())
                .query(String.class)
                .single());
    JsonNode turns = limits.path("maxTurns");
    JsonNode budget = limits.path("maxBudgetUsd");
    JsonNode timeout = limits.path("timeoutMinutes");
    return new AgentLimits(
        turns.isNumber() ? turns.asInt() : null,
        budget.isNumber() ? budget.decimalValue() : null,
        timeout.isNumber() ? Duration.ofMinutes(timeout.asLong()) : null);
  }
}
