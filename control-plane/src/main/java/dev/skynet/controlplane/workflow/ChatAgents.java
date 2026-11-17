package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.shared.TimeSource;
import dev.skynet.protocol.AgentEventType;
import dev.skynet.protocol.AgentObservableStatus;
import dev.skynet.protocol.SafeIds;
import dev.skynet.protocol.runner.AgentLimits;
import dev.skynet.protocol.runner.ChatTurn;
import dev.skynet.protocol.runner.ResumeFrom;
import dev.skynet.protocol.runner.StartAgent;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Los turnos del chat con Skyny (CH-C, docs/chat.md «El turno de Skyny»): cada mensaje lo contesta
 * un agente de Claude Code en un runner, con su invocación en {@code agent_run} como las de los
 * workflows, pero sin fase, ejecución ni repositorio ({@link AgentRun#ROLE_CHAT}).
 *
 * <p>El primer turno de una conversación empieza una sesión; los siguientes la continúan en el
 * mismo runner ({@code RESUME}). El agente solo tiene las herramientas del MCP de Skynet. Al
 * terminar, {@link ChatTurnFinished} lleva lo que dijo a la conversación.
 */
@Service
public class ChatAgents {

  /** Lo que puede gastar un turno: de sobra para consultar y proponer. */
  public static final AgentLimits LIMITS =
      new AgentLimits(30, new BigDecimal("0.50"), Duration.ofMinutes(5));

  /** Sus herramientas: las del MCP de Skynet, y nada más. */
  public static final List<String> ALLOWED_TOOLS = List.of("mcp__skynet");

  /** Un turno que contesta a un mensaje de la persona. */
  static final String PURPOSE_ANSWER = "answer";

  /** Un turno que resume la conversación para la memoria del proyecto (CH-F). */
  static final String PURPOSE_SUMMARY = "summary";

  /** El turno anterior de una conversación, del que puede continuar el siguiente. */
  public record Previous(UUID agentRunId, UUID runnerId, String sessionId) {}

  private final AgentRunRepository agentRuns;
  private final PromptRepository prompts;
  private final RunTransitions transitions;
  private final ApplicationEventPublisher publisher;
  private final JdbcClient jdbc;
  private final TimeSource time;

  ChatAgents(
      AgentRunRepository agentRuns,
      PromptRepository prompts,
      RunTransitions transitions,
      ApplicationEventPublisher publisher,
      JdbcClient jdbc,
      TimeSource time) {
    this.agentRuns = agentRuns;
    this.prompts = prompts;
    this.transitions = transitions;
    this.publisher = publisher;
    this.jdbc = jdbc;
    this.time = time;
  }

  /** El último turno de la conversación que terminó bien con su sesión, si lo hay. */
  @Transactional(readOnly = true)
  public Optional<Previous> previous(UUID conversationId) {
    return jdbc.sql(
            "SELECT a.id, a.runner_id, a.provider_session_id FROM chat_turn t"
                + " JOIN agent_run a ON a.id = t.agent_run_id WHERE t.conversation_id = ?"
                + " AND t.purpose = 'answer'"
                + " AND a.status = 'COMPLETED' AND a.provider_session_id IS NOT NULL"
                + " AND a.runner_id IS NOT NULL ORDER BY t.created_at DESC LIMIT 1")
        .param(conversationId)
        .query(
            (rs, n) ->
                new Previous(
                    rs.getObject("id", UUID.class),
                    rs.getObject("runner_id", UUID.class),
                    rs.getString("provider_session_id")))
        .optional()
        // Solo una sesión con un id que se pueda continuar (como «Continuar» de un agente).
        .filter(p -> isUuid(p.sessionId()));
  }

  private static boolean isUuid(String value) {
    try {
      UUID.fromString(value);
      return SafeIds.isSessionId(value);
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /** Si la conversación tiene un turno que aún no ha terminado. */
  @Transactional(readOnly = true)
  public boolean answering(UUID conversationId) {
    return jdbc.sql(
                "SELECT count(*) FROM chat_turn t JOIN agent_run a ON a.id = t.agent_run_id"
                    + " WHERE t.conversation_id = ? AND a.status NOT IN"
                    + " ('COMPLETED', 'FAILED', 'CANCELLED')")
            .param(conversationId)
            .query(Integer.class)
            .single()
        > 0;
  }

  /**
   * Pone en cola el turno que contesta a {@code messageId}. Con {@code previous}, continúa su
   * sesión en su runner; sin él, empieza una nueva en el primer runner del chat que lo reclame.
   *
   * @return el turno
   */
  @Transactional
  public UUID queue(
      UUID conversationId, UUID messageId, String model, String prompt, Previous previous) {
    return queue(conversationId, messageId, model, prompt, previous, PURPOSE_ANSWER);
  }

  /**
   * Pone en cola el turno que resume la conversación hasta {@code lastMessageId} para la memoria
   * (CH-F): una sesión nueva, con lo hablado en el prompt, que no continúa ningún turno.
   */
  @Transactional
  public UUID queueSummary(UUID conversationId, UUID lastMessageId, String model, String prompt) {
    return queue(conversationId, lastMessageId, model, prompt, null, PURPOSE_SUMMARY);
  }

  private UUID queue(
      UUID conversationId,
      UUID messageId,
      String model,
      String prompt,
      Previous previous,
      String purpose) {
    Instant now = time.now();
    AgentRun parent =
        previous == null ? null : agentRuns.findById(previous.agentRunId()).orElse(null);
    UUID sessionId = parent == null ? UUID.randomUUID() : UUID.fromString(previous.sessionId());
    AgentRun turn =
        agentRuns.save(
            AgentRun.chatTurn(parent, AgentRun.PROVIDER_CLAUDE_CODE, sessionId, LIMITS, now));
    Prompt saved = prompts.save(Prompt.of(turn.getId(), Prompt.ROLE_USER, prompt, now));
    jdbc.sql(
            "INSERT INTO chat_turn (agent_run_id, conversation_id, message_id, purpose,"
                + " created_at) VALUES (?, ?, ?, ?, ?)")
        .params(turn.getId(), conversationId, messageId, purpose, Timestamp.from(now))
        .update();
    ResumeFrom resume =
        parent == null
            ? null
            : new ResumeFrom(previous.sessionId(), false, ChatTurn.WORKSPACE, ChatTurn.WORKSPACE);
    Map<String, Object> spawned = new LinkedHashMap<>();
    spawned.put("conversationId", conversationId);
    spawned.put("messageId", messageId);
    spawned.put("role", AgentRun.ROLE_CHAT);
    spawned.put("purpose", purpose);
    spawned.put("provider", turn.getProvider());
    spawned.put("kind", turn.getKind());
    spawned.put("status", turn.getStatus());
    spawned.put("promptId", saved.getId());
    spawned.put("promptSha256", saved.getSha256());
    spawned.put("allowedTools", ALLOWED_TOOLS);
    spawned.put("model", model);
    spawned.put(
        "limits",
        Map.of(
            "maxTurns", LIMITS.maxTurns(),
            "maxBudgetUsd", LIMITS.maxBudgetUsd(),
            "timeoutMinutes", LIMITS.timeout().toMinutes()));
    if (parent != null) {
      spawned.put("parentAgentRunId", parent.getId());
    }
    transitions.append("agent_run", turn.getId(), "agent.spawned", null, spawned, now);
    publisher.publishEvent(
        new AgentRunQueued(
            turn.getId(),
            StartAgent.chatTurn(
                new ChatTurn(conversationId),
                sessionId,
                prompt,
                ALLOWED_TOOLS,
                model,
                LIMITS,
                resume),
            parent == null ? null : previous.runnerId()));
    return turn.getId();
  }

  /**
   * La conversación del turno {@code agentRunId} si es un turno del chat de {@code runnerId} que
   * aún no ha terminado.
   */
  @Transactional(readOnly = true)
  public Optional<UUID> activeTurn(UUID runnerId, UUID agentRunId) {
    return jdbc.sql(
            "SELECT t.conversation_id FROM chat_turn t JOIN agent_run a ON a.id = t.agent_run_id"
                + " WHERE a.id = ? AND a.runner_id = ? AND a.status NOT IN"
                + " ('COMPLETED', 'FAILED', 'CANCELLED')")
        .params(agentRunId, runnerId)
        .query(UUID.class)
        .optional();
  }

  /**
   * Para el turno en curso de la conversación, si lo hay: en cola se cancela en el acto; en un
   * runner, se le ordena terminar y el fin llega con el proceso. Devuelve si había alguno.
   */
  @Transactional
  public boolean cancel(UUID conversationId) {
    Optional<UUID> active =
        jdbc.sql(
                "SELECT a.id FROM chat_turn t JOIN agent_run a ON a.id = t.agent_run_id"
                    + " WHERE t.conversation_id = ? AND a.status NOT IN"
                    + " ('COMPLETED', 'FAILED', 'CANCELLED') ORDER BY t.created_at DESC LIMIT 1")
            .param(conversationId)
            .query(UUID.class)
            .optional();
    active.ifPresent(id -> cancelTurn(agentRuns.findById(id).orElseThrow(), "cancelled-by-user"));
    return active.isPresent();
  }

  /**
   * Retira el turno de la conversación que lleva en cola desde antes de {@code before} sin que lo
   * reclame ningún runner: se queda sin contestar y la conversación no se bloquea.
   */
  @Transactional
  public boolean withdrawStale(UUID conversationId, Instant before) {
    List<UUID> stale =
        jdbc.sql(
                "SELECT a.id FROM chat_turn t JOIN agent_run a ON a.id = t.agent_run_id"
                    + " WHERE t.conversation_id = ? AND a.status = 'QUEUED' AND a.created_at < ?")
            .params(conversationId, Timestamp.from(before))
            .query(UUID.class)
            .list();
    stale.forEach(id -> cancelTurn(agentRuns.findById(id).orElseThrow(), "no-runner"));
    return !stale.isEmpty();
  }

  /** Cancela un turno del chat (lo llama también «Cancelar» de un agente). */
  AgentRun cancelTurn(AgentRun turn, String reason) {
    Instant now = time.now();
    if (turn.getStatus() == AgentObservableStatus.QUEUED) {
      AgentRun cancelled =
          transitions.agent(turn, AgentObservableStatus.CANCELLED, null, reason, now);
      publisher.publishEvent(new AgentRunWithdrawn(cancelled.getId()));
      finished(
          cancelled,
          "no-runner".equals(reason)
              ? "ningún runner del chat lo recogió a tiempo"
              : "lo paraste antes de que empezara");
      return cancelled;
    }
    if (turn.getStatus().isTerminal()) {
      return turn;
    }
    if (turn.requestCancel(now)) {
      turn = agentRuns.save(turn);
      transitions.append(
          "agent_run",
          turn.getId(),
          "agent.cancel.requested",
          null,
          Map.of("runnerId", turn.getRunnerId()),
          now);
      publisher.publishEvent(new AgentCancelRequested(turn.getId(), turn.getRunnerId()));
    }
    return turn;
  }

  /** La conversación de un turno. */
  @Transactional(readOnly = true)
  public Optional<UUID> conversationOf(UUID agentRunId) {
    return jdbc.sql("SELECT conversation_id FROM chat_turn WHERE agent_run_id = ?")
        .param(agentRunId)
        .query(UUID.class)
        .optional();
  }

  /**
   * El turno ha terminado: avisa a la conversación con lo último que dijo el agente (su {@code
   * agent.result}). Lo llama quien lo cierra, en su transacción.
   */
  void finished(AgentRun turn) {
    finished(
        turn, turn.getStatus() == AgentObservableStatus.CANCELLED ? "lo paraste" : turn.getError());
  }

  private void finished(AgentRun turn, String error) {
    boolean completed = turn.getStatus() == AgentObservableStatus.COMPLETED;
    jdbc.sql("SELECT conversation_id, message_id, purpose FROM chat_turn WHERE agent_run_id = ?")
        .param(turn.getId())
        .query(
            (rs, n) ->
                new ChatTurnFinished(
                    turn.getId(),
                    rs.getObject("conversation_id", UUID.class),
                    rs.getObject("message_id", UUID.class),
                    completed,
                    resultText(turn.getId()),
                    completed ? null : error,
                    PURPOSE_SUMMARY.equals(rs.getString("purpose"))))
        .optional()
        .ifPresent(publisher::publishEvent);
  }

  private String resultText(UUID agentRunId) {
    return jdbc.sql(
            "SELECT payload->>'result' FROM event WHERE aggregate_type = 'agent_run'"
                + " AND aggregate_id = ? AND event_type = ? ORDER BY sequence DESC LIMIT 1")
        .params(agentRunId, AgentEventType.RESULT.wireName())
        .query(String.class)
        .optional()
        .orElse(null);
  }
}
