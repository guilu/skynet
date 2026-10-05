package dev.skynet.runner.provider.claude;

import dev.skynet.protocol.AgentEventType;
import dev.skynet.runner.provider.ParsedEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Traduce el NDJSON de {@code claude -p --output-format stream-json --verbose} a eventos
 * normalizados, línea a línea.
 *
 * <p>El mapeo sigue {@code docs/claude-code-stream-json.md}. Decisiones:
 *
 * <ul>
 *   <li>Los {@code stream_event} (deltas parciales) no producen eventos persistentes: el mensaje
 *       completo llega después como {@code assistant}.
 *   <li>Cada bloque {@code assistant} produce su evento; los bloques de un mismo mensaje comparten
 *       {@code messageId}, que es lo que usa el timeline para agruparlos. Los bloques {@code
 *       thinking} se omiten. El primer evento de cada mensaje lleva su {@code usage}, de modo que
 *       el control plane puede mostrar los tokens en vivo antes del {@code result}.
 *   <li>{@code system/status}, {@code system/thinking_tokens} y los {@code rate_limit_event} con
 *       estado {@code allowed} no producen eventos.
 *   <li>Todo lo que no se reconoce (tipo, subtipo, bloque o JSON inválido) se conserva como {@link
 *       AgentEventType#RAW}: el parser nunca falla por una línea.
 * </ul>
 *
 * <p>Tiene estado (recuerda qué herramienta abrió cada {@code tool_use_id}), así que se usa una
 * instancia por invocación. No es seguro entre hilos.
 */
public final class ClaudeStreamParser {

  /** Longitud máxima del texto de salida de una herramienta que viaja en el evento. */
  static final int MAX_TOOL_OUTPUT = 16 * 1024;

  /** Herramientas cuyo resultado modifica un archivo. */
  private static final Set<String> FILE_TOOLS =
      Set.of("Edit", "MultiEdit", "Write", "NotebookEdit");

  // BigDecimal conserva el coste tal como lo escribe el CLI, sin pasar por double.
  private static final ObjectMapper JSON =
      JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

  private final Map<String, String> toolNames = new HashMap<>();
  private final Set<String> usageSeen = new HashSet<>();
  private String sessionId;
  private boolean sawResult;

  /** Id de sesión anunciado en {@code system/init}, o {@code null} si aún no ha llegado. */
  public String sessionId() {
    return sessionId;
  }

  /**
   * Si ha llegado la línea {@code result}. Una invocación cancelada no la tiene: su estado final se
   * deduce de la señal y el código de salida.
   */
  public boolean sawResult() {
    return sawResult;
  }

  public List<ParsedEvent> parse(String line) {
    if (line == null || line.isBlank()) {
      return List.of();
    }
    JsonNode node;
    try {
      node = JSON.readTree(line);
    } catch (JacksonException e) {
      return List.of(raw(Map.of("line", line, "error", "invalid_json")));
    }
    if (!node.isObject()) {
      return List.of(raw(Map.of("line", line, "error", "not_an_object")));
    }
    return switch (text(node, "type")) {
      case "system" -> system(node);
      case "assistant" -> assistant(node);
      case "user" -> user(node);
      case "result" -> List.of(result(node));
      case "rate_limit_event" -> rateLimit(node);
      case "stream_event" -> List.of();
      case null, default -> List.of(raw(node));
    };
  }

  private List<ParsedEvent> system(JsonNode node) {
    return switch (text(node, "subtype")) {
      case "init" -> List.of(init(node));
      case "status", "thinking_tokens" -> List.of();
      case "permission_denied" ->
          List.of(
              event(
                  AgentEventType.PERMISSION_DENIED,
                  payload()
                      .put("toolName", text(node, "tool_name"))
                      .put("toolUseId", text(node, "tool_use_id"))
                      .put("reason", text(node, "decision_reason_type"))
                      .put("message", text(node, "message"))));
      case "vcs_state_changed" ->
          List.of(
              event(
                  AgentEventType.VCS_CHANGED,
                  payload()
                      .put("kind", text(node, "kind"))
                      .put("branch", text(node, "branch"))
                      .put("cwd", text(node, "cwd"))));
      case null, default -> List.of(raw(node));
    };
  }

  private ParsedEvent init(JsonNode node) {
    sessionId = text(node, "session_id");
    List<String> tools = new ArrayList<>();
    node.path("tools").forEach(t -> tools.add(t.asString()));
    return event(
        AgentEventType.SESSION_STARTED,
        payload()
            .put("provider", "claude-code")
            .put("sessionId", sessionId)
            .put("model", text(node, "model"))
            .put("providerVersion", text(node, "claude_code_version"))
            .put("permissionMode", text(node, "permissionMode"))
            .put("cwd", text(node, "cwd"))
            .put("tools", tools));
  }

  private List<ParsedEvent> assistant(JsonNode node) {
    JsonNode message = node.path("message");
    String messageId = text(message, "id");
    String parentToolUseId = text(node, "parent_tool_use_id");
    // El CLI repite el mensaje (y su usage) en una línea por bloque: los tokens viajan solo con el
    // primer evento de cada mensaje, para que sumarlos no los cuente dos veces.
    Map<String, Object> usage =
        message.has("usage") && (messageId == null || usageSeen.add(messageId))
            ? tokens(message.path("usage")).map()
            : null;
    List<ParsedEvent> events = new ArrayList<>();
    for (JsonNode block : message.path("content")) {
      Payload payload =
          switch (text(block, "type")) {
            case "text" ->
                payload()
                    .put("messageId", messageId)
                    .put("model", text(message, "model"))
                    .put("text", text(block, "text"))
                    .put("parentToolUseId", parentToolUseId);
            case "tool_use" -> {
              String toolUseId = text(block, "id");
              String name = text(block, "name");
              if (toolUseId != null) {
                toolNames.put(toolUseId, name);
              }
              yield payload()
                  .put("messageId", messageId)
                  .put("toolUseId", toolUseId)
                  .put("name", name)
                  .put("input", value(block.get("input")))
                  .put("parentToolUseId", parentToolUseId);
            }
            case "thinking", "redacted_thinking" -> null;
            case null, default -> {
              events.add(raw(node));
              yield null;
            }
          };
      if (payload == null) {
        continue;
      }
      if (usage != null) {
        payload.put("usage", usage);
        usage = null;
      }
      AgentEventType type =
          "tool_use".equals(text(block, "type"))
              ? AgentEventType.TOOL_STARTED
              : AgentEventType.MESSAGE_RECEIVED;
      events.add(event(type, payload));
    }
    return events;
  }

  private List<ParsedEvent> user(JsonNode node) {
    JsonNode content = node.path("message").path("content");
    if (!content.isArray()) {
      return List.of(raw(node));
    }
    String parentToolUseId = text(node, "parent_tool_use_id");
    JsonNode structured = node.get("tool_use_result");
    List<ParsedEvent> events = new ArrayList<>();
    for (JsonNode block : content) {
      if (!"tool_result".equals(text(block, "type"))) {
        events.add(raw(node));
        continue;
      }
      String toolUseId = text(block, "tool_use_id");
      String name = toolUseId == null ? null : toolNames.get(toolUseId);
      boolean isError = block.path("is_error").asBoolean(false);
      String output = toolOutput(block.get("content"));
      Payload payload =
          payload()
              .put("toolUseId", toolUseId)
              .put("name", name)
              .put("isError", isError)
              .put("output", truncate(output))
              .put("parentToolUseId", parentToolUseId);
      if (output != null && output.length() > MAX_TOOL_OUTPUT) {
        payload.put("outputTruncated", true);
      }
      if (structured != null && structured.isObject()) {
        payload.put("details", details(name, structured));
      }
      events.add(event(AgentEventType.TOOL_COMPLETED, payload));
      if (!isError
          && name != null
          && FILE_TOOLS.contains(name)
          && structured != null
          && structured.isObject()) {
        events.add(fileChanged(toolUseId, name, structured));
      }
    }
    return events;
  }

  /** Resumen del {@code tool_use_result} estructurado, sin copias completas de archivos. */
  private static Map<String, Object> details(String name, JsonNode structured) {
    Payload details = payload();
    if ("Bash".equals(name)) {
      details
          .put("stdout", truncate(text(structured, "stdout")))
          .put("stderr", truncate(text(structured, "stderr")))
          .put("interrupted", bool(structured, "interrupted"))
          .put("gitOperation", value(structured.get("gitOperation")));
    } else {
      // Read anida la ruta en "file"; Edit y Write la dan en la raíz.
      JsonNode file = structured.path("file");
      String filePath = text(structured, "filePath");
      details
          .put("filePath", filePath != null ? filePath : text(file, "filePath"))
          .put("numLines", longValue(file, "numLines"));
    }
    return details.map();
  }

  private ParsedEvent fileChanged(String toolUseId, String name, JsonNode structured) {
    String change =
        switch (text(structured, "type")) {
          case "create" -> "created";
          case null, default -> "modified";
        };
    return event(
        AgentEventType.FILE_CHANGED,
        payload()
            .put("toolUseId", toolUseId)
            .put("tool", name)
            .put("path", text(structured, "filePath"))
            .put("change", change)
            .put("patch", value(structured.get("structuredPatch"))));
  }

  private ParsedEvent result(JsonNode node) {
    sawResult = true;
    Payload tokens = tokens(node.path("usage"));
    return event(
        AgentEventType.RESULT,
        payload()
            .put("subtype", text(node, "subtype"))
            .put("isError", bool(node, "is_error"))
            .put("terminalReason", text(node, "terminal_reason"))
            .put("stopReason", text(node, "stop_reason"))
            .put("errors", value(node.get("errors")))
            .put("numTurns", longValue(node, "num_turns"))
            .put("durationMs", longValue(node, "duration_ms"))
            .put("sessionId", text(node, "session_id"))
            .put(
                "costUsdCumulative",
                node.has("total_cost_usd") ? node.get("total_cost_usd").decimalValue() : null)
            .put("usage", tokens.map())
            .put("permissionDenials", node.path("permission_denials").size())
            .put("structuredOutput", value(node.get("structured_output")))
            .put("result", text(node, "result")));
  }

  private List<ParsedEvent> rateLimit(JsonNode node) {
    JsonNode info = node.path("rate_limit_info");
    String status = text(info, "status");
    if ("allowed".equals(status)) {
      return List.of();
    }
    return List.of(
        event(
            AgentEventType.RATE_LIMIT,
            payload()
                .put("status", status)
                .put("limitType", text(info, "rateLimitType"))
                .put("resetsAt", longValue(info, "resetsAt"))));
  }

  /** Texto de un {@code tool_result}: una cadena o una lista de bloques de texto. */
  private static String toolOutput(JsonNode content) {
    if (content == null || content.isNull()) {
      return null;
    }
    if (content.isString()) {
      return content.asString();
    }
    StringBuilder sb = new StringBuilder();
    for (JsonNode block : content) {
      if ("text".equals(text(block, "type"))) {
        if (!sb.isEmpty()) {
          sb.append('\n');
        }
        sb.append(text(block, "text"));
      }
    }
    return sb.toString();
  }

  private static String truncate(String s) {
    return s == null || s.length() <= MAX_TOOL_OUTPUT ? s : s.substring(0, MAX_TOOL_OUTPUT);
  }

  /** Tokens de un bloque {@code usage}: entrada, salida y caché (lectura y escritura). */
  private static Payload tokens(JsonNode usage) {
    return payload()
        .put("input", longValue(usage, "input_tokens"))
        .put("output", longValue(usage, "output_tokens"))
        .put("cacheRead", longValue(usage, "cache_read_input_tokens"))
        .put("cacheCreation", longValue(usage, "cache_creation_input_tokens"));
  }

  private static ParsedEvent raw(JsonNode node) {
    return raw(Map.of("line", value(node)));
  }

  private static ParsedEvent raw(Map<String, Object> payload) {
    return new ParsedEvent(AgentEventType.RAW, payload);
  }

  private static ParsedEvent event(AgentEventType type, Payload payload) {
    return new ParsedEvent(type, payload.map());
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    return value == null || value.isNull() ? null : value.asString();
  }

  private static Boolean bool(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asBoolean();
  }

  private static Long longValue(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || !value.isNumber() ? null : value.asLong();
  }

  /** Convierte un nodo en mapas, listas y valores simples de Java. */
  private static Object value(JsonNode node) {
    return node == null || node.isNull() ? null : JSON.treeToValue(node, Object.class);
  }

  private static Payload payload() {
    return new Payload();
  }

  /** Mapa ordenado que ignora los valores {@code null}. */
  private static final class Payload {
    private final Map<String, Object> map = new LinkedHashMap<>();

    Payload put(String key, Object value) {
      if (value != null) {
        map.put(key, value);
      }
      return this;
    }

    Map<String, Object> map() {
      return map;
    }
  }
}
