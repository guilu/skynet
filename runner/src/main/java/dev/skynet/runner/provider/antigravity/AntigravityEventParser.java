package dev.skynet.runner.provider.antigravity;

import static dev.skynet.runner.provider.EventJson.event;
import static dev.skynet.runner.provider.EventJson.invalid;
import static dev.skynet.runner.provider.EventJson.object;
import static dev.skynet.runner.provider.EventJson.payload;
import static dev.skynet.runner.provider.EventJson.raw;
import static dev.skynet.runner.provider.EventJson.text;
import static dev.skynet.runner.provider.EventJson.truncate;
import static dev.skynet.runner.provider.EventJson.value;

import dev.skynet.protocol.AgentEventType;
import dev.skynet.runner.provider.EventJson.Payload;
import dev.skynet.runner.provider.EventParser;
import dev.skynet.runner.provider.ModelPrices;
import dev.skynet.runner.provider.ParsedEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Traduce el NDJSON de {@code agy -p --output-format stream-json} a eventos normalizados (ver
 * {@code docs/antigravity-stream-json.md}). agy cuenta la sesión en pasos ({@code step_update}): el
 * texto del agente llega en fragmentos ({@code text_delta}) que se juntan hasta que su paso
 * termina; cada herramienta es un paso que empieza ({@code ACTIVE}) y termina ({@code DONE}). agy
 * no informa del coste: el resultado lleva el estimado con la tabla de precios en {@code costUsd}.
 */
public final class AntigravityEventParser implements EventParser {

  /** Longitud máxima de la salida de una herramienta que viaja en el evento. */
  static final int MAX_TOOL_OUTPUT = 16 * 1024;

  /** Herramientas que escriben un fichero ({@code TargetFile} en sus parámetros). */
  static final Set<String> FILE_TOOLS =
      Set.of("write_to_file", "replace_file_content", "multi_replace_file_content", "sed_file");

  private final String model;
  private final boolean structured;
  private final ModelPrices prices;
  private final Set<Long> started = new HashSet<>();
  private final StringBuilder text = new StringBuilder();
  private String conversationId;
  private String lastMessage;
  private boolean sawResult;

  /**
   * Tokens de los pasos del agente que aún no ha llevado ningún evento. Viajan en el siguiente
   * mensaje o herramienta para que el control plane los sume en vivo: si el agente se cancela (el
   * fail-fast de una {@code parallel}) no llega el {@code result} con los totales.
   */
  private AntigravityUsage pendingUsage;

  /**
   * @param model modelo que se pidió, o {@code null} para el predeterminado de la cuenta
   * @param structured si se pidió salida estructurada ({@code --json-schema})
   */
  public AntigravityEventParser(String model, boolean structured, ModelPrices prices) {
    this.model = model;
    this.structured = structured;
    this.prices = prices;
  }

  /** Conversación de agy ({@code init}), o {@code null} si aún no ha llegado. */
  public String conversationId() {
    return conversationId;
  }

  @Override
  public boolean sawResult() {
    return sawResult;
  }

  @Override
  public List<ParsedEvent> parse(String line) {
    if (line == null || line.isBlank()) {
      return List.of();
    }
    JsonNode node = object(line);
    if (node == null) {
      return List.of(invalid(line));
    }
    return switch (text(node, "event")) {
      case "init" -> List.of(init(node));
      case "step_update" -> step(node.path("step_update"));
      case "result" -> result(node.path("result"));
      case null, default -> List.of(raw(node));
    };
  }

  private ParsedEvent init(JsonNode node) {
    conversationId = text(node, "conversation_id");
    return event(
        AgentEventType.SESSION_STARTED,
        payload()
            .put("provider", "antigravity")
            .put("sessionId", conversationId)
            .put("model", model)
            .put("permissionMode", text(node.path("init"), "permission_mode")));
  }

  private List<ParsedEvent> step(JsonNode step) {
    boolean done = "DONE".equals(text(step, "state"));
    return switch (text(step, "step_type")) {
      case "agent_response" -> {
        String delta = text(step, "text_delta");
        if (delta != null) {
          text.append(delta);
        }
        List<ParsedEvent> events = new ArrayList<>();
        if (done) {
          if (step.path("usage").isObject()) {
            pendingUsage = AntigravityUsage.of(step.path("usage")).plus(pendingUsage);
          }
          flush(events, step);
        }
        yield events;
      }
      case "tool" -> tool(step, done);
      // La entrada del usuario, los mensajes del sistema y el cierre no dicen nada nuevo.
      case "user_input", "system_message", "finish" -> List.of();
      case null, default -> List.of(raw(step));
    };
  }

  /** El texto acumulado de un paso del agente, como un mensaje. */
  private void flush(List<ParsedEvent> events, JsonNode step) {
    String message = text.toString().strip();
    text.setLength(0);
    if (message.isEmpty()) {
      return;
    }
    lastMessage = message;
    events.add(
        event(
            AgentEventType.MESSAGE_RECEIVED,
            payload()
                .put("messageId", "step_" + step.path("step_index").asLong())
                .put("model", model)
                .put("text", message)
                .put("usage", takeUsage())));
  }

