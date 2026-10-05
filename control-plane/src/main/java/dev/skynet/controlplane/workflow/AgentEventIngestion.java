package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.event.EventDraft;
import dev.skynet.controlplane.event.EventStore;
import dev.skynet.controlplane.shared.PayloadRedactor;
import dev.skynet.controlplane.shared.TimeSource;
import dev.skynet.protocol.AgentObservableStatus;
import dev.skynet.protocol.NormalizedEvent;
import dev.skynet.protocol.StageStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registra los eventos que envía un runner y aplica sus efectos sobre el agente, su fase y la
 * ejecución (estado observable, sesión, tokens, coste y resultado final).
 *
 * <p>Cada evento se procesa en su propia transacción y es idempotente por {@code eventId}: un
 * evento repetido no vuelve a aplicar sus efectos. Un evento cuya transición ya no es posible (p.
 * ej. tras una cancelación) se registra igualmente, sin efectos.
 */
@Service
public class AgentEventIngestion {

  /** Resultado de ingerir un evento. */
  public enum Outcome {
    ACCEPTED,
    DUPLICATE,
    UNKNOWN_AGENT_RUN,
    NOT_ASSIGNED_TO_RUNNER
  }

  private final AgentRunRepository agentRuns;
  private final StageRunRepository stageRuns;
  private final WorkflowRunRepository workflowRuns;
  private final RunTransitions transitions;
  private final EventStore events;
  private final PayloadRedactor redactor;
  private final TimeSource time;

  AgentEventIngestion(
      AgentRunRepository agentRuns,
      StageRunRepository stageRuns,
      WorkflowRunRepository workflowRuns,
      RunTransitions transitions,
      EventStore events,
      PayloadRedactor redactor,
      TimeSource time) {
    this.agentRuns = agentRuns;
    this.stageRuns = stageRuns;
    this.workflowRuns = workflowRuns;
    this.transitions = transitions;
    this.events = events;
    this.redactor = redactor;
    this.time = time;
  }

  @Transactional
  public Outcome ingest(UUID runnerId, NormalizedEvent event) {
    AgentRun agent = agentRuns.findById(event.agentRunId()).orElse(null);
    if (agent == null) {
      return Outcome.UNKNOWN_AGENT_RUN;
    }
    if (!runnerId.equals(agent.getRunnerId())) {
      return Outcome.NOT_ASSIGNED_TO_RUNNER;
    }
    StageRun stage = stageRuns.findById(agent.getStageRunId()).orElseThrow();
    // Los secretos se ocultan antes de guardar nada: ni el event store ni agent_run los ven.
    Map<String, Object> redacted = redactor.redact(event.payload());
    Map<String, Object> payload = new LinkedHashMap<>(redacted);
    payload.put("runnerSeq", event.seq());
    boolean isNew =
        events
            .appendIfNew(
                new EventDraft(
                    "agent_run",
                    agent.getId(),
                    event.type().wireName(),
                    stage.getWorkflowRunId(),
                    payload,
                    event.eventId().toString(),
                    event.occurredAt()))
            .isPresent();
    if (!isNew) {
      return Outcome.DUPLICATE;
    }
    if (!agent.getStatus().isTerminal()) {
      apply(agent, stage, event, redacted);
    }
    return Outcome.ACCEPTED;
  }

  private void apply(AgentRun agent, StageRun stage, NormalizedEvent event, Map<String, Object> p) {
    Instant now = time.now();
    UUID runId = stage.getWorkflowRunId();
    agent.touch(event.occurredAt());
    switch (event.type()) {
      case SESSION_STARTED -> {
        agent.startSession(string(p, "sessionId"), string(p, "model"), event.occurredAt());
        agent = active(agent, stage, AgentObservableStatus.THINKING, "session-started", now);
      }
      case MESSAGE_RECEIVED, TOOL_COMPLETED ->
          agent = active(agent, stage, AgentObservableStatus.THINKING, "agent-thinking", now);
      case TOOL_STARTED ->
          agent = active(agent, stage, AgentObservableStatus.EXECUTING, "tool-started", now);
      case RESULT ->
          agent.recordResult(
              string(p, "subtype"),
              Boolean.TRUE.equals(p.get("isError")),
              integer(p.get("numTurns")),
              longValue(usage(p).get("input")),
              longValue(usage(p).get("output")),
              decimal(p.get("costUsdCumulative")),
              previousCumulativeCost(agent),
              errors(p));
      case PROCESS_EXITED -> {
        AgentObservableStatus outcome =
            agent.exited(integer(p.get("exitCode")), string(p, "signal"), string(p, "error"));
        agent = transitions.advanceAgent(agent, outcome, runId, "process-exited", now);
        transitions.finishAdhoc(stage, workflowRuns.findById(runId).orElseThrow(), outcome, now);
      }
      default -> {}
    }
    // Actividad, sesión, resultado o código de salida aunque no haya habido transición.
    agentRuns.save(agent);
  }

  /** Pasa el agente a un estado activo y, con él, su fase a {@code RUNNING}. */
  private AgentRun active(
      AgentRun agent, StageRun stage, AgentObservableStatus next, String reason, Instant now) {
    agent = transitions.advanceAgent(agent, next, stage.getWorkflowRunId(), reason, now);
    transitions.advanceStage(stage, StageStatus.RUNNING, "agent-active", now);
    return agent;
  }

  /** Coste acumulado de la invocación anterior en el linaje de la sesión (reanudar, fork: M4). */
  private BigDecimal previousCumulativeCost(AgentRun agent) {
    if (agent.getParentAgentRunId() == null) {
      return null;
    }
    return agentRuns
        .findById(agent.getParentAgentRunId())
        .map(AgentRun::getCostUsdCumulative)
        .orElse(null);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> usage(Map<String, Object> p) {
    return p.get("usage") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
  }

  private static String errors(Map<String, Object> p) {
    if (p.get("errors") instanceof List<?> list && !list.isEmpty()) {
      return String.join("; ", list.stream().map(String::valueOf).toList());
    }
    return string(p, "subtype");
  }

  private static String string(Map<String, Object> p, String key) {
    Object value = p.get(key);
    return value == null ? null : value.toString();
  }

  private static Integer integer(Object value) {
    return value instanceof Number n ? n.intValue() : null;
  }

  private static Long longValue(Object value) {
    return value instanceof Number n ? n.longValue() : null;
  }

  private static BigDecimal decimal(Object value) {
    return switch (value) {
      case BigDecimal d -> d;
      case Number n -> new BigDecimal(n.toString());
      case String s -> new BigDecimal(s);
      case null, default -> null;
    };
  }
}
