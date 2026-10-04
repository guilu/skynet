package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.event.EventDraft;
import dev.skynet.controlplane.event.EventStore;
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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
  private final EventStore events;
  private final TimeSource time;
  private final JdbcClient jdbc;

  RunService(
      WorkflowRunRepository workflowRuns,
      StageRunRepository stageRuns,
      AgentRunRepository agentRuns,
      PromptRepository prompts,
      WorkItemService workItems,
      ProjectService projects,
      EventStore events,
      TimeSource time,
      JdbcClient jdbc) {
    this.workflowRuns = workflowRuns;
    this.stageRuns = stageRuns;
    this.agentRuns = agentRuns;
    this.prompts = prompts;
    this.workItems = workItems;
    this.projects = projects;
    this.events = events;
    this.time = time;
    this.jdbc = jdbc;
  }

  /**
   * Lanza una ejecución {@code adhoc}: un workflow con una fase y un agente en cola. El agente
   * queda en {@code QUEUED} hasta que un runner lo reclame (M2).
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

    AgentRun agent =
        agentRuns.save(
            AgentRun.queued(stage.getId(), repositoryId, AgentRun.PROVIDER_CLAUDE_CODE, now));
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
    return run;
  }

  /**
   * Cancela un agente y propaga la cancelación a su fase y a la ejecución. En M1 solo hay agentes
   * en cola; a partir de M2 la cancelación de un agente activo se delega en el runner.
   */
  @Transactional
  public AgentRun cancelAgent(UUID agentRunId) {
    AgentRun agent = agentRuns.findById(agentRunId).orElseThrow(() -> notFound(agentRunId));
    StageRun stage = stageRuns.findById(agent.getStageRunId()).orElseThrow();
    WorkflowRun run = workflowRuns.findById(stage.getWorkflowRunId()).orElseThrow();
    Instant now = time.now();

    AgentObservableStatus previousAgent = agent.transitionTo(AgentObservableStatus.CANCELLED, now);
    agent = agentRuns.save(agent);
    append(
        "agent_run",
        agent.getId(),
        "agent.status.changed",
        run.getId(),
        statusChange(previousAgent, agent.getStatus(), "cancelled-by-user"),
        now);

    if (stage.getStatus().canTransitionTo(StageStatus.CANCELLED)) {
      StageStatus previousStage = stage.transitionTo(StageStatus.CANCELLED, now);
      stageRuns.save(stage);
      append(
          "stage_run",
          stage.getId(),
          "stage.status.changed",
          run.getId(),
          statusChange(previousStage, stage.getStatus(), "agent-cancelled"),
          now);
    }
    if (run.getStatus().canTransitionTo(WorkflowRunStatus.CANCELLED)) {
      WorkflowRunStatus previousRun = run.transitionTo(WorkflowRunStatus.CANCELLED, now);
      workflowRuns.save(run);
      append(
          "workflow_run",
          run.getId(),
          "workflow.status.changed",
          run.getId(),
          statusChange(previousRun, run.getStatus(), "agent-cancelled"),
          now);
    }
    return agent;
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
    return runs.stream()
        .map(
            run ->
                RunView.of(
                    run,
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
    events.append(EventDraft.of(aggregateType, aggregateId, type, workflowRunId, payload, now));
  }

  private static Map<String, Object> statusChange(
      Enum<?> previous, Enum<?> current, String reason) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("previousStatus", previous);
    payload.put("status", current);
    payload.put("reason", reason);
    return payload;
  }

  private static NotFoundException notFound(UUID agentRunId) {
    return new NotFoundException("Ejecución de agente", agentRunId);
  }
}
