package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.definition.StageDefinition;
import dev.skynet.controlplane.definition.StageType;
import dev.skynet.controlplane.definition.WorkflowDefinitions;
import dev.skynet.protocol.AgentObservableStatus;
import dev.skynet.protocol.StageStatus;
import dev.skynet.protocol.runner.AgentLimits;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * La verificación de las fases con {@code artifacts} o {@code checks} (W4-B): cuando su agente
 * termina bien, la fase sigue en marcha («Verificando») mientras el runner comprueba su worktree, y
 * solo termina bien si pasan todas las comprobaciones; si no, falla con el motivo y se aplica el
 * fail-fast. En una fase {@code parallel} se verifica cada hijo en su worktree. Una fase {@code
 * verification} no tiene agente: su única invocación es esa verificación.
 *
 * <p>Una fase sin comprobaciones se cierra como siempre, y su worktree se verifica después solo
 * para informar (M5).
 */
@Component
class StageVerifications {

  private final AgentRunRepository agentRuns;
  private final StageRunRepository stageRuns;
  private final WorkflowRunRepository workflowRuns;
  private final WorkflowDefinitions definitions;
  private final Workspaces workspaces;
  private final Verifications verifications;
  private final VerificationService verificationService;
  private final RunTransitions transitions;

  StageVerifications(
      AgentRunRepository agentRuns,
      StageRunRepository stageRuns,
      WorkflowRunRepository workflowRuns,
      WorkflowDefinitions definitions,
      Workspaces workspaces,
      Verifications verifications,
      VerificationService verificationService,
      RunTransitions transitions) {
    this.agentRuns = agentRuns;
    this.stageRuns = stageRuns;
    this.workflowRuns = workflowRuns;
    this.definitions = definitions;
    this.workspaces = workspaces;
    this.verifications = verifications;
    this.verificationService = verificationService;
    this.transitions = transitions;
  }

  /**
   * El agente de una fase ha terminado: si terminó bien y la fase tiene comprobaciones, pone en
   * cola la verificación de su worktree; si no, la verificación informativa del repositorio. Luego
   * cierra la fase si ya puede.
   */
  void afterAgent(AgentRun agent, StageRun stage, Instant now) {
    StageDefinition definition = definitionOf(stage);
    if (agent.getStatus() == AgentObservableStatus.COMPLETED) {
      WorkspaceView workspace = workspaces.find(agent.getWorkspaceId()).orElse(null);
      // Si otro hijo ya no pasó, la fase va a fallar: no hace falta comprobar este. Una fase ya
      // terminada solo tiene agentes nuevos si una puerta le pidió cambios (W5-B): se comprueban.
      if (definition != null
          && definition.verified()
          && workspace != null
          && (stage.getStatus().isTerminal() || !verifications.stageFailed(stage.getId()))) {
        queue(agent, workspace, stage, definition, agent.getBaseCommit(), now);
      } else {
        verificationService.afterCompletion(agent, now);
      }
    }
    finish(stage, definition, now);
  }

  /**
   * Arranca una fase {@code verification} en el worktree que hereda ({@code source}): una
   * invocación sin agente ni comando, con el runner del worktree, cuya verificación mide los
   * cambios desde el commit con el que se creó.
   */
  void startNode(
      UUID runId,
      UUID repositoryId,
      StageRun stage,
      StageDefinition definition,
      WorkspaceView source,
      Instant now) {
    AgentRun agent =
        AgentRun.queued(
            stage.getId(),
            repositoryId,
            AgentRun.PROVIDER_VERIFICATION,
            UUID.randomUUID(),
            source.id(),
            AgentLimits.none(),
            now);
    agent.assignRunner(source.runnerId());
    agent.recordBaseCommit(source.baseCommit());
    agent = agentRuns.save(agent);
    Map<String, Object> spawned = new LinkedHashMap<>();
    spawned.put("stageRunId", stage.getId());
    spawned.put("provider", agent.getProvider());
    spawned.put("workspaceId", source.id());
    transitions.append("agent_run", agent.getId(), "agent.spawned", runId, spawned, now);
    agent =
        transitions.agent(agent, AgentObservableStatus.STARTING, runId, "verification-queued", now);
    stage = transitions.advanceStage(stage, StageStatus.STARTING, "verification-queued", now);
    stage = transitions.advanceStage(stage, StageStatus.RUNNING, "verification-queued", now);
    queue(agent, source, stage, definition, source.baseCommit(), now);
  }

