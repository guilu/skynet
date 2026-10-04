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
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
    transitions.append(aggregateType, aggregateId, type, workflowRunId, payload, now);
  }

  private static NotFoundException notFound(UUID agentRunId) {
    return new NotFoundException("Ejecución de agente", agentRunId);
  }
}
