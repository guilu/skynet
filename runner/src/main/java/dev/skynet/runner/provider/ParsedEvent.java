package dev.skynet.runner.provider;

import dev.skynet.protocol.AgentEventType;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Evento normalizado que produce un adaptador a partir de la salida del proveedor, antes de que el
 * runner le asigne {@code eventId}, {@code seq} y momento.
 */
public record ParsedEvent(AgentEventType type, Map<String, Object> payload) {

  public ParsedEvent {
    Objects.requireNonNull(type, "type");
    payload =
        payload == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
  }
}
