package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.event.EventDraft;
import dev.skynet.controlplane.event.EventStore;
import dev.skynet.protocol.AgentObservableStatus;
import dev.skynet.protocol.StageStatus;
import dev.skynet.protocol.WorkflowRunStatus;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Transiciones de agentes, fases y ejecuciones que registran su evento y guardan la entidad.
 *
 * <p>Los métodos {@code advance*} toleran que la transición ya no sea posible (p. ej. eventos que
 * llegan tras una cancelación): en ese caso no hacen nada y devuelven {@code false}. Los pasos
 * intermedios obligatorios ({@code STARTING → RUNNING} antes de {@code SUCCEEDED}) se recorren
 * solos.
 */
@Component
class RunTransitions {

  private final AgentRunRepository agentRuns;
  private final StageRunRepository stageRuns;
  private final WorkflowRunRepository workflowRuns;
  private final EventStore events;

  RunTransitions(
      AgentRunRepository agentRuns,
      StageRunRepository stageRuns,
      WorkflowRunRepository workflowRuns,
      EventStore events) {
    this.agentRuns = agentRuns;
    this.stageRuns = stageRuns;
    this.workflowRuns = workflowRuns;
    this.events = events;
  }

  /** Transición obligatoria del agente: lanza {@code ConflictException} si no está permitida. */
  AgentRun agent(
      AgentRun agent, AgentObservableStatus next, UUID workflowRunId, String reason, Instant now) {
    AgentObservableStatus previous = agent.transitionTo(next, now);
    AgentRun saved = agentRuns.save(agent);
    append(
        "agent_run",
        agent.getId(),
        "agent.status.changed",
        workflowRunId,
        statusChange(previous, next, reason),
        now);
    return saved;
  }

  AgentRun advanceAgent(
      AgentRun agent, AgentObservableStatus next, UUID workflowRunId, String reason, Instant now) {
    if (agent.getStatus() == next) {
      return agent;
    }
    if (agent.getStatus() == AgentObservableStatus.STARTING
        && next == AgentObservableStatus.COMPLETED) {
      agent = agent(agent, AgentObservableStatus.THINKING, workflowRunId, reason, now);
    }
    if (!agent.getStatus().canTransitionTo(next)) {
      return agent;
    }
    return agent(agent, next, workflowRunId, reason, now);
  }

  StageRun advanceStage(StageRun stage, StageStatus next, String reason, Instant now) {
    if (stage.getStatus() == next) {
      return stage;
    }
    if (stage.getStatus() == StageStatus.STARTING && next == StageStatus.SUCCEEDED) {
      stage = advanceStage(stage, StageStatus.RUNNING, reason, now);
    }
    if (!stage.getStatus().canTransitionTo(next)) {
      return stage;
    }
    StageStatus previous = stage.transitionTo(next, now);
    StageRun saved = stageRuns.save(stage);
    append(
        "stage_run",
        stage.getId(),
        "stage.status.changed",
        stage.getWorkflowRunId(),
        statusChange(previous, next, reason),
        now);
    return saved;
  }

  /** La fase pasa de {@code PENDING} a {@code READY}: sus dependencias han terminado bien. */
  StageRun readyStage(StageRun stage, Instant now) {
    stage.transitionTo(StageStatus.READY, now);
    StageRun saved = stageRuns.save(stage);
    append(
        "stage_run",
        stage.getId(),
        "stage.ready",
        stage.getWorkflowRunId(),
        Map.of("stageKey", stage.getStageKey(), "attempt", stage.getAttempt()),
        now);
    return saved;
  }

  /** La fase no se ejecutará: una dependencia que necesitaba se ha omitido. */
  StageRun skipStage(StageRun stage, String reason, Instant now) {
    stage.transitionTo(StageStatus.SKIPPED, now);
    StageRun saved = stageRuns.save(stage);
    append(
        "stage_run",
        stage.getId(),
        "stage.skipped",
        stage.getWorkflowRunId(),
        Map.of("stageKey", stage.getStageKey(), "reason", reason),
        now);
    return saved;
  }

  WorkflowRun advanceRun(WorkflowRun run, WorkflowRunStatus next, String reason, Instant now) {
    if (run.getStatus() == next || !run.getStatus().canTransitionTo(next)) {
      return run;
    }
    WorkflowRunStatus previous = run.transitionTo(next, now);
    WorkflowRun saved = workflowRuns.save(run);
    append(
        "workflow_run",
        run.getId(),
        "workflow.status.changed",
        run.getId(),
        statusChange(previous, next, reason),
        now);
    return saved;
  }

  /**
   * Cierra la fase con el resultado de su agente. La ejecución la cierra el motor, que decide qué
   * viene después: quien llama publica {@link WorkflowRunChanged}.
   */
  StageRun finishStage(StageRun stage, AgentObservableStatus outcome, Instant now) {
    String reason = "agent-" + outcome.name().toLowerCase(java.util.Locale.ROOT);
    return switch (outcome) {
      case COMPLETED -> advanceStage(stage, StageStatus.SUCCEEDED, reason, now);
      case CANCELLED -> advanceStage(stage, StageStatus.CANCELLED, reason, now);
      default -> advanceStage(stage, StageStatus.FAILED, reason, now);
    };
  }

  void append(
      String aggregateType,
      UUID aggregateId,
      String type,
      UUID workflowRunId,
      Map<String, ?> payload,
      Instant now) {
    events.append(EventDraft.of(aggregateType, aggregateId, type, workflowRunId, payload, now));
  }

  static Map<String, Object> statusChange(Enum<?> previous, Enum<?> current, String reason) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("previousStatus", previous);
    payload.put("status", current);
    payload.put("reason", reason);
    return payload;
  }
}
