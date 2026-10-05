package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.project.CodeRepository;
import dev.skynet.controlplane.project.ProjectService;
import dev.skynet.controlplane.shared.ConflictException;
import dev.skynet.controlplane.shared.NotFoundException;
import dev.skynet.controlplane.shared.TimeSource;
import dev.skynet.controlplane.workitem.WorkItem;
import dev.skynet.controlplane.workitem.WorkItemService;
import dev.skynet.protocol.AgentObservableStatus;
import dev.skynet.protocol.StageStatus;
import dev.skynet.protocol.WorkflowRunStatus;
import dev.skynet.protocol.runner.AgentLimits;
import dev.skynet.protocol.runner.StartAgent;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Crea y gobierna ejecuciones. Todas las transiciones se validan contra las máquinas de estados y
 * cada una registra su evento en la misma transacción.
 */
@Service
public class RunService {

  static final String ADHOC_STAGE = "agent";

  private final WorkflowRunRepository workflowRuns;
  private final StageRunRepository stageRuns;
  private final AgentRunRepository agentRuns;
  private final PromptRepository prompts;
  private final WorkItemService workItems;
  private final ProjectService projects;
  private final TimeSource time;
  private final JdbcClient jdbc;
  private final RunTransitions transitions;
  private final AgentDefaults defaults;
  private final ApplicationEventPublisher publisher;

  RunService(
      WorkflowRunRepository workflowRuns,
      StageRunRepository stageRuns,
      AgentRunRepository agentRuns,
      PromptRepository prompts,
      WorkItemService workItems,
      ProjectService projects,
      TimeSource time,
      JdbcClient jdbc,
      RunTransitions transitions,
      AgentDefaults defaults,
      ApplicationEventPublisher publisher) {
    this.workflowRuns = workflowRuns;
    this.stageRuns = stageRuns;
    this.agentRuns = agentRuns;
    this.prompts = prompts;
    this.workItems = workItems;
    this.projects = projects;
    this.time = time;
    this.jdbc = jdbc;
    this.transitions = transitions;
    this.defaults = defaults;
    this.publisher = publisher;
  }

  /**
   * Lanza una ejecución {@code adhoc}: un workflow con una fase y un agente en cola. El agente
   * queda en {@code QUEUED} hasta que un runner reclame su orden de arranque.
   */
  @Transactional
  public WorkflowRun launch(UUID workItemId, UUID repositoryId, String promptText) {
    WorkItem workItem = workItems.get(workItemId);
    CodeRepository repository = projects.getRepository(repositoryId);
    if (!repository.getProjectId().equals(workItem.getProjectId())) {
      throw new ConflictException(
          "El repositorio " + repository.getName() + " no pertenece al proyecto del trabajo");
    }
    Instant now = time.now();

    WorkflowRun run = WorkflowRun.create(workItemId, adhocDefinitionId(), now);
    run.transitionTo(WorkflowRunStatus.RUNNING, now);
    run = workflowRuns.save(run);
    append(
        "workflow_run",
        run.getId(),
        "workflow.started",
        run.getId(),
        Map.of("workItemId", workItemId, "definition", "adhoc", "status", run.getStatus()),
        now);

    StageRun stage = StageRun.create(run.getId(), ADHOC_STAGE, now);
    stage.transitionTo(StageStatus.READY, now);
    stage = stageRuns.save(stage);
    append(
        "stage_run",
        stage.getId(),
        "stage.ready",
        run.getId(),
        Map.of("stageKey", stage.getStageKey(), "attempt", stage.getAttempt()),
        now);

    UUID sessionId = UUID.randomUUID();
    AgentRun agent =
        agentRuns.save(
            AgentRun.queued(
                stage.getId(), repositoryId, AgentRun.PROVIDER_CLAUDE_CODE, sessionId, now));
    Prompt prompt = prompts.save(Prompt.of(agent.getId(), Prompt.ROLE_USER, promptText, now));
    append(
        "agent_run",
        agent.getId(),
        "agent.spawned",
        run.getId(),
        Map.of(
            "stageRunId", stage.getId(),
            "repositoryId", repositoryId,
            "provider", agent.getProvider(),
            "kind", agent.getKind(),
            "status", agent.getStatus(),
            "promptId", prompt.getId(),
            "promptSha256", prompt.getSha256()),
        now);
    publisher.publishEvent(
        new AgentRunQueued(
            agent.getId(),
            new StartAgent(
                run.getId(),
                workItem.getKey(),
                repository.getLocalPath(),
                repository.getDefaultBranch(),
                sessionId,
                promptText,
                defaults.allowedTools(),
                defaults.permissionMode(),
                defaults.model(),
                new AgentLimits(
                    defaults.maxTurns(), defaults.maxBudgetUsd(), defaults.timeout()))));
    return run;
  }

