package dev.skynet.runner.provider.codex;

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
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Traduce el JSONL de {@code codex exec --json} a eventos normalizados (ver {@code
 * docs/codex-exec-json.md}). Cada {@code exec} es un turno: su {@code turn.completed} o {@code
 * turn.failed} es el resultado. Codex no informa del coste: el resultado lleva el estimado con la
 * tabla de precios en {@code costUsd}.
 */
public final class CodexEventParser implements EventParser {

  /** Longitud máxima de la salida de una herramienta que viaja en el evento. */
  static final int MAX_TOOL_OUTPUT = 16 * 1024;

  private final String model;
  private final boolean structured;
  private final ModelPrices prices;
  private final Set<String> started = new HashSet<>();
  private String threadId;
  private String lastMessage;
  private int turns;
  private boolean sawResult;

  /**
   * @param model modelo que se pidió, o {@code null} para el predeterminado de Codex
   * @param structured si la invocación pide salida estructurada ({@code --output-schema}): el
   *     último mensaje del agente es ese JSON
   */
  public CodexEventParser(String model, boolean structured, ModelPrices prices) {
    this.model = model;
    this.structured = structured;
    this.prices = prices;
  }

  @Override
  public boolean sawResult() {
    return sawResult;
  }

  /** Sesión de Codex ({@code thread.started}), o {@code null} si aún no ha llegado. */
  public String threadId() {
    return threadId;
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
    return switch (text(node, "type")) {
      case "thread.started" -> List.of(threadStarted(node));
      case "turn.started" -> List.of();
      case "item.started", "item.updated" -> itemStarted(node.path("item"));
      case "item.completed" -> itemCompleted(node.path("item"));
      case "turn.completed" -> List.of(turnCompleted(node));
      case "turn.failed" -> List.of(turnFailed(node));
      case null, default -> List.of(raw(node));
    };
  }

  private ParsedEvent threadStarted(JsonNode node) {
    threadId = text(node, "thread_id");
    return event(
        AgentEventType.SESSION_STARTED,
        payload().put("provider", "codex").put("sessionId", threadId).put("model", model));
  }

  /** Las herramientas se anuncian al empezar; los mensajes, al completarse. */
  private List<ParsedEvent> itemStarted(JsonNode item) {
    String id = text(item, "id");
    Optional<Payload> tool = tool(item);
    if (tool.isEmpty() || id == null || !started.add(id)) {
      return List.of();
    }
    return List.of(event(AgentEventType.TOOL_STARTED, tool.get()));
  }

  private List<ParsedEvent> itemCompleted(JsonNode item) {
    String id = text(item, "id");
    String type = text(item, "type");
    if ("agent_message".equals(type)) {
      lastMessage = text(item, "text");
      return List.of(
          event(
              AgentEventType.MESSAGE_RECEIVED,
              payload().put("messageId", id).put("model", model).put("text", lastMessage)));
    }
    Optional<Payload> tool = tool(item);
    if (tool.isEmpty()) {
      return List.of(raw(item));
    }
    List<ParsedEvent> events = new ArrayList<>();
    if (id == null || started.add(id)) {
      events.add(event(AgentEventType.TOOL_STARTED, tool.get()));
    }
    String output = output(item);
    Payload completed =
        payload()
            .put("toolUseId", id)
            .put("name", toolName(item))
            .put("isError", failed(item))
            .put("output", truncate(output, MAX_TOOL_OUTPUT));
    if (output != null && output.length() > MAX_TOOL_OUTPUT) {
      completed.put("outputTruncated", true);
    }
    if ("command_execution".equals(type)) {
      completed.put("details", payload().put("exitCode", longValue(item, "exit_code")).map());
    }
    events.add(event(AgentEventType.TOOL_COMPLETED, completed));
    if ("file_change".equals(type) && !failed(item)) {
      for (JsonNode change : item.path("changes")) {
        events.add(
            event(
                AgentEventType.FILE_CHANGED,
                payload()
                    .put("toolUseId", id)
                    .put("tool", "apply_patch")
                    .put("path", text(change, "path"))
                    .put("change", change(text(change, "kind")))));
      }
    }
    return events;
  }

