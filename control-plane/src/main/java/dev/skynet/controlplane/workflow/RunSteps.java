package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.definition.AgentDefinition;
import dev.skynet.controlplane.definition.PublishedDefinition;
import dev.skynet.controlplane.definition.StageDefinition;
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
   */
  public record StageState(String key, StageStatus status, boolean hasAgent) {}

  /** Cómo ha ido arrancar una fase. */
  public enum StartOutcome {
    /** Su agente está en cola. */
    STARTED,
    /** El worktree que continúa está ocupado (una verificación): se intenta más tarde. */
    DEFERRED,
    /** No se ha podido arrancar y la fase ha fallado. */
    FAILED
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
      boolean hasAgent =
          !agentRuns.findByStageRunIdInOrderByCreatedAtAsc(List.of(stage.getId())).isEmpty();
      stages.put(
          stage.getStageKey(), new StageState(stage.getStageKey(), stage.getStatus(), hasAgent));
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
   * en cola, en el worktree de la fase de la que depende si lo continúa. Si no se puede (el
   * repositorio o el trabajo se archivaron, el worktree se eliminó, no hay prompt), la fase falla
   * con el motivo.
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
      if (stageDefinition.type() != StageType.AGENT) {
        throw new ConflictException(
            "Las fases de tipo `" + stageDefinition.type().yaml() + "` todavía no se ejecutan");
      }
      WorkItem workItem = workItems.get(run.getWorkItemId());
      CodeRepository repository = projects.getRepository(repositoryOf(run));
      workItems.requireActive(workItem);
      projects.requireActive(repository);
      AgentDefinition agent =
          stageDefinition.agent() == null
              ? null
              : definition.agent(stageDefinition.agent()).orElseThrow();
      String template =
          stageDefinition.prompt() != null
              ? stageDefinition.prompt()
              : agent == null ? null : agent.prompt();
      String prompt =
          template == null
              ? ""
              : new PromptRenderer(workItem, projects.get(workItem.getProjectId()), inputsOf(run))
                  .render(template);
      if (prompt.isBlank()) {
        throw new ConflictException("La fase " + stageKey + " se queda sin prompt");
      }
      EffectivePolicy policy =
          EffectivePolicy.of(projects.agentPolicy(repository), agent, launchLimitsOf(run));
      Invocation invocation = Invocation.fresh(AgentRunKind.START, null, prompt, policy);
      String source = worktreeSource(stageDefinition, definition, latestStages(runId));
      if (source != null) {
        WorkspaceView workspace = worktreeOf(runId, source);
        workspaces.lock(workspace.id());
        workspace = workspaces.find(workspace.id()).orElseThrow();
        RunService.requireUsable(workspace);
        if (workspaces.hasLiveInvocation(workspace.id())) {
          return StartOutcome.DEFERRED;
        }
        invocation = Invocation.inWorkspace(prompt, policy, workspace);
      }
      agentQueue.queue(run, stage, workItem, repository, invocation, now);
      return StartOutcome.STARTED;
    } catch (ConflictException e) {
      fail(stage, e.getMessage(), now);
      return StartOutcome.FAILED;
    }
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
   * Fase cuyo worktree continúa {@code stage}, o {@code null} para uno nuevo: la de {@code
   * workspaceFrom} o, si no, su única dependencia con agente. Una dependencia opcional que se
   * omitió no deja worktree.
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
              .filter(d -> definition.stage(d).map(s -> s.type() == StageType.AGENT).orElse(false))
              .toList();
      source = agentDependencies.size() == 1 ? agentDependencies.getFirst() : null;
    }
    StageRun run = source == null ? null : stages.get(source);
    return run != null && run.getStatus() == StageStatus.SUCCEEDED ? source : null;
  }

  /** Worktree del último agente de la fase {@code stageKey}. */
  private WorkspaceView worktreeOf(UUID runId, String stageKey) {
    StageRun source = stage(runId, stageKey);
    return agentRuns.findByStageRunIdInOrderByCreatedAtAsc(List.of(source.getId())).stream()
        .map(AgentRun::getWorkspaceId)
        .filter(java.util.Objects::nonNull)
        .reduce((first, second) -> second)
        .flatMap(workspaces::find)
        .orElseThrow(
            () ->
                new ConflictException(
                    "La fase " + stageKey + " no dejó un worktree que continuar"));
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