  /**
   * Cancela un agente. Si sigue en cola se cancela en el acto, junto con su fase y su ejecución, y
   * su orden de arranque se retira. Si ya está en un runner se le ordena terminar el proceso; el
   * estado {@code CANCELLED} llega con el fin del proceso. Pedirlo dos veces no tiene efecto.
   */
  @Transactional
  public AgentRun cancelAgent(UUID agentRunId) {
    AgentRun agent = agentRuns.findById(agentRunId).orElseThrow(() -> notFound(agentRunId));
    StageRun stage = stageRuns.findById(agent.getStageRunId()).orElseThrow();
    WorkflowRun run = workflowRuns.findById(stage.getWorkflowRunId()).orElseThrow();
    Instant now = time.now();

    if (agent.getStatus() == AgentObservableStatus.QUEUED) {
      agent =
          transitions.agent(
              agent, AgentObservableStatus.CANCELLED, run.getId(), "cancelled-by-user", now);
      transitions.finishAdhoc(stage, run, AgentObservableStatus.CANCELLED, now);
      publisher.publishEvent(new AgentRunWithdrawn(agent.getId()));
      return agent;
    }
    if (agent.getStatus().isTerminal()) {
      throw new ConflictException(
          "El agente " + agentRunId + " ya ha terminado (" + agent.getStatus() + ")");
    }
    if (agent.requestCancel(now)) {
      agent = agentRuns.save(agent);
      append(
          "agent_run",
          agent.getId(),
          "agent.cancel.requested",
          run.getId(),
          Map.of("runnerId", agent.getRunnerId()),
          now);
      publisher.publishEvent(new AgentCancelRequested(agent.getId(), agent.getRunnerId()));
    }
    return agent;
  }

  /**
   * Un runner ha reclamado la orden de arranque: el agente y su fase pasan a {@code STARTING}.
   * Devuelve {@code false} si el agente ya no está en cola, y entonces la orden no debe entregarse.
   */
  @Transactional
  public boolean assignRunner(UUID agentRunId, UUID runnerId) {
    AgentRun agent = agentRuns.findById(agentRunId).orElseThrow(() -> notFound(agentRunId));
    if (agent.getStatus() != AgentObservableStatus.QUEUED) {
      return false;
    }
    StageRun stage = stageRuns.findById(agent.getStageRunId()).orElseThrow();
    Instant now = time.now();
    agent.assignRunner(runnerId);
    transitions.agent(
        agent, AgentObservableStatus.STARTING, stage.getWorkflowRunId(), "assigned", now);
    transitions.advanceStage(stage, StageStatus.STARTING, "agent-assigned", now);
    return true;
  }

  /**
   * Marca el agente como {@code UNRESPONSIVE} si sigue activo y sin actividad desde {@code before}.
   * Devuelve si lo ha marcado. La siguiente actividad lo devuelve a un estado activo.
   */
  @Transactional
  boolean markUnresponsive(UUID agentRunId, Instant before) {
    AgentRun agent = agentRuns.findById(agentRunId).orElseThrow(() -> notFound(agentRunId));
    boolean silent =
        (agent.getStatus() == AgentObservableStatus.THINKING
                || agent.getStatus() == AgentObservableStatus.EXECUTING)
            && agent.getLastActivityAt() != null
            && agent.getLastActivityAt().isBefore(before);
    if (!silent) {
      return false;
    }
    StageRun stage = stageRuns.findById(agent.getStageRunId()).orElseThrow();
    transitions.agent(
        agent,
        AgentObservableStatus.UNRESPONSIVE,
        stage.getWorkflowRunId(),
        "no-activity",
        time.now());
    return true;
  }

  @Transactional(readOnly = true)
  public List<RunView> runsOf(UUID workItemId) {
    workItems.get(workItemId);
    return views(workflowRuns.findByWorkItemIdOrderByCreatedAtDesc(workItemId));
  }

  @Transactional(readOnly = true)
  public RunView run(UUID workflowRunId) {
    WorkflowRun run =
        workflowRuns
            .findById(workflowRunId)
            .orElseThrow(() -> new NotFoundException("Ejecución", workflowRunId));
    return views(List.of(run)).getFirst();
  }

  @Transactional(readOnly = true)
  public AgentRunDetail agent(UUID agentRunId) {
    AgentRun agent = agentRuns.findById(agentRunId).orElseThrow(() -> notFound(agentRunId));
    StageRun stage = stageRuns.findById(agent.getStageRunId()).orElseThrow();
    return new AgentRunDetail(
        AgentRunView.of(agent),
        stage.getWorkflowRunId(),
        prompts.findByAgentRunIdOrderByCreatedAtAsc(agentRunId).stream()
            .map(PromptView::of)
            .toList());
  }

