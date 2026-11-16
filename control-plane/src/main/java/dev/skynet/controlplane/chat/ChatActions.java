package dev.skynet.controlplane.chat;

import dev.skynet.controlplane.definition.PublishedDefinition;
import dev.skynet.controlplane.definition.WorkflowDefinitions;
import dev.skynet.controlplane.event.EventDraft;
import dev.skynet.controlplane.event.EventStore;
import dev.skynet.controlplane.memory.MemoryContent;
import dev.skynet.controlplane.memory.MemoryKind;
import dev.skynet.controlplane.memory.MemorySaved;
import dev.skynet.controlplane.memory.MemoryService;
import dev.skynet.controlplane.memory.MemorySource;
import dev.skynet.controlplane.project.Project;
import dev.skynet.controlplane.project.ProjectService;
import dev.skynet.controlplane.shared.ConflictException;
import dev.skynet.controlplane.shared.InvalidRequestException;
import dev.skynet.controlplane.shared.NotFoundException;
import dev.skynet.controlplane.shared.TimeSource;
import dev.skynet.controlplane.workflow.ApprovalView;
import dev.skynet.controlplane.workflow.Approvals;
import dev.skynet.controlplane.workflow.RunService;
import dev.skynet.controlplane.workflow.RunView;
import dev.skynet.controlplane.workitem.WorkItem;
import dev.skynet.controlplane.workitem.WorkItemService;
import dev.skynet.controlplane.workitem.WorkItemType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Las acciones por confirmar del chat con Skyny (CH-B, docs/chat.md «Acciones por confirmar»).
 *
 * <p>Skyny solo las <b>propone</b>, con el token de la conversación: se comprueba lo que pide y
 * queda pendiente. La persona las confirma o las rechaza desde la web, con su sesión; al confirmar
 * se hacen con el servicio de siempre, a nombre de «{@code <persona> vía chat}», y cada paso deja
 * un evento {@code chat.action.*} con la conversación. Skyny no puede confirmar nada: su token no
 * llega a estas rutas.
 */
@Service
public class ChatActions {

  /** Cuánto espera una propuesta a que la confirmen. */
  public static final Duration EXPIRES_AFTER = Duration.ofHours(1);

  /** Cuántas propuestas pendientes puede tener una conversación a la vez. */
  public static final int MAX_PENDING = 5;

  /** El sufijo del actor de lo que se hace desde el chat, para la auditoría. */
  public static final String VIA_CHAT = " vía chat";

  private static final int MAX_TEXT = 4_000;

  private static final TypeReference<LinkedHashMap<String, Object>> INPUTS =
      new TypeReference<>() {};

  /** Lo que Skyny puede proponer. */
  public enum Kind {
    LAUNCH,
    CREATE_WORK_ITEM,
    CANCEL_RUN,
    RETRY_STAGE,
    DECIDE_APPROVAL,
    SAVE_MEMORY
  }

  public enum Status {
    PENDING,
    CONFIRMED,
    REJECTED,
    EXPIRED,
    FAILED
  }

  /** Una acción, como la ven la web y Skyny. */
  public record Action(
      UUID id,
      UUID conversationId,
      Kind kind,
      JsonNode input,
      String summary,
      Status status,
      ChatCard result,
      String error,
      Instant createdAt,
      Instant decidedAt,
      String decidedBy) {}

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;
  private final ProjectService projects;
  private final WorkItemService workItems;
  private final WorkflowDefinitions definitions;
  private final RunService runs;
  private final Approvals approvals;
  private final MemoryService memory;
  private final EventStore events;
  private final TimeSource time;
  private final JsonMapper json = JsonMapper.builder().build();

  ChatActions(
      JdbcClient jdbc,
      PlatformTransactionManager transactionManager,
      ProjectService projects,
      WorkItemService workItems,
      WorkflowDefinitions definitions,
      RunService runs,
      Approvals approvals,
      MemoryService memory,
      EventStore events,
      TimeSource time) {
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(transactionManager);
    this.projects = projects;
    this.workItems = workItems;
    this.definitions = definitions;
    this.runs = runs;
    this.approvals = approvals;
    this.memory = memory;
    this.events = events;
    this.time = time;
  }