  /**
   * Ha terminado una verificación de fase: lo registra en la fase y la cierra si ya puede. En una
   * fase {@code verification}, su invocación termina con el resultado. En una fase que ya terminó
   * bien es la de una corrección (W5-B): se registra y la puerta que la pidió decide.
   */
  void completed(UUID verificationRunId, Instant now) {
    VerificationResult result = verifications.find(verificationRunId).orElseThrow();
    if (result.stageRunId() == null || result.isLive()) {
      return;
    }
    StageRun stage = stageRuns.findById(result.stageRunId()).orElseThrow();
    AgentRun agent = agentRuns.findById(result.agentRunId()).orElseThrow();
    if (stage.getStatus().isTerminal() && stage.getStatus() != StageStatus.SUCCEEDED) {
      return;
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("stageKey", stage.getStageKey());
    if (agent.getChildKey() != null) {
      payload.put("childKey", agent.getChildKey());
    }
    payload.put("agentRunId", agent.getId());
    payload.put("verificationRunId", verificationRunId);
    payload.put("status", result.status());
    payload.put("checks", result.checks());
    if (result.error() != null) {
      payload.put("error", result.error());
    }
    transitions.append(
        "stage_run",
        stage.getId(),
        "stage.verification.completed",
        stage.getWorkflowRunId(),
        payload,
        now);
    if (agent.isVerification() && !agent.getStatus().isTerminal()) {
      boolean passed = Verifications.PASSED.equals(result.status());
      if (!passed) {
        agent.fail(reason(result));
      }
      transitions.advanceAgent(
          agent,
          passed ? AgentObservableStatus.COMPLETED : AgentObservableStatus.FAILED,
          stage.getWorkflowRunId(),
          passed ? "verification-passed" : "verification-failed",
          now);
    }
    finish(stage, definitionOf(stage), now);
  }

  /**
   * Cierra la fase cuando han terminado sus agentes y, si tiene comprobaciones, las verificaciones
   * de todos ellos: bien si todas pasan y fallida si alguna no. Una fase {@code verification} sigue
   * a su invocación, que ya lleva el resultado.
   */
  private void finish(StageRun stage, StageDefinition definition, Instant now) {
    if (stage.getStatus().isTerminal()) {
      // Se canceló mientras se verificaba.
      return;
    }
    List<AgentRun> agents = agentRuns.findByStageRunIdInOrderByCreatedAtAsc(List.of(stage.getId()));
    boolean verifiedHere =
        definition != null && definition.verified() && definition.type() != StageType.VERIFICATION;
    if (!verifiedHere
        || agents.isEmpty()
        || agents.stream().anyMatch(a -> !a.getStatus().isTerminal())) {
      transitions.finishStage(stage, now);
      return;
    }
    AgentObservableStatus outcome = RunTransitions.outcome(agents);
    boolean checkFailed = verifications.stageFailed(stage.getId());
    if (outcome == AgentObservableStatus.CANCELLED && checkFailed) {
      // Un hijo no pasó su verificación y se canceló a sus hermanos: la fase falla por él.
      transitions.advanceStage(stage, StageStatus.FAILED, "verification-failed", now);
      return;
    }
    if (outcome != AgentObservableStatus.COMPLETED) {
      transitions.finishStage(stage, now);
      return;
    }
    Map<UUID, VerificationResult> latest = verifications.latestOfStage(stage.getId());
    List<VerificationResult> results = agents.stream().map(a -> latest.get(a.getId())).toList();
    if (results.stream().anyMatch(r -> r != null && r.isLive())) {
      return;
    }
    boolean failed =
        results.stream().anyMatch(r -> r != null && !Verifications.PASSED.equals(r.status()));
    if (!failed && results.stream().anyMatch(r -> r == null)) {
      return;
    }
    boolean passed = !failed;
    transitions.advanceStage(
        stage,
        passed ? StageStatus.SUCCEEDED : StageStatus.FAILED,
        passed ? "verification-passed" : "verification-failed",
        now);
  }

  private void queue(
      AgentRun agent,
      WorkspaceView workspace,
      StageRun stage,
      StageDefinition definition,
      String baseCommit,
      Instant now) {
    UUID id = verificationService.queueStage(agent, workspace, stage, definition, baseCommit, now);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("stageKey", stage.getStageKey());
    if (agent.getChildKey() != null) {
      payload.put("childKey", agent.getChildKey());
    }
    payload.put("agentRunId", agent.getId());
    payload.put("verificationRunId", id);
    payload.put("checks", definition.checks().stream().map(c -> c.name()).toList());
    transitions.append(
        "stage_run",
        stage.getId(),
        "stage.verification.started",
        stage.getWorkflowRunId(),
        payload,
        now);
  }

  /** Por qué no pasó: las comprobaciones que fallaron, con su motivo, o el error del runner. */
  static String reason(VerificationResult result) {
    String failed =
        result.checks().stream()
            .filter(c -> Boolean.FALSE.equals(c.passed()))
            .map(c -> c.name() + ": " + c.reason())
            .collect(Collectors.joining("; "));
    if (!failed.isEmpty()) {
      return failed;
    }
    return result.error() == null ? "La verificación no pasó" : result.error();
  }

  private StageDefinition definitionOf(StageRun stage) {
    WorkflowRun run = workflowRuns.findById(stage.getWorkflowRunId()).orElseThrow();
    return definitions
        .published(run.getDefinitionId())
        .definition()
        .stage(stage.getStageKey())
        .orElse(null);
  }
}