  /** Los tokens pendientes, como {@code usage} de un evento, o {@code null} si no hay. */
  private Map<String, Object> takeUsage() {
    AntigravityUsage usage = pendingUsage;
    pendingUsage = null;
    if (usage == null) {
      return null;
    }
    return payload()
        .put("input", usage.input())
        .put("output", usage.billedOutput())
        .put("cacheRead", usage.cacheRead())
        .map();
  }

  private List<ParsedEvent> tool(JsonNode step, boolean done) {
    long index = step.path("step_index").asLong();
    JsonNode info = step.path("tool_info");
    String rawName = text(step, "tool_name") != null ? text(step, "tool_name") : text(info, "name");
    String id = "step_" + index;
    List<ParsedEvent> events = new ArrayList<>();
    if (started.add(index)) {
      events.add(
          event(
              AgentEventType.TOOL_STARTED,
              payload()
                  .put("toolUseId", id)
                  .put("name", toolName(rawName))
                  .put("input", input(rawName, info.path("parameters")))
                  .put("usage", takeUsage())));
    }
    if (!done) {
      return events;
    }
    String output = text(info, "output");
    Payload completed =
        payload()
            .put("toolUseId", id)
            .put("name", toolName(rawName))
            .put("isError", false)
            .put("output", truncate(output, MAX_TOOL_OUTPUT));
    if (output != null && output.length() > MAX_TOOL_OUTPUT) {
      completed.put("outputTruncated", true);
    }
    events.add(event(AgentEventType.TOOL_COMPLETED, completed));
    String path = text(info.path("parameters"), "TargetFile");
    if (FILE_TOOLS.contains(rawName) && path != null) {
      events.add(
          event(
              AgentEventType.FILE_CHANGED,
              payload()
                  .put("toolUseId", id)
                  .put("tool", rawName)
                  .put("path", path)
                  .put("change", "write_to_file".equals(rawName) ? "created" : "modified")));
    }
    return events;
  }

  /** Nombre de la herramienta como lo pinta la web: {@code Bash} para los comandos. */
  static String toolName(String name) {
    return "run_command".equals(name) ? "Bash" : name;
  }

  /** Parámetros de la herramienta; los comandos, con {@code command} como los de Claude Code. */
  private static Object input(String name, JsonNode parameters) {
    if ("run_command".equals(name) && text(parameters, "CommandLine") != null) {
      return Map.of("command", text(parameters, "CommandLine"));
    }
    return value(parameters);
  }

  private List<ParsedEvent> result(JsonNode result) {
    sawResult = true;
    List<ParsedEvent> events = new ArrayList<>();
    if (!text.isEmpty()) {
      flush(events, result);
    }
    // Lo que agy no pudo hacer sin permiso (sin terminal no puede preguntar): se deniega solo.
    for (JsonNode denied : result.path("denied_actions")) {
      events.add(
          event(
              AgentEventType.PERMISSION_DENIED,
              payload()
                  .put("toolName", text(denied, "display_name"))
                  .put("reason", text(denied, "action"))
                  .put(
                      "message",
                      "agy no tiene permiso para «"
                          + text(denied, "action")
                          + "» sin preguntar: se denegó")));
    }
    AntigravityUsage usage = AntigravityUsage.of(result.path("usage"));
    boolean success = "SUCCESS".equals(text(result, "status"));
    String response = text(result, "response");
    Payload payload =
        payload()
            .put("subtype", success ? "success" : "error")
            .put("isError", !success)
            .put(
                "numTurns",
                result.path("num_turns").isNumber() ? result.path("num_turns").asLong() : null)
            .put("sessionId", conversationId)
            .put(
                "usage",
                payload()
                    .put("input", usage.input())
                    .put("output", usage.billedOutput())
                    .put("cacheRead", usage.cacheRead())
                    .map())
            .put("costUsd", usage.cost(prices, model).orElse(null))
            .put("result", response == null || response.isBlank() ? lastMessage : response.strip());
    if (!success) {
      String status = text(result, "status");
      events.add(
          event(
              AgentEventType.RESULT,
              payload.put(
                  "errors",
                  List.of(
                      "Antigravity CLI terminó con estado "
                          + (status == null ? "desconocido" : status)))));
      return events;
    }
    if (structured) {
      JsonNode output = result.get("structured_output");
      if (output == null || output.isNull()) {
        events.add(
            event(
                AgentEventType.RESULT,
                payload
                    .put("subtype", "error_structured_output")
                    .put("isError", true)
                    .put("errors", List.of("Antigravity CLI no devolvió la salida estructurada"))));
        return events;
      }
      payload.put("structuredOutput", value(output));
    }
    events.add(event(AgentEventType.RESULT, payload));
    return events;
  }
}