  /**
   * Skyny propone una acción en la conversación. Se comprueba que lo que pide existe y tiene
   * sentido; si no, 400 o 404 con el motivo, para que Skyny lo corrija.
   */
  @Transactional
  public Action propose(UUID conversationId, Kind kind, JsonNode input) {
    if (kind == null) {
      throw new InvalidRequestException("Falta el tipo de acción");
    }
    ObjectNode args =
        input != null && input.isObject() ? (ObjectNode) input : json.createObjectNode();
    expire(conversationId);
    int pending =
        jdbc.sql(
                "SELECT count(*) FROM conversation_action WHERE conversation_id = ?"
                    + " AND status = 'PENDING'")
            .param(conversationId)
            .query(Integer.class)
            .single();
    if (pending >= MAX_PENDING) {
      throw new ConflictException(
          "Ya hay " + MAX_PENDING + " acciones esperando a que las confirmen en esta conversación");
    }
    Checked checked = check(kind, args);
    UUID id = UUID.randomUUID();
    Instant now = time.now();
    jdbc.sql(
            "INSERT INTO conversation_action (id, conversation_id, kind, input, summary, status,"
                + " created_at) VALUES (?, ?, ?, ?::jsonb, ?, 'PENDING', ?)")
        .params(
            id,
            conversationId,
            kind.name(),
            json.writeValueAsString(checked.input()),
            checked.summary(),
            Timestamp.from(now))
        .update();
    event(
        conversationId,
        id,
        kind,
        "chat.action.proposed",
        Map.of("summary", checked.summary()),
        now);
    return get(conversationId, id);
  }

  /** Las acciones de la conversación, de la más antigua a la más nueva. */
  @Transactional
  public List<Action> list(UUID conversationId) {
    expire(conversationId);
    return jdbc.sql(
            "SELECT * FROM conversation_action WHERE conversation_id = ? ORDER BY created_at, id")
        .param(conversationId)
        .query(this::action)
        .list();
  }

  /** Una acción de la conversación. */
  @Transactional(readOnly = true)
  public Action get(UUID conversationId, UUID id) {
    return jdbc.sql("SELECT * FROM conversation_action WHERE id = ? AND conversation_id = ?")
        .params(id, conversationId)
        .query(this::action)
        .optional()
        .orElseThrow(() -> new NotFoundException("Acción", id));
  }

  /**
   * La persona la confirma: se hace con el servicio de siempre. Si el servicio la rechaza (por
   * ejemplo, la ejecución ya terminó), queda {@link Status#FAILED} con el motivo; no se reintenta.
   */
  public Action confirm(UUID conversationId, UUID id, String actor) {
    String by = actor + VIA_CHAT;
    Action action = transactions.execute(status -> claim(conversationId, id, by));
    ChatCard card;
    try {
      card = transactions.execute(status -> execute(action, by));
    } catch (RuntimeException e) {
      String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      transactions.executeWithoutResult(
          status -> {
            jdbc.sql("UPDATE conversation_action SET status = 'FAILED', error = ? WHERE id = ?")
                .params(error, id)
                .update();
            event(
                conversationId,
                id,
                action.kind(),
                "chat.action.failed",
                Map.of("actor", by, "error", error),
                time.now());
          });
      return get(conversationId, id);
    }
    transactions.executeWithoutResult(
        status -> {
          jdbc.sql("UPDATE conversation_action SET result = ?::jsonb WHERE id = ?")
              .params(json.writeValueAsString(card), id)
              .update();
          Map<String, Object> payload = new LinkedHashMap<>();
          payload.put("actor", by);
          payload.put("result", Map.of("kind", card.kind(), "id", card.id()));
          event(conversationId, id, action.kind(), "chat.action.confirmed", payload, time.now());
        });
    return get(conversationId, id);
  }

  /** La persona la rechaza: no se hace nada. */
  @Transactional
  public Action reject(UUID conversationId, UUID id, String actor) {
    String by = actor + VIA_CHAT;
    Action action = decide(conversationId, id, Status.REJECTED, by);
    event(
        conversationId, id, action.kind(), "chat.action.rejected", Map.of("actor", by), time.now());
    return get(conversationId, id);
  }

