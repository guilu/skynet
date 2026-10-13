package dev.skynet.runner.provider.gemini;

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
import dev.skynet.runner.provider.StructuredOutput;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Traduce el JSONL de {@code gemini --output-format stream-json} a eventos normalizados (ver {@code
 * docs/gemini-stream-json.md}). Gemini manda el texto del agente en fragmentos ({@code message} con
 * {@code delta}): se juntan en un mensaje hasta la siguiente herramienta o el resultado. Gemini no
 * informa del coste: el resultado lleva el estimado con la tabla de precios en {@code costUsd}. Con
 * {@code output}, el último mensaje tiene que acabar en un JSON que cumpla su schema.
 */
public final class GeminiEventParser implements EventParser {

  /** Longitud máxima de la salida de una herramienta que viaja en el evento. */
  static final int MAX_TOOL_OUTPUT = 16 * 1024;

  /** Herramientas que escriben un fichero ({@code file_path} en sus parámetros). */
  static final Set<String> FILE_TOOLS = Set.of("write_file", "replace");

  private final String model;
  private final StructuredOutput structured;
  private final ModelPrices prices;
  private final Map<String, JsonNode> tools = new HashMap<>();
  private final StringBuilder text = new StringBuilder();
  private String sessionId;
  private String lastMessage;
  private int messages;
  private boolean sawResult;

  /**
   * @param model modelo que se pidió, o {@code null} para el predeterminado de Gemini CLI
   * @param structured la salida estructurada que se pidió en el prompt, o {@code null}
   */
  public GeminiEventParser(String model, StructuredOutput structured, ModelPrices prices) {
    this.model = model;
    this.structured = structured;
    this.prices = prices;
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
    String type = text(node, "type");
    if ("message".equals(type)) {
      if ("assistant".equals(text(node, "role"))) {
        String content = text(node, "content");
        if (content != null) {
          text.append(content);
        }
      }
      return List.of();
    }
    List<ParsedEvent> events = new ArrayList<>();
    flush(events);
    switch (type) {
      case "init" -> events.add(init(node));
      case "tool_use" -> events.add(toolUse(node));
      case "tool_result" -> toolResult(node, events);
      case "result" -> events.add(result(node));
      case null, default -> events.add(raw(node));
    }
    return events;
  }

  private ParsedEvent init(JsonNode node) {
    sessionId = text(node, "session_id");
    String initModel = text(node, "model");
    // "auto" es un alias: el modelo real llega por petición en el resultado.
    String reported = model != null ? model : "auto".equals(initModel) ? null : initModel;
    return event(
        AgentEventType.SESSION_STARTED,
        payload().put("provider", "gemini").put("sessionId", sessionId).put("model", reported));
  }

  /** El texto acumulado del agente, como un mensaje. */
  private void flush(List<ParsedEvent> events) {
    if (text.isEmpty()) {
      return;
    }
    lastMessage = text.toString();
    text.setLength(0);
    messages++;
    events.add(
        event(
            AgentEventType.MESSAGE_RECEIVED,
            payload()
                .put("messageId", "msg_" + messages)
                .put("model", model)
                .put("text", lastMessage)));
  }

  private ParsedEvent toolUse(JsonNode node) {
    String id = text(node, "tool_id");
    tools.put(id, node);
    return event(
        AgentEventType.TOOL_STARTED,
        payload()
            .put("toolUseId", id)
            .put("name", toolName(text(node, "tool_name")))
            .put("input", value(node.get("parameters"))));
  }

  private void toolResult(JsonNode node, List<ParsedEvent> events) {
    String id = text(node, "tool_id");
    JsonNode use = tools.get(id);
    String rawName = use == null ? null : text(use, "tool_name");
    boolean error = !"success".equals(text(node, "status"));
    String output = text(node, "output");
    if (output == null && node.path("error").isObject()) {
      output = text(node.path("error"), "message");
    }
    Payload completed =
        payload()
            .put("toolUseId", id)
            .put("name", toolName(rawName))
            .put("isError", error)
            .put("output", truncate(output, MAX_TOOL_OUTPUT));
    if (output != null && output.length() > MAX_TOOL_OUTPUT) {
      completed.put("outputTruncated", true);
    }
    if (node.path("error").isObject()) {
      completed.put("details", payload().put("errorType", text(node.path("error"), "type")).map());
    }
    events.add(event(AgentEventType.TOOL_COMPLETED, completed));
    if (!error && FILE_TOOLS.contains(rawName)) {
      String path = text(use.path("parameters"), "file_path");
      if (path != null) {
        events.add(
            event(
                AgentEventType.FILE_CHANGED,
                payload()
                    .put("toolUseId", id)
                    .put("tool", rawName)
                    .put("path", path)
                    .put("change", "modified")));
      }
    }
  }

  /** Nombre de la herramienta como lo pinta la web: {@code Bash} para los comandos. */
  static String toolName(String name) {
    return "run_shell_command".equals(name) ? "Bash" : name;
  }

  private ParsedEvent result(JsonNode node) {
    sawResult = true;
    GeminiStats stats = GeminiStats.of(node.path("stats"));
    boolean success = "success".equals(text(node, "status"));
    Payload payload =
        payload()
            .put("subtype", success ? "success" : "error")
            .put("isError", !success)
            .put("sessionId", sessionId)
            .put(
                "usage",
                payload()
                    .put("input", stats.total().input())
                    .put("output", stats.total().output())
                    .put("cacheRead", stats.total().cached())
                    .map())
            .put("models", stats.modelNames().isEmpty() ? null : stats.modelNames())
            .put("costUsd", stats.cost(prices).orElse(null))
            .put("result", lastMessage);
    if (!success) {
      String message = text(node.path("error"), "message");
      return event(
          AgentEventType.RESULT,
          payload.put(
              "errors", List.of(message == null ? "Gemini CLI terminó con un error" : message)));
    }
    if (structured != null) {
      StructuredOutput.Result read = structured.read(lastMessage);
      if (read.error() != null) {
        return event(
            AgentEventType.RESULT,
            payload
                .put("subtype", "error_structured_output")
                .put("isError", true)
                .put("errors", List.of(read.error())));
      }
      payload.put("structuredOutput", read.value());
    }
    return event(AgentEventType.RESULT, payload);
  }
}