  /** Una herramienta, o vacío si el elemento no lo es (mensajes, razonamiento, tareas…). */
  private static Optional<Payload> tool(JsonNode item) {
    String name = toolName(item);
    if (name == null) {
      return Optional.empty();
    }
    Object input =
        switch (text(item, "type")) {
          case "command_execution" -> Map.of("command", String.valueOf(text(item, "command")));
          case "file_change" -> value(item.get("changes"));
          case "mcp_tool_call" -> value(item.get("arguments"));
          case "web_search" -> payload().put("query", text(item, "query")).map();
          case null, default -> null;
        };
    return Optional.of(
        payload().put("toolUseId", text(item, "id")).put("name", name).put("input", input));
  }

  /** Nombre de la herramienta como lo pinta la web: {@code Bash} para los comandos. */
  private static String toolName(JsonNode item) {
    return switch (text(item, "type")) {
      case "command_execution" -> "Bash";
      case "file_change" -> "apply_patch";
      case "mcp_tool_call" -> "mcp__" + text(item, "server") + "__" + text(item, "tool");
      case "web_search" -> "WebSearch";
      case null, default -> null;
    };
  }

  private static boolean failed(JsonNode item) {
    String status = text(item, "status");
    if ("failed".equals(status) || "declined".equals(status)) {
      return true;
    }
    Long exitCode = longValue(item, "exit_code");
    return exitCode != null && exitCode != 0;
  }

  private static String output(JsonNode item) {
    return switch (text(item, "type")) {
      case "command_execution" -> text(item, "aggregated_output");
      case "mcp_tool_call" -> {
        JsonNode error = item.path("error");
        if (error.isObject()) {
          yield text(error, "message");
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : item.path("result").path("content")) {
          if ("text".equals(text(block, "type"))) {
            if (!sb.isEmpty()) {
              sb.append('\n');
            }
            sb.append(text(block, "text"));
          }
        }
        yield sb.isEmpty() ? null : sb.toString();
      }
      case "file_change" -> {
        List<String> lines = new ArrayList<>();
        item.path("changes").forEach(c -> lines.add(text(c, "kind") + " " + text(c, "path")));
        yield String.join("\n", lines);
      }
      case null, default -> null;
    };
  }

  private static String change(String kind) {
    return switch (kind) {
      case "add" -> "created";
      case "delete" -> "deleted";
      case null, default -> "modified";
    };
  }

  private ParsedEvent turnCompleted(JsonNode node) {
    sawResult = true;
    turns++;
    CodexUsage usage = CodexUsage.of(node.path("usage"));
    Payload payload =
        payload()
            .put("subtype", "success")
            .put("isError", false)
            .put("numTurns", (long) turns)
            .put("sessionId", threadId)
            .put(
                "usage",
                payload()
                    .put("input", usage.uncachedInput())
                    .put("output", usage.output())
                    .put("cacheRead", usage.cachedInput())
                    .map())
            .put("costUsd", usage.cost(prices, model).orElse(null))
            .put("result", lastMessage);
    if (structured) {
      Object output = structuredOutput();
      if (output == null) {
        return event(
            AgentEventType.RESULT,
            payload
                .put("subtype", "error_structured_output")
                .put("isError", true)
                .put("errors", List.of("Codex no devolvió un JSON en su último mensaje")));
      }
      payload.put("structuredOutput", output);
    }
    return event(AgentEventType.RESULT, payload);
  }

  /** El último mensaje del agente como JSON (con {@code --output-schema} es solo eso). */
  private Object structuredOutput() {
    return lastMessage == null ? null : value(object(lastMessage));
  }

  private ParsedEvent turnFailed(JsonNode node) {
    sawResult = true;
    turns++;
    String message = text(node.path("error"), "message");
    return event(
        AgentEventType.RESULT,
        payload()
            .put("subtype", "error")
            .put("isError", true)
            .put("numTurns", (long) turns)
            .put("sessionId", threadId)
            .put("errors", message == null ? null : List.of(message)));
  }

  private static Long longValue(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || !value.isNumber() ? null : value.asLong();
  }
}