  /** La pasa de pendiente a confirmada, o explica por qué no puede. */
  private Action claim(UUID conversationId, UUID id, String by) {
    Boolean archived =
        jdbc.sql("SELECT archived_at IS NOT NULL FROM conversation WHERE id = ?")
            .param(conversationId)
            .query(Boolean.class)
            .optional()
            .orElseThrow(() -> new NotFoundException("Conversación", conversationId));
    if (archived) {
      throw new ConflictException("La conversación está archivada");
    }
    return decide(conversationId, id, Status.CONFIRMED, by);
  }

  private Action decide(UUID conversationId, UUID id, Status to, String by) {
    expire(conversationId);
    Action current = get(conversationId, id);
    if (current.status() != Status.PENDING) {
      throw new ConflictException(
          "La acción ya no espera confirmación: está " + describe(current.status()));
    }
    jdbc.sql(
            "UPDATE conversation_action SET status = ?, decided_at = ?, decided_by = ?"
                + " WHERE id = ? AND status = 'PENDING'")
        .params(to.name(), Timestamp.from(time.now()), by, id)
        .update();
    return current;
  }

  /** Caduca las pendientes de la conversación que nadie confirmó a tiempo. */
  private void expire(UUID conversationId) {
    jdbc.sql(
            "UPDATE conversation_action SET status = 'EXPIRED' WHERE conversation_id = ?"
                + " AND status = 'PENDING' AND created_at < ?")
        .params(conversationId, Timestamp.from(time.now().minus(EXPIRES_AFTER)))
        .update();
  }

  private record Checked(ObjectNode input, String summary) {}

  /** Comprueba lo que pide Skyny y lo deja listo para hacerlo, con su resumen para la tarjeta. */
  private Checked check(Kind kind, ObjectNode args) {
    ObjectNode input = json.createObjectNode();
    return switch (kind) {
      case LAUNCH -> {
        WorkItem item = workItems.get(uuid(args, "workItemId"));
        String key = text(args, "workflow", true);
        PublishedDefinition definition = definitions.latestLaunchable(key);
        UUID repository = optionalUuid(args, "repositoryId");
        if (repository == null) {
          repository = item.getRepositoryId();
        }
        if (repository == null) {
          throw new InvalidRequestException(
              "El trabajo " + item.getKey() + " no tiene repositorio: di en cuál se lanza");
        }
        if (!item.getProjectId().equals(projects.getRepository(repository).getProjectId())) {
          throw new InvalidRequestException("El repositorio no es del proyecto del trabajo");
        }
        input.put("workItemId", item.getId().toString());
        input.put("workflow", definition.key());
        input.put("definitionId", definition.id().toString());
        input.put("repositoryId", repository.toString());
        JsonNode inputs = args.get("inputs");
        if (inputs != null && inputs.isObject()) {
          input.set("inputs", inputs);
        }
        yield new Checked(
            input,
            "Lanzar "
                + definition.key()
                + " (v"
                + definition.version()
                + ") en "
                + item.getKey()
                + " «"
                + item.getTitle()
                + "»");
      }
      case CREATE_WORK_ITEM -> {
        Project project = projects.get(uuid(args, "projectId"));
        projects.requireActive(project);
        String title = text(args, "title", true);
        WorkItemType type = enumValue(WorkItemType.class, args, "type", WorkItemType.FEATURE);
        UUID repository = optionalUuid(args, "repositoryId");
        if (repository != null
            && !project.getId().equals(projects.getRepository(repository).getProjectId())) {
          throw new InvalidRequestException("El repositorio no es de ese proyecto");
        }
        input.put("projectId", project.getId().toString());
        input.put("title", title);
        input.put("type", type.name());
        putIfPresent(input, "description", text(args, "description", false));
        putIfPresent(input, "repositoryId", repository == null ? null : repository.toString());
        yield new Checked(
            input, "Crear el trabajo «" + title + "» (" + type.name() + ") en " + project.getKey());
      }
      case CANCEL_RUN -> {
        RunView run = runs.run(uuid(args, "workflowRunId"));
        if (run.status().isTerminal()) {
          throw new ConflictException("La ejecución ya terminó: no hay nada que cancelar");
        }
        input.put("workflowRunId", run.id().toString());
        yield new Checked(input, "Cancelar la ejecución de " + runLabel(run));
      }
      case RETRY_STAGE -> {
        RunView run = runs.run(uuid(args, "workflowRunId"));
        String stage = text(args, "stageKey", true);
        if (run.stages().stream().noneMatch(s -> stage.equals(s.stageKey()))) {
          throw new InvalidRequestException("La ejecución no tiene la fase " + stage);
        }
        input.put("workflowRunId", run.id().toString());
        input.put("stageKey", stage);
        yield new Checked(input, "Reintentar la fase " + stage + " de " + runLabel(run));
      }
      case DECIDE_APPROVAL -> {
        ApprovalView approval = approvals.view(uuid(args, "approvalId"));
        if (!"PENDING".equals(approval.status())) {
          throw new ConflictException("La aprobación ya está decidida");
        }
        Approvals.Action decision = enumValue(Approvals.Action.class, args, "decision", null);
        if (decision == null || decision == Approvals.Action.COMMENT) {
          throw new InvalidRequestException("decision debe ser APPROVE, REJECT o REQUEST_CHANGES");
        }
        String comment = text(args, "comment", false);
        if (comment == null && decision != Approvals.Action.APPROVE) {
          throw new InvalidRequestException("Rechazar o pedir cambios necesita un comentario");
        }
        input.put("approvalId", approval.id().toString());
        input.put("decision", decision.name());
        putIfPresent(input, "comment", comment);
        yield new Checked(
            input, decisionLabel(decision) + " la fase " + approval.stageKey() + " (aprobación)");
      }
      case SAVE_MEMORY -> {
        Project project = projects.get(uuid(args, "projectId"));
        projects.requireActive(project);
        MemoryKind memoryKind = enumValue(MemoryKind.class, args, "kind", null);
        if (memoryKind == null) {
          throw new InvalidRequestException("Falta kind");
        }
        String title = text(args, "title", true);
        input.put("projectId", project.getId().toString());
        input.put("kind", memoryKind.name());
        input.put("title", title);
        input.put("what", text(args, "what", true));
        for (String field : List.of("why", "where", "learned", "topicKey")) {
          putIfPresent(input, field, text(args, field, false));
        }
        JsonNode tags = args.get("tags");
        if (tags != null && tags.isArray()) {
          input.set("tags", tags);
        }
        yield new Checked(
            input,
            "Guardar en la memoria de " + project.getKey() + ": [" + memoryKind + "] " + title);
      }
    };
  }

