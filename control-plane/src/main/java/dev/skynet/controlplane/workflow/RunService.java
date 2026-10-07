package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.project.AgentDefaults;
import dev.skynet.controlplane.project.AgentPolicy;
import dev.skynet.controlplane.project.CodeRepository;
import dev.skynet.controlplane.project.ProjectService;
import dev.skynet.controlplane.shared.ConflictException;
import dev.skynet.controlplane.shared.InvalidRequestException;
import dev.skynet.controlplane.shared.NotFoundException;
import dev.skynet.controlplane.shared.TimeSource;
import dev.skynet.controlplane.workitem.WorkItem;
import dev.skynet.controlplane.workitem.WorkItemService;
import dev.skynet.protocol.AgentObservableStatus;
import dev.skynet.protocol.StageStatus;
import dev.skynet.protocol.WorkflowRunStatus;
import dev.skynet.protocol.runner.AgentLimits;
import dev.skynet.protocol.runner.ResumeFrom;
import dev.skynet.protocol.runner.StartAgent;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
  private final Workspaces workspaces;
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
      Workspaces workspaces,
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
    this.workspaces = workspaces;
    this.publisher = publisher;
  }

  /**
   * Lanza una ejecución {@code adhoc}: un workflow con una fase y un agente en cola. El agente
   * queda en {@code QUEUED} hasta que un runner reclame su orden de arranque. Los límites que
   * falten (o todos, con {@code null}) se toman de la política del repositorio, y ninguno puede
   * superar los suyos.
   */
  @Transactional
  public WorkflowRun launch(
      UUID workItemId, UUID repositoryId, String promptText, AgentLimits limits) {
    WorkItem workItem = workItems.get(workItemId);
    CodeRepository repository = projects.getRepository(repositoryId);
    if (!repository.getProjectId().equals(workItem.getProjectId())) {
      throw new ConflictException(
          "El repositorio " + repository.getName() + " no pertenece al proyecto del trabajo");
    }
    return spawn(
        workItem,
        repository,
        Invocation.fresh(
            AgentRunKind.START,
            null,
            promptText,
            withinPolicy(limits, projects.agentPolicy(repository))));
  }

  /**
   * Envía un mensaje al agente: reanuda su sesión ({@code --resume}) en el mismo worktree y el
   * mismo runner, como una invocación hija en una ejecución nueva; la terminada no se modifica. Si
   * la sesión ya continuó, el mensaje sigue a su última invocación.
   */
  @Transactional
  public WorkflowRun sendMessage(UUID agentRunId, String text) {
    return continueSession(agentRunId, text, false);
  }

  /**
   * Bifurca la sesión del agente ({@code --fork-session}) con un mensaje: una sesión nueva que
   * parte de la conversación, en un worktree nuevo creado desde el suyo, en el mismo runner.
   */
  @Transactional
  public WorkflowRun fork(UUID agentRunId, String text) {
    return continueSession(agentRunId, text, true);
  }

  /**
   * Reintenta un lanzamiento: el mismo prompt y los mismos límites, con sesión y worktree nuevos,
   * en cualquier runner. Para repetir un mensaje basta con enviarlo otra vez.
   */
  @Transactional
  public WorkflowRun retry(UUID agentRunId) {
    AgentRun parent = agentRuns.findById(agentRunId).orElseThrow(() -> notFound(agentRunId));
    requireFinished(parent);
    if (parent.getKind() != AgentRunKind.START && parent.getKind() != AgentRunKind.RETRY) {
      throw new ConflictException(
          "Solo se puede reintentar un lanzamiento; para repetir un mensaje, envíalo de nuevo");
    }
    String promptText =
        prompts.findByAgentRunIdOrderByCreatedAtAsc(agentRunId).stream()
            .filter(p -> Prompt.ROLE_USER.equals(p.getRole()))
            .findFirst()
            .orElseThrow(
                () -> new ConflictException("El agente " + agentRunId + " no tiene prompt"))
            .getContent();
    CodeRepository repository = projects.getRepository(parent.getRepositoryId());
    return spawn(
        workItemOf(parent),
        repository,
        Invocation.fresh(
            AgentRunKind.RETRY,
            parent,
            promptText,
            cappedByPolicy(parent.limits(), projects.agentPolicy(repository))));
  }

  private WorkflowRun continueSession(UUID agentRunId, String text, boolean fork) {
    AgentRun requested = agentRuns.findById(agentRunId).orElseThrow(() -> notFound(agentRunId));
    WorkspaceView workspace =
        workspaces
            .find(requested.getWorkspaceId())
            .orElseThrow(
                () ->
                    new ConflictException(
                        "La sesión del agente "
                            + agentRunId
                            + " no llegó a arrancar: no se puede continuar"));
    // Con el worktree bloqueado, la última invocación y el bloqueo de escritores no cambian hasta
    // el commit.
    workspaces.lock(workspace.id());
    AgentRun parent = lastOfSession(requested);
    requireUsable(workspaces.find(workspace.id()).orElseThrow());
    if (workspaces.hasLiveInvocation(workspace.id())) {
      throw new ConflictException(
          "Ya hay una invocación o una verificación en curso en el worktree "
              + workspace.path()
              + ": espera a que termine o cancélala");
    }
    if (!sessionStarted(parent)) {
      throw new ConflictException(
          "La sesión del agente " + parent.getId() + " no llegó a arrancar: no se puede continuar");
    }
    UUID sessionId = fork ? UUID.randomUUID() : UUID.fromString(parent.getProviderSessionId());
    CodeRepository repository = projects.getRepository(parent.getRepositoryId());
    return spawn(
        workItemOf(parent),
        repository,
        new Invocation(
            fork ? AgentRunKind.FORK : AgentRunKind.RESUME,
            parent,
            sessionId,
            text,
            cappedByPolicy(parent.limits(), projects.agentPolicy(repository)),
            fork ? null : workspace.id(),
            new ResumeFrom(
                parent.getProviderSessionId(), fork, workspace.path(), workspace.branch()),
            parent.getRunnerId()));
  }

  /**
   * Última invocación de la sesión a partir de {@code agent}: sigue sus reanudaciones mientras las
   * haya. Los forks y reintentos abren sesiones nuevas y no cuentan.
   */
  private AgentRun lastOfSession(AgentRun agent) {
    AgentRun current = agent;
    for (AgentRun next = lastStep(current); next != null; next = lastStep(current)) {
      current = next;
    }
    return current;
  }

  /** El proveedor confirmó la sesión: sin eso no hay nada que reanudar. */
  private boolean sessionStarted(AgentRun agent) {
    StageRun stage = stageRuns.findById(agent.getStageRunId()).orElseThrow();
    return jdbc.sql(
                "SELECT count(*) FROM event WHERE workflow_run_id = ? AND aggregate_id = ?"
                    + " AND event_type = 'agent.session.started'")
            .params(stage.getWorkflowRunId(), agent.getId())
            .query(Integer.class)
            .single()
        > 0;
  }

  /** Un worktree eliminado, o a punto de eliminarse, ya no admite escritores. */
  static void requireUsable(WorkspaceView workspace) {
    if (workspace.removedAt() != null) {
      throw new ConflictException(
          "El worktree "
              + workspace.path()
              + " se eliminó: ya no se puede reanudar, bifurcar ni verificar (su rama "
              + workspace.branch()
              + " sigue en el repositorio)");
    }
    if (workspace.cleanupRequestedAt() != null) {
      throw new ConflictException("El worktree " + workspace.path() + " se está eliminando");
    }
  }

  private static void requireFinished(AgentRun agent) {
    if (!agent.getStatus().isTerminal()) {
      throw new ConflictException(
          "El agente " + agent.getId() + " sigue en curso (" + agent.getStatus() + ")");
    }
  }

  private WorkItem workItemOf(AgentRun agent) {
    StageRun stage = stageRuns.findById(agent.getStageRunId()).orElseThrow();
    WorkflowRun run = workflowRuns.findById(stage.getWorkflowRunId()).orElseThrow();
    return workItems.get(run.getWorkItemId());
  }

  /**
   * Invocación por crear.
   *
   * @param parent invocación de la que parte, o {@code null} en un lanzamiento
   * @param workspaceId worktree que reutiliza (solo al reanudar)
   * @param resume sesión y worktree de partida (reanudar y bifurcar)
   * @param runnerId runner que debe ejecutarla, o {@code null} si vale cualquiera
   */
  private record Invocation(
      AgentRunKind kind,
      AgentRun parent,
      UUID sessionId,
      String prompt,
      AgentLimits limits,
      UUID workspaceId,
      ResumeFrom resume,
      UUID runnerId) {

    /** Invocación con sesión y worktree nuevos, en cualquier runner. */
    static Invocation fresh(AgentRunKind kind, AgentRun parent, String prompt, AgentLimits limits) {
      return new Invocation(kind, parent, UUID.randomUUID(), prompt, limits, null, null, null);
    }
  }

  /**
   * Crea la ejecución {@code adhoc} con su fase y su agente en cola, y publica la orden. Las
   * herramientas, el modo de permisos y el entorno son los de la política actual del repositorio,
   * también al continuar una sesión.
   */
  private WorkflowRun spawn(WorkItem workItem, CodeRepository repository, Invocation invocation) {
    Instant now = time.now();
    AgentRun parent = invocation.parent();
    AgentPolicy policy = projects.agentPolicy(repository);

    WorkflowRun run = WorkflowRun.create(workItem.getId(), adhocDefinitionId(), now);
    run.transitionTo(WorkflowRunStatus.RUNNING, now);
    run = workflowRuns.save(run);
    Map<String, Object> started = new LinkedHashMap<>();
    started.put("workItemId", workItem.getId());
    started.put("definition", "adhoc");
    started.put("status", run.getStatus());
    if (parent != null) {
      started.put("parentAgentRunId", parent.getId());
    }
    append("workflow_run", run.getId(), "workflow.started", run.getId(), started, now);

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
            parent == null
                ? AgentRun.queued(
                    stage.getId(),
                    repository.getId(),
                    AgentRun.PROVIDER_CLAUDE_CODE,
                    invocation.sessionId(),
                    invocation.limits(),
                    now)
                : AgentRun.queued(
                    stage.getId(),
                    parent,
                    invocation.kind(),
                    invocation.sessionId(),
                    invocation.workspaceId(),
                    invocation.limits(),
                    now));
    Prompt prompt =
        prompts.save(Prompt.of(agent.getId(), Prompt.ROLE_USER, invocation.prompt(), now));
    Map<String, Object> spawned = new LinkedHashMap<>();
    spawned.put("stageRunId", stage.getId());
    spawned.put("repositoryId", repository.getId());
    spawned.put("provider", agent.getProvider());
    spawned.put("kind", agent.getKind());
    spawned.put("status", agent.getStatus());
    spawned.put("promptId", prompt.getId());
    spawned.put("promptSha256", prompt.getSha256());
    spawned.put("allowedTools", policy.allowedTools());
    spawned.put("permissionMode", policy.permissionMode());
    Map<String, Object> limits = new LinkedHashMap<>();
    limits.put("maxTurns", invocation.limits().maxTurns());
    limits.put("maxBudgetUsd", invocation.limits().maxBudgetUsd());
    limits.put(
        "timeoutMinutes",
        invocation.limits().timeout() == null ? null : invocation.limits().timeout().toMinutes());
    spawned.put("limits", limits);
    if (parent != null) {
      spawned.put("parentAgentRunId", parent.getId());
    }
    append("agent_run", agent.getId(), "agent.spawned", run.getId(), spawned, now);
    publisher.publishEvent(
        new AgentRunQueued(
            agent.getId(),
            new StartAgent(
                run.getId(),
                workItem.getKey(),
                repository.getLocalPath(),
                repository.getDefaultBranch(),
                invocation.sessionId(),
                invocation.prompt(),
                policy.allowedTools(),
                policy.permissionMode(),
                defaults.model(),
                invocation.limits(),
                invocation.resume(),
                policy.environment()),
            invocation.runnerId()));
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
   * El runner del agente ha dejado de declararlo en sus latidos: su proceso ya no existe (el runner
   * perdió su journal, o la orden se confirmó pero la invocación se perdió). Pasa a {@code FAILED},
   * o a {@code CANCELLED} si ya se había pedido cancelarlo. Devuelve si lo ha cerrado.
   */
  @Transactional
  public boolean failLost(UUID agentRunId) {
    AgentRun agent = agentRuns.findById(agentRunId).orElseThrow(() -> notFound(agentRunId));
    if (agent.getStatus().isTerminal()) {
      return false;
    }
    StageRun stage = stageRuns.findById(agent.getStageRunId()).orElseThrow();
    WorkflowRun run = workflowRuns.findById(stage.getWorkflowRunId()).orElseThrow();
    Instant now = time.now();
    AgentObservableStatus outcome =
        agent.exited(null, null, "El proceso ya no existe en el runner");
    agent = transitions.advanceAgent(agent, outcome, run.getId(), "process-lost", now);
    transitions.finishAdhoc(stage, run, outcome, now);
    agentRuns.save(agent);
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
        AgentRunView.of(agent, workspaces.find(agent.getWorkspaceId()).orElse(null)),
        stage.getWorkflowRunId(),
        prompts.findByAgentRunIdOrderByCreatedAtAsc(agentRunId).stream()
            .map(PromptView::of)
            .toList());
  }

  /**
   * Conversación a la que pertenece el agente: sube por reanudaciones y forks hasta el lanzamiento
   * que la empezó y baja por las reanudaciones hasta la última invocación.
   */
  @Transactional(readOnly = true)
  public ConversationView conversation(UUID agentRunId) {
    AgentRun agent = agentRuns.findById(agentRunId).orElseThrow(() -> notFound(agentRunId));
    LinkedList<AgentRun> chain = new LinkedList<>();
    AgentRun current = agent;
    while (current.getParentAgentRunId() != null
        && (current.getKind() == AgentRunKind.RESUME || current.getKind() == AgentRunKind.FORK)) {
      current = agentRuns.findById(current.getParentAgentRunId()).orElseThrow();
      chain.addFirst(current);
    }
    chain.add(agent);
    AgentRun last = agent;
    for (AgentRun next = lastStep(last); next != null; next = lastStep(last)) {
      chain.add(next);
      last = next;
    }
    Map<UUID, WorkspaceView> worktrees = new HashMap<>();
    workspaces
        .findAll(
            chain.stream()
                .map(AgentRun::getWorkspaceId)
                .filter(Objects::nonNull)
                .distinct()
                .toList())
        .forEach(w -> worktrees.put(w.id(), w));
    return new ConversationView(
        chain.stream()
            .map(
                a -> {
                  UUID runId =
                      stageRuns.findById(a.getStageRunId()).orElseThrow().getWorkflowRunId();
                  String promptText =
                      prompts.findByAgentRunIdOrderByCreatedAtAsc(a.getId()).stream()
                          .filter(p -> Prompt.ROLE_USER.equals(p.getRole()))
                          .map(Prompt::getContent)
                          .findFirst()
                          .orElse(null);
                  return new ConversationView.Turn(
                      runId,
                      AgentRunView.of(a, worktrees.get(a.getWorkspaceId())),
                      promptText,
                      messages(runId, a.getId()));
                })
            .toList());
  }

  /** Reanudación más reciente de {@code agent}, o {@code null} si no la hay. */
  private AgentRun lastStep(AgentRun agent) {
    return jdbc.sql(
            "SELECT id FROM agent_run WHERE parent_agent_run_id = ? AND kind = ?"
                + " ORDER BY created_at DESC LIMIT 1")
        .params(agent.getId(), AgentRunKind.RESUME.name())
        .query(UUID.class)
        .optional()
        .flatMap(agentRuns::findById)
        .orElse(null);
  }

  private List<ConversationView.Message> messages(UUID workflowRunId, UUID agentRunId) {
    return jdbc.sql(
            "SELECT sequence, occurred_at, payload ->> 'text' AS text FROM event"
                + " WHERE workflow_run_id = ? AND aggregate_id = ?"
                + " AND event_type = 'agent.message.received'"
                + " AND payload ->> 'parentToolUseId' IS NULL ORDER BY sequence")
        .params(workflowRunId, agentRunId)
        .query(
            (rs, row) ->
                new ConversationView.Message(
                    rs.getLong("sequence"),
                    rs.getTimestamp("occurred_at").toInstant(),
                    rs.getString("text")))
        .list();
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
    Map<UUID, WorkspaceView> worktrees = new HashMap<>();
    workspaces
        .findAll(
            agents.stream()
                .map(AgentRun::getWorkspaceId)
                .filter(Objects::nonNull)
                .distinct()
                .toList())
        .forEach(w -> worktrees.put(w.id(), w));
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
                                        .map(
                                            a ->
                                                AgentRunView.of(
                                                    a, worktrees.get(a.getWorkspaceId())))
                                        .toList()))
                        .toList()))
        .toList();
  }

  /**
   * Límites de un lanzamiento: los que falten se toman de la política; si alguno supera el máximo
   * de la política, se rechaza.
   */
  private static AgentLimits withinPolicy(AgentLimits limits, AgentPolicy policy) {
    AgentLimits l = limits == null ? AgentLimits.none() : limits;
    return new AgentLimits(
        within(l.maxTurns(), policy.maxTurns(), "Los turnos máximos", ""),
        within(l.maxBudgetUsd(), policy.maxBudgetUsd(), "El presupuesto", " US$"),
        l.timeout() == null
            ? policy.timeout()
            : Duration.ofMinutes(
                within(
                    Math.toIntExact(l.timeout().toMinutes()),
                    policy.timeoutMinutes(),
                    "El tiempo máximo",
                    " min")));
  }

  private static <T extends Comparable<T>> T within(
      T requested, T maximum, String what, String unit) {
    if (requested == null) {
      return maximum;
    }
    if (maximum != null && requested.compareTo(maximum) > 0) {
      throw new InvalidRequestException(
          what
              + " ("
              + plain(requested)
              + unit
              + ") supera el máximo del repositorio ("
              + plain(maximum)
              + unit
              + ")");
    }
    return requested;
  }

  private static String plain(Object value) {
    return value instanceof BigDecimal d
        ? d.stripTrailingZeros().toPlainString()
        : value.toString();
  }

  /**
   * Límites al reintentar o continuar: los de la invocación de partida, rebajados a los de la
   * política si esta se ha endurecido desde entonces.
   */
  private static AgentLimits cappedByPolicy(AgentLimits limits, AgentPolicy policy) {
    AgentLimits l = limits == null ? AgentLimits.none() : limits;
    return new AgentLimits(
        min(l.maxTurns(), policy.maxTurns()),
        min(l.maxBudgetUsd(), policy.maxBudgetUsd()),
        min(l.timeout(), policy.timeout()));
  }

  private static <T extends Comparable<T>> T min(T value, T maximum) {
    if (value == null || maximum == null) {
      return value == null ? maximum : value;
    }
    return value.compareTo(maximum) <= 0 ? value : maximum;
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