  /**
   * Ejecuciones de más reciente a más antigua, filtradas por estado y proyecto (vacío o {@code
   * null}: sin filtro).
   */
  @Transactional(readOnly = true)
  public RunPage list(Set<WorkflowRunStatus> statuses, UUID projectId, int page, int size) {
    String where =
        " FROM workflow_run r JOIN work_item w ON w.id = r.work_item_id"
            + " WHERE (CAST(:project AS uuid) IS NULL OR w.project_id = :project)"
            + (statuses == null || statuses.isEmpty() ? "" : " AND r.status IN (:statuses)");
    Map<String, Object> params = new HashMap<>();
    params.put("project", projectId);
    if (statuses != null && !statuses.isEmpty()) {
      params.put("statuses", statuses.stream().map(Enum::name).toList());
    }
    long total = jdbc.sql("SELECT count(*)" + where).params(params).query(Long.class).single();
    List<UUID> ids =
        jdbc.sql(
                "SELECT r.id"
                    + where
                    + " ORDER BY r.created_at DESC, r.id LIMIT :limit OFFSET :offset")
            .params(params)
            .param("limit", size)
            .param("offset", (long) page * size)
            .query(UUID.class)
            .list();
    Map<UUID, WorkflowRun> byId = new HashMap<>();
    workflowRuns.findAllById(ids).forEach(r -> byId.put(r.getId(), r));
    return new RunPage(views(ids.stream().map(byId::get).toList()), page, size, total);
  }

  /** Definiciones de workflow, de la más reciente a la más antigua (en la Fase 1, solo adhoc). */
  @Transactional(readOnly = true)
  public List<WorkflowDefinitionView> definitions() {
    return jdbc.sql(
            "SELECT id, key, version, source_yaml, created_at FROM workflow_definition"
                + " ORDER BY key, version DESC")
        .query(
            (rs, row) ->
                new WorkflowDefinitionView(
                    rs.getObject("id", UUID.class),
                    rs.getString("key"),
                    rs.getInt("version"),
                    rs.getString("source_yaml"),
                    rs.getTimestamp("created_at").toInstant()))
        .list();
  }

  /** Agentes activos sin actividad desde {@code before}, candidatos a {@code UNRESPONSIVE}. */
  @Transactional(readOnly = true)
  List<UUID> silentAgents(Instant before) {
    return jdbc.sql(
            "SELECT id FROM agent_run WHERE status IN ('THINKING', 'EXECUTING')"
                + " AND last_activity_at < ? ORDER BY last_activity_at")
        .param(Timestamp.from(before))
        .query(UUID.class)
        .list();
  }

  /** Agentes en {@code UNRESPONSIVE}, con su ejecución y su trabajo. */
  @Transactional(readOnly = true)
  public List<UnresponsiveAgent> unresponsiveAgents() {
    return jdbc.sql(
            "SELECT a.id, s.workflow_run_id, w.key, a.last_activity_at FROM agent_run a"
                + " JOIN stage_run s ON s.id = a.stage_run_id"
                + " JOIN workflow_run r ON r.id = s.workflow_run_id"
                + " JOIN work_item w ON w.id = r.work_item_id"
                + " WHERE a.status = 'UNRESPONSIVE' ORDER BY a.last_activity_at")
        .query(
            (rs, row) ->
                new UnresponsiveAgent(
                    rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class),
                    rs.getString(3),
                    rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant()))
        .list();
  }

  private List<RunView> views(List<WorkflowRun> runs) {
    if (runs.isEmpty()) {
      return List.of();
    }
    List<StageRun> stages =
        stageRuns.findByWorkflowRunIdInOrderByCreatedAtAsc(
            runs.stream().map(WorkflowRun::getId).toList());
    List<AgentRun> agents =
        stages.isEmpty()
            ? List.of()
            : agentRuns.findByStageRunIdInOrderByCreatedAtAsc(
                stages.stream().map(StageRun::getId).toList());
    Map<UUID, WorkItem> items = new HashMap<>();
    workItems
        .getAll(runs.stream().map(WorkflowRun::getWorkItemId).distinct().toList())
        .forEach(w -> items.put(w.getId(), w));
    return runs.stream()
        .map(
            run ->
                RunView.of(
                    run,
                    items.get(run.getWorkItemId()),
                    stages.stream()
                        .filter(s -> s.getWorkflowRunId().equals(run.getId()))
                        .map(
                            s ->
                                StageRunView.of(
                                    s,
                                    agents.stream()
                                        .filter(a -> a.getStageRunId().equals(s.getId()))
                                        .map(AgentRunView::of)
                                        .toList()))
                        .toList()))
        .toList();
  }

  private UUID adhocDefinitionId() {
    return jdbc.sql("SELECT id FROM workflow_definition WHERE key = 'adhoc' AND version = 1")
        .query(UUID.class)
        .single();
  }

  private void append(
      String aggregateType,
      UUID aggregateId,
      String type,
      UUID workflowRunId,
      Map<String, ?> payload,
      Instant now) {
    transitions.append(aggregateType, aggregateId, type, workflowRunId, payload, now);
  }

  private static NotFoundException notFound(UUID agentRunId) {
    return new NotFoundException("Ejecución de agente", agentRunId);
  }
}
