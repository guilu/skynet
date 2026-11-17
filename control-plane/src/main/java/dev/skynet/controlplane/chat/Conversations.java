package dev.skynet.controlplane.chat;

import dev.skynet.controlplane.event.EventDraft;
import dev.skynet.controlplane.event.EventStore;
import dev.skynet.controlplane.project.Project;
import dev.skynet.controlplane.project.ProjectService;
import dev.skynet.controlplane.runner.RunnerDirectory;
import dev.skynet.controlplane.shared.Archived;
import dev.skynet.controlplane.shared.ConflictException;
import dev.skynet.controlplane.shared.InvalidRequestException;
import dev.skynet.controlplane.shared.NotFoundException;
import dev.skynet.controlplane.shared.PayloadRedactor;
import dev.skynet.controlplane.shared.TimeSource;
import dev.skynet.controlplane.workflow.ChatAgents;
import dev.skynet.controlplane.workflow.ChatTurnFinished;
import dev.skynet.controlplane.workitem.WorkItem;
import dev.skynet.controlplane.workitem.WorkItemService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Las conversaciones del chat con Skyny (CH-A, docs/chat.md): crearlas, listarlas, leerlas con sus
 * mensajes, escribir en ellas y archivarlas.
 *
 * <p>Cada mensaje de la persona lo contesta un turno de Skyny en un runner (CH-C, {@link
 * ChatAgents}): la conversación queda en {@link ConversationState#ANSWERING} hasta que llega su
 * respuesta. Sin un runner del chat en línea, queda en {@link ConversationState#OFFLINE}. Los
 * textos se guardan con los secretos enmascarados, como la memoria.
 */
@Service
public class Conversations {

  /** El proveedor que contesta (decidido en el estudio de viabilidad: el agente del runner). */
  public static final String PROVIDER = "claude-code";

  /** El modelo por defecto del chat: rápido y barato. */
  public static final String DEFAULT_MODEL = "haiku";

  /** El tope de un mensaje, en caracteres. */
  public static final int MAX_BODY = 8_000;

  /** El tope de un título, en caracteres. */
  public static final int MAX_TITLE = 80;

  /** Cuántas conversaciones devuelve la lista como mucho. */
  public static final int MAX_LIST = 100;

  static final String AGGREGATE = "conversation";

  /** Cuánto espera un turno en cola a que lo reclame un runner antes de darlo por perdido. */
  static final Duration QUEUE_TIMEOUT = Duration.ofMinutes(2);

  private static final String COLUMNS =
      "c.id, c.title, c.project_id, p.key AS project_key, p.name AS project_name, c.work_item_id,"
          + " w.key AS work_item_key, w.title AS work_item_title, c.provider, c.model, c.state,"
          + " c.message_count, c.created_at, c.created_by, c.updated_at, c.archived_at";
  private static final String FROM =
      " FROM conversation c LEFT JOIN project p ON p.id = c.project_id"
          + " LEFT JOIN work_item w ON w.id = c.work_item_id";

  /** Una conversación, sin sus mensajes. */
  public record Conversation(
      UUID id,
      String title,
      UUID projectId,
      String projectKey,
      String projectName,
      UUID workItemId,
      String workItemKey,
      String workItemTitle,
      String provider,
      String model,
      ConversationState state,
      int messageCount,
      Instant createdAt,
      String createdBy,
      Instant updatedAt,
      Instant archivedAt) {}

  /** Un mensaje de una conversación. */
  public record Message(
      UUID id,
      UUID conversationId,
      int seq,
      MessageAuthor author,
      String body,
      List<ChatCard> cards,
      Instant createdAt) {}

  /** Una conversación con todos sus mensajes, en orden. */
  public record Thread(Conversation conversation, List<Message> messages) {}

  /** Lo que deja escribir un mensaje: el mensaje y cómo queda la conversación. */
  public record Posted(Message message, Conversation conversation) {}

  private final JdbcClient jdbc;
  private final ProjectService projects;
  private final WorkItemService workItems;
  private final EventStore events;
  private final PayloadRedactor redactor;
  private final TimeSource time;
  private final ChatAgents chats;
  private final RunnerDirectory runners;
  private final JsonMapper json = JsonMapper.builder().build();

  Conversations(
      JdbcClient jdbc,
      ProjectService projects,
      WorkItemService workItems,
      EventStore events,
      PayloadRedactor redactor,
      TimeSource time,
      ChatAgents chats,
      RunnerDirectory runners) {
    this.jdbc = jdbc;
    this.projects = projects;
    this.workItems = workItems;
    this.events = events;
    this.redactor = redactor;
    this.time = time;
    this.chats = chats;
    this.runners = runners;
  }

  /**
   * Una conversación nueva. Con un trabajo y sin proyecto, el proyecto es el del trabajo. Sin
   * título, lo da el primer mensaje; sin título ni mensaje, «Conversación nueva».
   *
   * @param message primer mensaje de la persona, o {@code null}
   */
  @Transactional
  public Thread create(
      UUID projectId, UUID workItemId, String title, String message, String actor) {
    if (workItemId != null) {
      WorkItem item = workItems.get(workItemId);
      if (projectId != null && !projectId.equals(item.getProjectId())) {
        throw new InvalidRequestException("El trabajo no es de ese proyecto");
      }
      projectId = item.getProjectId();
    }
    if (projectId != null) {
      Project project = projects.get(projectId);
      projects.requireActive(project);
    }
    String text = message == null || message.isBlank() ? null : body(message);
    String name = title(title, text);
    UUID id = UUID.randomUUID();
    Instant now = time.now();
    jdbc.sql(
            "INSERT INTO conversation (id, title, project_id, work_item_id, provider, model, state,"
                + " message_count, created_at, created_by, updated_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?)")
        .params(
            id,
            name,
            projectId,
            workItemId,
            PROVIDER,
            DEFAULT_MODEL,
            ConversationState.IDLE.name(),
            Timestamp.from(now),
            actor,
            Timestamp.from(now))
        .update();
    Map<String, Object> payload = new HashMap<>();
    payload.put("conversationId", id.toString());
    payload.put("title", name);
    if (projectId != null) {
      payload.put("projectId", projectId.toString());
    }
    if (workItemId != null) {
      payload.put("workItemId", workItemId.toString());
    }
    payload.put("actor", actor);
    events.append(EventDraft.of(AGGREGATE, id, "chat.conversation.created", null, payload, now));
    if (text != null) {
      Message first = append(id, MessageAuthor.USER, text, List.of(), now);
      setState(id, answer(get(id), first));
    }
    return thread(id);
  }

  /** Las conversaciones, la de actividad más reciente primero. */
  @Transactional(readOnly = true)
  public List<Conversation> list(UUID projectId, Archived archived, int limit) {
    if (limit < 1 || limit > MAX_LIST) {
      throw new InvalidRequestException("limit debe estar entre 1 y " + MAX_LIST);
    }
    String where = projectId == null ? " WHERE TRUE" : " WHERE c.project_id = :project";
    var query =
        jdbc.sql(
                "SELECT "
                    + COLUMNS
                    + FROM
                    + where
                    + archived.sql("c.archived_at")
                    + " ORDER BY c.updated_at DESC, c.id LIMIT :limit")
            .param("limit", limit);
    if (projectId != null) {
      query = query.param("project", projectId);
    }
    return query.query(this::conversation).list();
  }

  /** Una conversación con sus mensajes. */
  @Transactional(readOnly = true)
  public Thread thread(UUID id) {
    Conversation conversation = get(id);
    List<Message> messages =
        jdbc.sql(
                "SELECT id, conversation_id, seq, author, body, CAST(cards AS text) AS cards,"
                    + " created_at FROM conversation_message WHERE conversation_id = ?"
                    + " ORDER BY seq")
            .param(id)
            .query(this::message)
            .list();
    return new Thread(conversation, messages);
  }

  /** Una conversación, sin sus mensajes. */
  @Transactional(readOnly = true)
  public Conversation get(UUID id) {
    return jdbc.sql("SELECT " + COLUMNS + FROM + " WHERE c.id = ?")
        .param(id)
        .query(this::conversation)
        .optional()
        .orElseThrow(() -> new NotFoundException("Conversación", id));
  }

  /**
   * La persona escribe en una conversación. Mientras Skyny está contestando, no se puede: un
   * mensaje cada vez.
   */
  @Transactional
  public Posted post(UUID id, String body) {
    Conversation conversation = lock(id);
    if (conversation.archivedAt() != null) {
      throw new ConflictException("La conversación está archivada");
    }
    if (conversation.state() == ConversationState.ANSWERING) {
      // Un turno que nadie reclamó a tiempo no bloquea la conversación.
      chats.withdrawStale(id, time.now().minus(QUEUE_TIMEOUT));
      if (chats.answering(id)) {
        throw new ConflictException("Skyny todavía está contestando el mensaje anterior");
      }
      conversation = get(id);
    }
    Message message = append(id, MessageAuthor.USER, body(body), List.of(), time.now());
    setState(id, answer(conversation, message));
    return new Posted(message, get(id));
  }

  /**
   * Para el turno en curso, si lo hay (CH-C). La respuesta, o que no la hubo, llega con el fin del
   * turno.
   */
  @Transactional
  public Conversation stop(UUID id) {
    lock(id);
    chats.cancel(id);
    return get(id);
  }

  /**
   * Pone en cola el turno de Skyny que contesta a {@code message}: continúa la sesión del turno
   * anterior si su runner sigue en línea, o empieza una nueva con lo hablado hasta ahora. Sin
   * runner del chat en línea, nadie contesta.
   */
  private ConversationState answer(Conversation conversation, Message message) {
    Optional<ChatAgents.Previous> previous =
        chats.previous(conversation.id()).filter(p -> runners.chatAvailable(p.runnerId()));
    if (previous.isEmpty() && !runners.chatAvailable()) {
      return ConversationState.OFFLINE;
    }
    String prompt =
        previous.isPresent()
            ? message.body()
            : SkynyPrompt.fresh(
                context(conversation), history(conversation.id(), message.seq()), message.body());
    chats.queue(
        conversation.id(), message.id(), conversation.model(), prompt, previous.orElse(null));
    return ConversationState.ANSWERING;
  }

  /**
   * Llega lo que dijo Skyny en un turno (CH-C): su mensaje, con las acciones que propuso mientras
   * contestaba como tarjetas, y la conversación vuelve a {@link ConversationState#IDLE}.
   */
  @Transactional
  public Message answered(ChatTurnFinished finished) {
    lock(finished.conversationId());
    Instant now = time.now();
    String text;
    if (finished.completed()) {
      text =
          finished.text() == null || finished.text().isBlank()
              ? "No tengo nada que añadir."
              : finished.text().strip();
    } else {
      text =
          "No he podido contestar"
              + (finished.error() == null || finished.error().isBlank()
                  ? "."
                  : ": " + finished.error().strip());
    }
    if (text.length() > MAX_BODY) {
      text = text.substring(0, MAX_BODY - 1).strip() + "…";
    }
    Message message =
        append(
            finished.conversationId(),
            MessageAuthor.SKYNY,
            redactor.redact(text),
            proposals(finished),
            now);
    setState(finished.conversationId(), ConversationState.IDLE);
    return message;
  }

  /** Las acciones que propuso Skyny durante el turno y siguen esperando, como tarjetas. */
  private List<ChatCard> proposals(ChatTurnFinished finished) {
    return jdbc.sql(
            "SELECT a.id, a.summary FROM conversation_action a JOIN chat_turn t"
                + " ON t.conversation_id = a.conversation_id WHERE t.agent_run_id = ?"
                + " AND a.status = 'PENDING' AND a.created_at >= t.created_at"
                + " ORDER BY a.created_at")
        .param(finished.agentRunId())
        .query((rs, n) -> new ChatCard("action", rs.getString("id"), rs.getString("summary"), null))
        .list();
  }

  private static SkynyPrompt.Context context(Conversation c) {
    return new SkynyPrompt.Context(
        c.projectId() == null ? null : c.projectId().toString(),
        c.projectKey(),
        c.projectName(),
        c.workItemId() == null ? null : c.workItemId().toString(),
        c.workItemKey(),
        c.workItemTitle());
  }

  /** Los mensajes anteriores a {@code beforeSeq}, en orden. */
  private List<SkynyPrompt.Line> history(UUID conversationId, int beforeSeq) {
    return jdbc.sql(
            "SELECT author, body FROM conversation_message WHERE conversation_id = ? AND seq < ?"
                + " ORDER BY seq")
        .params(conversationId, beforeSeq)
        .query(
            (rs, n) ->
                new SkynyPrompt.Line(
                    MessageAuthor.valueOf(rs.getString("author")), rs.getString("body")))
        .list();
  }

  /** Archiva una conversación: sale de la lista y ya no se puede escribir en ella. */
  @Transactional
  public Conversation archive(UUID id, String actor) {
    Conversation conversation = lock(id);
    if (conversation.archivedAt() != null) {
      return conversation;
    }
    Instant now = time.now();
    jdbc.sql("UPDATE conversation SET archived_at = ? WHERE id = ?")
        .params(Timestamp.from(now), id)
        .update();
    events.append(
        EventDraft.of(
            AGGREGATE,
            id,
            "chat.conversation.archived",
            null,
            Map.of("conversationId", id.toString(), "actor", actor),
            now));
    // Lo que quedó sin resumir va a la memoria del proyecto (CH-F).
    summarize(id);
    return get(id);
  }

  /**
   * Pone en cola el turno que resume la conversación para la memoria de su proyecto (CH-F), si hace
   * falta: tiene proyecto, nadie está contestando y Skyny ha dicho algo desde el último resumen.
   * Sin runner del chat en línea, lo deja para otra vez. Mientras se resume, la conversación está
   * en {@link ConversationState#ANSWERING}, como con cualquier turno.
   *
   * @return si lo puso en cola
   */
  @Transactional
  public boolean summarize(UUID id) {
    Conversation conversation = lock(id);
    if (conversation.projectId() == null
        || conversation.state() == ConversationState.ANSWERING
        || chats.answering(id)) {
      return false;
    }
    int summarized =
        jdbc.sql("SELECT summarized_count FROM conversation WHERE id = ?")
            .param(id)
            .query(Integer.class)
            .single();
    boolean news =
        jdbc.sql(
                "SELECT EXISTS (SELECT 1 FROM conversation_message WHERE conversation_id = ?"
                    + " AND author = 'SKYNY' AND seq > ?)")
            .params(id, summarized)
            .query(Boolean.class)
            .single();
    if (!news || !runners.chatAvailable()) {
      return false;
    }
    List<SkynyPrompt.Line> lines = history(id, Integer.MAX_VALUE);
    UUID last =
        jdbc.sql(
                "SELECT id FROM conversation_message WHERE conversation_id = ?"
                    + " ORDER BY seq DESC LIMIT 1")
            .param(id)
            .query(UUID.class)
            .single();
    jdbc.sql("UPDATE conversation SET summarized_count = message_count WHERE id = ?")
        .param(id)
        .update();
    chats.queueSummary(
        id,
        last,
        conversation.model(),
        ChatSummary.prompt(
            conversation.projectKey(), conversation.projectName(), conversation.title(), lines));
    setState(id, ConversationState.ANSWERING);
    return true;
  }

  /**
   * Las conversaciones con proyecto, sin archivar y en reposo, que llevan desde antes de {@code
   * before} sin mensajes y tienen algo sin resumir: las que toca resumir (CH-F).
   */
  @Transactional(readOnly = true)
  public List<UUID> idleToSummarize(Instant before, int limit) {
    return jdbc.sql(
            "SELECT c.id FROM conversation c WHERE c.project_id IS NOT NULL"
                + " AND c.archived_at IS NULL AND c.state = 'IDLE' AND c.updated_at < ?"
                + " AND EXISTS (SELECT 1 FROM conversation_message m WHERE m.conversation_id = c.id"
                + " AND m.author = 'SKYNY' AND m.seq > c.summarized_count)"
                + " ORDER BY c.updated_at LIMIT ?")
        .params(Timestamp.from(before), limit)
        .query(UUID.class)
        .list();
  }

  /**
   * El turno de resumen ha terminado (CH-F): la conversación vuelve a {@link
   * ConversationState#IDLE} y queda dicho qué pasó con el resumen.
   *
   * @param observationId la observación guardada, o {@code null} si no se guardó
   * @param outcome {@code saved}, {@code nothing} (no había nada que guardar) o {@code failed}
   */
  @Transactional
  public void summarized(UUID id, UUID agentRunId, UUID observationId, String outcome) {
    lock(id);
    setState(id, ConversationState.IDLE);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("conversationId", id.toString());
    payload.put("agentRunId", agentRunId.toString());
    payload.put("outcome", outcome);
    if (observationId != null) {
      payload.put("observationId", observationId.toString());
    }
    events.append(
        EventDraft.of(AGGREGATE, id, "chat.conversation.summarized", null, payload, time.now()));
  }

  /** Añade un mensaje al final de la conversación y lo anuncia. */
  private Message append(
      UUID conversationId, MessageAuthor author, String body, List<ChatCard> cards, Instant now) {
    int seq =
        jdbc.sql(
                "UPDATE conversation SET message_count = message_count + 1, updated_at = ?"
                    + " WHERE id = ? RETURNING message_count")
            .params(Timestamp.from(now), conversationId)
            .query(Integer.class)
            .single();
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO conversation_message (id, conversation_id, seq, author, body, cards,"
                + " created_at) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)")
        .params(
            id,
            conversationId,
            seq,
            author.name(),
            body,
            json.writeValueAsString(cards),
            Timestamp.from(now))
        .update();
    events.append(
        EventDraft.of(
            AGGREGATE,
            conversationId,
            "chat.message.created",
            null,
            Map.of(
                "conversationId",
                conversationId.toString(),
                "messageId",
                id.toString(),
                "seq",
                seq,
                "author",
                author.name()),
            now));
    return new Message(id, conversationId, seq, author, body, cards, now);
  }

  private void setState(UUID id, ConversationState state) {
    jdbc.sql("UPDATE conversation SET state = ? WHERE id = ?").params(state.name(), id).update();
  }

  /**
   * La conversación, bloqueada hasta el final de la transacción: los mensajes van de uno en uno.
   */
  private Conversation lock(UUID id) {
    boolean exists =
        jdbc.sql("SELECT id FROM conversation WHERE id = ? FOR UPDATE")
            .param(id)
            .query(UUID.class)
            .optional()
            .isPresent();
    if (!exists) {
      throw new NotFoundException("Conversación", id);
    }
    return get(id);
  }

  private String body(String text) {
    if (text == null || text.isBlank()) {
      throw new InvalidRequestException("El mensaje no puede estar vacío");
    }
    String stripped = text.strip();
    if (stripped.length() > MAX_BODY) {
      throw new InvalidRequestException(
          "El mensaje es demasiado largo: como mucho " + MAX_BODY + " caracteres");
    }
    return redactor.redact(stripped);
  }

  /** El título: el que se pide, o la primera línea del primer mensaje, recortados. */
  static String title(String title, String firstMessage) {
    String source =
        title != null && !title.isBlank()
            ? title
            : firstMessage != null ? firstMessage : "Conversación nueva";
    String line = source.strip().lines().findFirst().orElse("").strip().replaceAll("\\s+", " ");
    if (line.isEmpty()) {
      line = "Conversación nueva";
    }
    return line.length() <= MAX_TITLE ? line : line.substring(0, MAX_TITLE - 1).strip() + "…";
  }

  private Conversation conversation(ResultSet rs, int n) throws SQLException {
    return new Conversation(
        rs.getObject("id", UUID.class),
        rs.getString("title"),
        rs.getObject("project_id", UUID.class),
        rs.getString("project_key"),
        rs.getString("project_name"),
        rs.getObject("work_item_id", UUID.class),
        rs.getString("work_item_key"),
        rs.getString("work_item_title"),
        rs.getString("provider"),
        rs.getString("model"),
        ConversationState.valueOf(rs.getString("state")),
        rs.getInt("message_count"),
        instant(rs, "created_at"),
        rs.getString("created_by"),
        instant(rs, "updated_at"),
        instant(rs, "archived_at"));
  }

  private Message message(ResultSet rs, int n) throws SQLException {
    return new Message(
        rs.getObject("id", UUID.class),
        rs.getObject("conversation_id", UUID.class),
        rs.getInt("seq"),
        MessageAuthor.valueOf(rs.getString("author")),
        rs.getString("body"),
        json.readValue(rs.getString("cards"), new TypeReference<List<ChatCard>>() {}),
        instant(rs, "created_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}
