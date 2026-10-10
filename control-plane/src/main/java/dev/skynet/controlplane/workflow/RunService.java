package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.definition.AgentDefinition;
import dev.skynet.controlplane.definition.PublishedDefinition;
import dev.skynet.controlplane.definition.StageDefinition;
import dev.skynet.controlplane.definition.WorkflowDefinition;
import dev.skynet.controlplane.definition.WorkflowDefinitions;
import dev.skynet.controlplane.project.AgentPolicy;
import dev.skynet.controlplane.project.CodeRepository;
import dev.skynet.controlplane.project.ProjectService;
import dev.skynet.controlplane.shared.ArchiveState;
import dev.skynet.controlplane.shared.Archived;
import dev.skynet.controlplane.shared.Archiving;
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
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
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
import tools.jackson.databind.ObjectMapper;

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
  private final Workspaces workspaces;
  private final AgentQueue agentQueue;
  private final WorkflowDefinitions definitions;
  private final ObjectMapper json;
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
      Workspaces workspaces,
      AgentQueue agentQueue,
      WorkflowDefinitions definitions,
      ObjectMapper json,
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
    this.workspaces = workspaces;
    this.agentQueue = agentQueue;
    this.definitions = definitions;
    this.json = json;
    this.publisher = publisher;
  }

  /**
   * Qué se lanza.
   *
   * @param definitionId versión publicada del workflow, o {@code null} para la última de {@code
   *     adhoc}
   * @param inputs datos de entrada del workflow; {@code null} si no pide ninguno
   * @param prompt atajo para el dato {@code prompt} (el de {@code adhoc}), o {@code null}
   * @param limits límites para los agentes que no fijan los suyos; los que falten (o todos, con
   *     {@code null}) se toman de la política del repositorio, y ninguno puede superar los suyos
   */
  public record Launch(
      UUID repositoryId,
      UUID definitionId,
      Map<String, Object> inputs,
      String prompt,
      AgentLimits limits) {}

  /** Lanza {@code adhoc}: una fase con un agente y ese prompt. */
  @Transactional
  public WorkflowRun launch(
      UUID workItemId, UUID repositoryId, String promptText, AgentLimits limits) {
    return launch(workItemId, new Launch(repositoryId, null, null, promptText, limits));
  }

  /**
   * Lanza una versión publicada de un workflow sobre un trabajo y un repositorio. Crea la ejecución
   * con todas sus fases en {@code PENDING}; el motor activa las que pueden empezar.
   */
  @Transactional
  public WorkflowRun launch(UUID workItemId, Launch launch) {
    WorkItem workItem = workItems.get(workItemId);
    CodeRepository repository = projects.getRepository(launch.repositoryId());
    if (!repository.getProjectId().equals(workItem.getProjectId())) {
      throw new ConflictException(
          "El repositorio " + repository.getName() + " no pertenece al proyecto del trabajo");
    }
    requireActive(workItem, repository, null);
    PublishedDefinition definition =
        launch.definitionId() == null
            ? definitions.latestLaunchable(WorkflowDefinitions.ADHOC)
            : definitions.launchable(launch.definitionId());
    Map<String, Object> given =
        new LinkedHashMap<>(launch.inputs() == null ? Map.of() : launch.inputs());
    if (launch.prompt() != null) {
      boolean asksPrompt =
          definition.definition().inputs().stream().anyMatch(i -> i.name().equals("prompt"));
      if (!asksPrompt) {
        throw new InvalidRequestException(
            "El workflow " + definition.key() + " no pide un prompt: rellena sus datos");
      }
      given.putIfAbsent("prompt", launch.prompt());
    }
    Map<String, Object> inputs = LaunchInputs.resolve(definition.definition().inputs(), given);
    AgentLimits limits = withinPolicy(launch.limits(), projects.agentPolicy(repository));
    Instant now = time.now();

    WorkflowRun run = WorkflowRun.create(workItem.getId(), definition.id(), now);
    run.transitionTo(WorkflowRunStatus.RUNNING, now);
    run = workflowRuns.save(run);
    jdbc.sql(
            "UPDATE workflow_run SET repository_id = ?, inputs = CAST(? AS jsonb),"
                + " launch_limits = CAST(? AS jsonb) WHERE id = ?")
        .params(
            repository.getId(),
            json.writeValueAsString(inputs),
            json.writeValueAsString(limitsJson(limits)),
            run.getId())
        .update();
    Map<String, Object> started = new LinkedHashMap<>();
    started.put("workItemId", workItem.getId());
    started.put("definition", definition.key());
    started.put("definitionVersion", definition.version());
    started.put("definitionId", definition.id());
    started.put("status", run.getStatus());
    append("workflow_run", run.getId(), "workflow.started", run.getId(), started, now);
    for (StageDefinition stageDefinition : definition.definition().stages()) {
      StageRun stage = stageRuns.save(StageRun.create(run.getId(), stageDefinition.id(), now));
      Map<String, Object> pending = new LinkedHashMap<>();
      pending.put("stageKey", stage.getStageKey());
      pending.put("attempt", stage.getAttempt());
      pending.put("dependsOn", stageDefinition.dependsOn().stream().map(Object::toString).toList());
      append("stage_run", stage.getId(), "stage.pending", run.getId(), pending, now);
    }
    publisher.publishEvent(new WorkflowRunChanged(run.getId()));
    return run;
  }

  /**
   * Lo que se le permite a una invocación que sigue a {@code parent} (reintento, mensaje o
   * bifurcación): lo mismo que a su agente del YAML con la política actual del repositorio, y los
   * límites de {@code parent} rebajados a esa política.
   */
  private EffectivePolicy inheritedPolicy(AgentRun parent, CodeRepository repository) {
    AgentPolicy policy = projects.agentPolicy(repository);
    AgentLimits limits = cappedByPolicy(parent.limits(), policy);
    StageRun stage = stageRuns.findById(parent.getStageRunId()).orElseThrow();
    WorkflowDefinition definition =
        definitions.published(runEntity(stage.getWorkflowRunId()).getDefinitionId()).definition();
    AgentDefinition agent =
        definition
            .stage(stage.getStageKey())
            .map(StageDefinition::agent)
            .flatMap(definition::agent)
            .orElse(null);
    EffectivePolicy effective = EffectivePolicy.of(policy, agent, limits);
    return new EffectivePolicy(
        effective.allowedTools(), effective.permissionMode(), effective.environment(), limits);
  }

  /** Límites como se guardan en {@code launch_limits}. */
  static Map<String, Object> limitsJson(AgentLimits limits) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("maxTurns", limits.maxTurns());
    out.put("maxBudgetUsd", limits.maxBudgetUsd());
    out.put("timeoutMinutes", limits.timeout() == null ? null : limits.timeout().toMinutes());
    return out;
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
            AgentRunKind.RETRY, parent, promptText, inheritedPolicy(parent, repository)));
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
            inheritedPolicy(parent, repository),
            fork ? null : workspace.id(),
            new ResumeFrom(
                parent.getProviderSessionId(), fork, workspace.path(), workspace.branch()),
            null,
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

  /**
   * Lo archivado es de solo lectura: no se lanzan agentes en un trabajo, proyecto o repositorio
   * archivado, ni se continúa un agente de una ejecución archivada.
   */
  private void requireActive(WorkItem workItem, CodeRepository repository, AgentRun parent) {
    workItems.requireActive(workItem);
    projects.requireActive(repository);
    if (parent != null) {
      WorkflowRun run = runOf(parent);
      if (run.getArchivedAt() != null) {
        throw new ConflictException(
            "La ejecución " + run.getId() + " está archivada: restáurala para continuar");
      }
    }
  }

  private WorkflowRun runOf(AgentRun agent) {
    StageRun stage = stageRuns.findById(agent.getStageRunId()).orElseThrow();
    return workflowRuns.findById(stage.getWorkflowRunId()).orElseThrow();
  }

  private WorkItem workItemOf(AgentRun agent) {
    StageRun stage = stageRuns.findById(agent.getStageRunId()).orElseThrow();
    WorkflowRun run = workflowRuns.findById(stage.getWorkflowRunId()).orElseThrow();
    return workItems.get(run.getWorkItemId());
  }

  /**
   * Crea una ejecución {@code adhoc} hija con su fase lista y su agente en cola, y publica la
   * orden: continuar, bifurcar o reintentar un agente no toca la ejecución de la que parte. Las
   * herramientas, el modo de permisos y el entorno son los de la política actual del repositorio.
   */
  private WorkflowRun spawn(WorkItem workItem, CodeRepository repository, Invocation invocation) {
    Instant now = time.now();
    AgentRun parent = invocation.parent();
    requireActive(workItem, repository, parent);
    PublishedDefinition adhoc = definitions.latestLaunchable(WorkflowDefinitions.ADHOC);

    WorkflowRun run = WorkflowRun.create(workItem.getId(), adhoc.id(), now);
    run.transitionTo(WorkflowRunStatus.RUNNING, now);
    run = workflowRuns.save(run);
    jdbc.sql(
            "UPDATE workflow_run SET repository_id = ?, inputs = CAST(? AS jsonb),"
                + " launch_limits = CAST(? AS jsonb) WHERE id = ?")
        .params(
            repository.getId(),
            json.writeValueAsString(Map.of("prompt", invocation.prompt())),
            json.writeValueAsString(limitsJson(invocation.policy().limits())),
            run.getId())
        .update();
    Map<String, Object> started = new LinkedHashMap<>();
    started.put("workItemId", workItem.getId());
    started.put("definition", adhoc.key());
    started.put("definitionVersion", adhoc.version());
    started.put("definitionId", adhoc.id());
    started.put("status", run.getStatus());
    if (parent != null) {
      started.put("parentAgentRunId", parent.getId());
    }
    append("workflow_run", run.getId(), "workflow.started", run.getId(), started, now);

    StageRun stage = stageRuns.save(StageRun.create(run.getId(), ADHOC_STAGE, now));
    stage = transitions.readyStage(stage, now);
    agentQueue.queue(run, stage, workItem, repository, invocation, now);
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
      transitions.finishStage(stage, AgentObservableStatus.CANCELLED, now);
      publisher.publishEvent(new AgentRunWithdrawn(agent.getId()));
      publisher.publishEvent(new WorkflowRunChanged(run.getId()));
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
   * Cancela la ejecución: las fases que no han empezado se cancelan, los agentes en cola se retiran
   * y a los que ya están en un runner se les ordena terminar. Queda cancelada cuando terminan todas
   * sus fases. Pedirlo otra vez mientras tanto no tiene efecto.
   */
  @Transactional
  public RunView cancelRun(UUID workflowRunId) {
    lockRun(workflowRunId);
    WorkflowRun run = runEntity(workflowRunId);
    if (run.getStatus().isTerminal()) {
      throw new ConflictException(
          "La ejecución " + workflowRunId + " ya ha terminado (" + run.getStatus() + ")");
    }
    Instant now = time.now();
    for (StageRun stage :
        stageRuns.findByWorkflowRunIdInOrderByCreatedAtAsc(List.of(run.getId()))) {
      cancelStage(stage, "cancelled-by-user", now);
    }
    publisher.publishEvent(new WorkflowRunChanged(run.getId()));
    return run(workflowRunId);
  }

  /**
   * Cancela una fase que no ha terminado. Sin agente, o con él aún en cola, queda cancelada en el
   * acto; con el agente en un runner, se le ordena terminar y la fase se cierra cuando acabe.
   * Devuelve si la fase ha cambiado de estado.
   */
  boolean cancelStage(StageRun stage, String reason, Instant now) {
    if (stage.getStatus().isTerminal()) {
      return false;
    }
    boolean running = false;
    for (AgentRun agent : agentRuns.findByStageRunIdInOrderByCreatedAtAsc(List.of(stage.getId()))) {
      if (agent.getStatus() == AgentObservableStatus.QUEUED) {
        transitions.agent(
            agent, AgentObservableStatus.CANCELLED, stage.getWorkflowRunId(), reason, now);
        publisher.publishEvent(new AgentRunWithdrawn(agent.getId()));
      } else if (!agent.getStatus().isTerminal()) {
        running = true;
        if (agent.requestCancel(now)) {
          agent = agentRuns.save(agent);
          append(
              "agent_run",
              agent.getId(),
              "agent.cancel.requested",
              stage.getWorkflowRunId(),
              Map.of("runnerId", agent.getRunnerId()),
              now);
          publisher.publishEvent(new AgentCancelRequested(agent.getId(), agent.getRunnerId()));
        }
      }
    }
    if (running) {
      return false;
    }
    transitions.advanceStage(stage, StageStatus.CANCELLED, reason, now);
    return true;
  }

  /** Bloquea la ejecución hasta el final de la transacción: el motor la evalúa de una en una. */
  void lockRun(UUID workflowRunId) {
    jdbc.sql("SELECT id FROM workflow_run WHERE id = ? FOR UPDATE")
        .param(workflowRunId)
        .query(UUID.class)
        .optional()
        .orElseThrow(() -> new NotFoundException("Ejecución", workflowRunId));
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
    transitions.finishStage(stage, outcome, now);
    agentRuns.save(agent);
    publisher.publishEvent(new WorkflowRunChanged(run.getId()));
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
  public List<RunView> runsOf(UUID workItemId, Archived archived) {
    workItems.get(workItemId);
    return views(
        workflowRuns.findByWorkItemIdOrderByCreatedAtDesc(workItemId).stream()
            .filter(r -> archived.accepts(r.getArchivedAt()))
            .toList());
  }

  /**
   * Archiva la ejecución: sale de las listas, del dashboard y de las métricas, y sus agentes no se
   * pueden continuar hasta que se restaure. Solo se archiva una ejecución terminada.
   */
  @Transactional
  public ArchiveState archive(UUID workflowRunId) {
    WorkflowRun run = runEntity(workflowRunId);
    if (run.getArchivedAt() != null) {
      return new ArchiveState(workflowRunId, run.getArchivedAt());
    }
    if (!run.getStatus().isTerminal()) {
      throw new ConflictException(
          "La ejecución "
              + workflowRunId
              + " sigue en curso ("
              + run.getStatus()
              + "): cancélala o espera a que termine antes de archivarla");
    }
    Instant now = time.now();
    Archiving.set(jdbc, "workflow_run", workflowRunId, now);
    append(
        "workflow_run",
        workflowRunId,
        "workflow.archived",
        workflowRunId,
        Map.of("workItemId", run.getWorkItemId()),
        now);
    return new ArchiveState(workflowRunId, now);
  }

  /** Restaura la ejecución; ni su trabajo ni su proyecto pueden estar archivados. */
  @Transactional
  public ArchiveState restore(UUID workflowRunId) {
    WorkflowRun run = runEntity(workflowRunId);
    if (run.getArchivedAt() == null) {
      return new ArchiveState(workflowRunId, null);
    }
    workItems.requireActive(workItems.get(run.getWorkItemId()));
    Archiving.set(jdbc, "workflow_run", workflowRunId, null);
    append(
        "workflow_run",
        workflowRunId,
        "workflow.restored",
        workflowRunId,
        Map.of("workItemId", run.getWorkItemId()),
        time.now());
    return new ArchiveState(workflowRunId, null);
  }

  private WorkflowRun runEntity(UUID workflowRunId) {
    return workflowRuns
        .findById(workflowRunId)
        .orElseThrow(() -> new NotFoundException("Ejecución", workflowRunId));
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
   * null}: sin filtro). Una ejecución cuenta como archivada si lo está ella, su trabajo o su
   * proyecto.
   */
  @Transactional(readOnly = true)
  public RunPage list(
      Set<WorkflowRunStatus> statuses,
      UUID projectId,
      Instant since,
      String query,
      Archived archived,
      int page,
      int size) {
    String text = query == null || query.isBlank() ? null : query.strip();
    String where =
        " FROM workflow_run r JOIN work_item w ON w.id = r.work_item_id"
            + " JOIN project p ON p.id = w.project_id"
            + " WHERE (CAST(:project AS uuid) IS NULL OR w.project_id = :project)"
            + archived.sql("r.archived_at", "w.archived_at", "p.archived_at")
            + " AND (CAST(:since AS timestamptz) IS NULL OR r.created_at >= :since)"
            + (statuses == null || statuses.isEmpty() ? "" : " AND r.status IN (:statuses)")
            + (text == null
                ? ""
                : " AND (w.key ILIKE :q ESCAPE '\\' OR w.title ILIKE :q ESCAPE '\\')");
    Map<String, Object> params = new HashMap<>();
    params.put("project", projectId);
    params.put("since", since == null ? null : Timestamp.from(since));
    if (text != null) {
      // Los comodines de LIKE que escriba el usuario se buscan tal cual.
      params.put("q", "%" + text.replaceAll("([\\\\%_])", "\\\\$1") + "%");
    }
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

  /**
   * Métricas de las ejecuciones creadas desde {@code since}, en tramos de una hora o de un día
   * ({@code bucket}) según el calendario de {@code zone}.
   */
  @Transactional(readOnly = true)
  public RunMetrics metrics(Instant since, Instant until, String bucket, ZoneId zone) {
    if (!bucket.equals("hour") && !bucket.equals("day")) {
      throw new IllegalArgumentException("Tramo no válido: " + bucket);
    }
    Map<String, Object> params = new HashMap<>();
    params.put("since", Timestamp.from(since));
    params.put("until", Timestamp.from(until));
    params.put("unit", bucket);
    params.put("tz", zone.getId());
    // Una fila por ejecución del periodo, con la suma de sus agentes.
    String runs =
        "WITH runs AS (SELECT r.id, r.status, r.created_at, r.started_at, r.finished_at,"
            + " (SELECT sum(a.input_tokens) FROM agent_run a JOIN stage_run s"
            + " ON s.id = a.stage_run_id WHERE s.workflow_run_id = r.id) AS input_tokens,"
            + " (SELECT sum(a.output_tokens) FROM agent_run a JOIN stage_run s"
            + " ON s.id = a.stage_run_id WHERE s.workflow_run_id = r.id) AS output_tokens,"
            + " (SELECT sum(a.cost_usd) FROM agent_run a JOIN stage_run s"
            + " ON s.id = a.stage_run_id WHERE s.workflow_run_id = r.id) AS cost_usd"
            + " FROM workflow_run r JOIN work_item w ON w.id = r.work_item_id"
            + " JOIN project p ON p.id = w.project_id"
            + " WHERE r.created_at >= :since AND r.created_at <= :until"
            + Archived.EXCLUDE.sql("r.archived_at", "w.archived_at", "p.archived_at")
            + ")";
    RunMetrics totals =
        jdbc.sql(
                runs
                    + " SELECT count(*) AS total,"
                    + " count(*) FILTER (WHERE status IN ('PENDING', 'RUNNING')) AS active,"
                    + " count(*) FILTER (WHERE status = 'SUCCEEDED') AS succeeded,"
                    + " count(*) FILTER (WHERE status = 'FAILED') AS failed,"
                    + " count(*) FILTER (WHERE status = 'CANCELLED') AS cancelled,"
                    + " percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM"
                    + " finished_at - coalesce(started_at, created_at))) FILTER (WHERE"
                    + " finished_at IS NOT NULL) AS median,"
                    + " CAST(sum(input_tokens) AS bigint) AS input_tokens,"
                    + " CAST(sum(output_tokens) AS bigint) AS output_tokens,"
                    + " sum(cost_usd) AS cost_usd FROM runs")
            .params(params)
            .query(
                (rs, n) ->
                    new RunMetrics(
                        since,
                        until,
                        bucket,
                        rs.getLong("total"),
                        rs.getLong("active"),
                        rs.getLong("succeeded"),
                        rs.getLong("failed"),
                        rs.getLong("cancelled"),
                        rs.getObject("median", Double.class),
                        rs.getObject("input_tokens", Long.class),
                        rs.getObject("output_tokens", Long.class),
                        rs.getBigDecimal("cost_usd"),
                        List.of()))
            .single();
    List<RunMetrics.Bucket> buckets =
        jdbc.sql(
                runs
                    + ", buckets AS (SELECT generate_series(date_trunc(:unit, CAST(:since AS"
                    + " timestamptz) AT TIME ZONE :tz), date_trunc(:unit, CAST(:until AS"
                    + " timestamptz) AT TIME ZONE :tz), CAST('1 ' || :unit AS interval)) AS b)"
                    + " SELECT b AT TIME ZONE :tz AS start, count(runs.id) AS total,"
                    + " count(runs.id) FILTER (WHERE runs.status = 'SUCCEEDED') AS succeeded,"
                    + " count(runs.id) FILTER (WHERE runs.status = 'FAILED') AS failed,"
                    + " count(runs.id) FILTER (WHERE runs.status = 'CANCELLED') AS cancelled,"
                    + " sum(runs.cost_usd) AS cost_usd FROM buckets LEFT JOIN runs"
                    + " ON date_trunc(:unit, runs.created_at AT TIME ZONE :tz) = b"
                    + " GROUP BY b ORDER BY b")
            .params(params)
            .query(
                (rs, n) ->
                    new RunMetrics.Bucket(
                        rs.getTimestamp("start").toInstant(),
                        rs.getLong("total"),
                        rs.getLong("succeeded"),
                        rs.getLong("failed"),
                        rs.getLong("cancelled"),
                        rs.getBigDecimal("cost_usd")))
            .list();
    return new RunMetrics(
        totals.since(),
        totals.until(),
        totals.bucket(),
        totals.total(),
        totals.active(),
        totals.succeeded(),
        totals.failed(),
        totals.cancelled(),
        totals.medianDurationSeconds(),
        totals.inputTokens(),
        totals.outputTokens(),
        totals.costUsd(),
        buckets);
  }

  /**
   * Versiones publicadas de los workflows, por clave y de la más reciente a la más antigua. Los
   * borradores y su gestión están en el módulo {@code definition}.
   */
  @Transactional(readOnly = true)
  public List<WorkflowDefinitionView> definitions() {
    return jdbc.sql(
            "SELECT id, key, version, source_yaml, created_at FROM workflow_definition"
                + " WHERE status = 'PUBLISHED' ORDER BY key, version DESC")
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