  /** La hace, con lo que se comprobó al proponerla. */
  private ChatCard execute(Action action, String by) {
    JsonNode in = action.input();
    return switch (action.kind()) {
      case LAUNCH -> {
        UUID workItem = UUID.fromString(in.path("workItemId").asString());
        Map<String, Object> inputs =
            in.has("inputs") ? json.convertValue(in.get("inputs"), INPUTS) : Map.of();
        UUID run =
            runs.launchUnlessDone(
                    workItem,
                    new RunService.Launch(
                        UUID.fromString(in.path("repositoryId").asString()),
                        UUID.fromString(in.path("definitionId").asString()),
                        inputs,
                        null,
                        null),
                    false)
                .getId();
        yield runCard(runs.run(run));
      }
      case CREATE_WORK_ITEM -> {
        WorkItem item =
            workItems.create(
                UUID.fromString(in.path("projectId").asString()),
                in.path("title").asString(),
                in.path("description").asString(null),
                WorkItemType.valueOf(in.path("type").asString()),
                null,
                in.has("repositoryId")
                    ? UUID.fromString(in.path("repositoryId").asString())
                    : null);
        yield new ChatCard(
            "work_item",
            item.getId().toString(),
            item.getKey() + " «" + item.getTitle() + "»",
            "/work-items/" + item.getId());
      }
      case CANCEL_RUN ->
          runCard(runs.cancelRun(UUID.fromString(in.path("workflowRunId").asString())));
      case RETRY_STAGE ->
          runCard(
              runs.retryStage(
                  UUID.fromString(in.path("workflowRunId").asString()),
                  in.path("stageKey").asString(),
                  by));
      case DECIDE_APPROVAL -> {
        ApprovalView view =
            approvals.decide(
                UUID.fromString(in.path("approvalId").asString()),
                Approvals.Action.valueOf(in.path("decision").asString()),
                in.path("comment").asString(null),
                by);
        yield new ChatCard(
            "approval",
            view.id().toString(),
            "Aprobación de " + view.stageKey() + ": " + view.status(),
            "/approvals/" + view.id());
      }
      case SAVE_MEMORY -> {
        List<String> tags = new ArrayList<>();
        in.path("tags").forEach(t -> tags.add(t.asString()));
        UUID project = UUID.fromString(in.path("projectId").asString());
        // La escribe Skyny, aunque la persona confirme la tarjeta: entra sin confirmar (MV-A).
        MemorySaved saved =
            memory.save(
                project,
                null,
                new MemoryContent(
                    MemoryKind.valueOf(in.path("kind").asString()),
                    in.path("title").asString(),
                    in.path("topicKey").asString(null),
                    in.path("what").asString(),
                    in.path("why").asString(null),
                    in.path("where").asString(null),
                    in.path("learned").asString(null),
                    tags),
                MemorySource.agent(null, null));
        yield new ChatCard(
            "memory",
            saved.observation().id().toString(),
            saved.observation().title(),
            "/projects/" + project);
      }
    };
  }

