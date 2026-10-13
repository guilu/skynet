package dev.skynet.runner.provider;

import dev.skynet.protocol.AgentEventType;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Ayudas que comparten los traductores de JSONL de los proveedores. */
public final class EventJson {

  public static final ObjectMapper JSON = JsonMapper.builder().build();

  private EventJson() {}

  /** El objeto JSON de una línea, o {@code null} si no lo es (ver {@link #invalid}). */
  public static JsonNode object(String line) {
    try {
      JsonNode node = JSON.readTree(line);
      return node.isObject() ? node : null;
    } catch (JacksonException e) {
      return null;
    }
  }

  /** Evento {@code RAW} para una línea que no es un objeto JSON. */
  public static ParsedEvent invalid(String line) {
    String error;
    try {
      JSON.readTree(line);
      error = "not_an_object";
    } catch (JacksonException e) {
      error = "invalid_json";
    }
    return raw(Map.of("line", line, "error", error));
  }

  public static ParsedEvent raw(JsonNode node) {
    return raw(Map.of("line", value(node)));
  }

  public static ParsedEvent raw(Map<String, Object> payload) {
    return new ParsedEvent(AgentEventType.RAW, payload);
  }

  public static ParsedEvent event(AgentEventType type, Payload payload) {
    return new ParsedEvent(type, payload.map());
  }

  public static String text(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    return value == null || value.isNull() ? null : value.asString();
  }

  public static Object value(JsonNode node) {
    return node == null || node.isNull() ? null : JSON.treeToValue(node, Object.class);
  }

  public static String truncate(String s, int max) {
    return s == null || s.length() <= max ? s : s.substring(0, max);
  }

  public static Payload payload() {
    return new Payload();
  }

  /** Mapa ordenado que ignora los valores {@code null}. */
  public static final class Payload {
    private final Map<String, Object> map = new LinkedHashMap<>();

    public Payload put(String key, Object value) {
      if (value != null) {
        map.put(key, value);
      }
      return this;
    }

    public Map<String, Object> map() {
      return map;
    }
  }
}