  private static ChatCard runCard(RunView run) {
    return new ChatCard("workflow_run", run.id().toString(), runLabel(run), "/runs/" + run.id());
  }

  private static String runLabel(RunView run) {
    return run.workItemKey() + " «" + run.workItemTitle() + "»";
  }

  private static String decisionLabel(Approvals.Action decision) {
    return switch (decision) {
      case APPROVE -> "Aprobar";
      case REJECT -> "Rechazar";
      case REQUEST_CHANGES -> "Pedir cambios en";
      case COMMENT -> "Comentar";
    };
  }

  private static String describe(Status status) {
    return switch (status) {
      case PENDING -> "pendiente";
      case CONFIRMED -> "confirmada";
      case REJECTED -> "rechazada";
      case EXPIRED -> "caducada";
      case FAILED -> "fallida";
    };
  }

  private void event(
      UUID conversationId,
      UUID actionId,
      Kind kind,
      String type,
      Map<String, ?> extra,
      Instant now) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("conversationId", conversationId.toString());
    payload.put("actionId", actionId.toString());
    payload.put("kind", kind.name());
    payload.putAll(extra);
    events.append(EventDraft.of(Conversations.AGGREGATE, conversationId, type, null, payload, now));
  }

  private static UUID uuid(JsonNode args, String field) {
    UUID value = optionalUuid(args, field);
    if (value == null) {
      throw new InvalidRequestException("Falta " + field);
    }
    return value;
  }

  private static UUID optionalUuid(JsonNode args, String field) {
    String value = args.path(field).asString("").strip();
    if (value.isEmpty()) {
      return null;
    }
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      throw new InvalidRequestException(field + " no es un id: " + value);
    }
  }

  private static String text(JsonNode args, String field, boolean required) {
    String value = args.path(field).asString("").strip();
    if (value.isEmpty()) {
      if (required) {
        throw new InvalidRequestException("Falta " + field);
      }
      return null;
    }
    if (value.length() > MAX_TEXT) {
      throw new InvalidRequestException(field + " es demasiado largo");
    }
    return value;
  }

  private static <E extends Enum<E>> E enumValue(
      Class<E> type, JsonNode args, String field, E fallback) {
    String value = args.path(field).asString("").strip();
    if (value.isEmpty()) {
      return fallback;
    }
    try {
      return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new InvalidRequestException(field + " no vale: " + value);
    }
  }

  private static void putIfPresent(ObjectNode node, String field, String value) {
    if (value != null) {
      node.put(field, value);
    }
  }

  private Action action(ResultSet rs, int n) throws SQLException {
    String result = rs.getString("result");
    Timestamp decided = rs.getTimestamp("decided_at");
    return new Action(
        rs.getObject("id", UUID.class),
        rs.getObject("conversation_id", UUID.class),
        Kind.valueOf(rs.getString("kind")),
        json.readTree(rs.getString("input")),
        rs.getString("summary"),
        Status.valueOf(rs.getString("status")),
        result == null ? null : json.readValue(result, ChatCard.class),
        rs.getString("error"),
        rs.getTimestamp("created_at").toInstant(),
        decided == null ? null : decided.toInstant(),
        rs.getString("decided_by"));
  }
}
