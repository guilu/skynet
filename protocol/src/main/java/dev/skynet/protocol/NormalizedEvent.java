package dev.skynet.protocol;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Evento de un agente tal como lo envía el runner al control plane.
 *
 * @param eventId generado por el runner; clave de idempotencia de la ingestión
 * @param agentRunId invocación a la que pertenece
 * @param seq orden local dentro de la invocación, empezando en 1
 * @param occurredAt momento en que el runner observó el evento
 * @param type tipo normalizado
 * @param payload datos del evento; nunca contiene valores {@code null}
 */
public record NormalizedEvent(
    UUID eventId,
    UUID agentRunId,
    long seq,
    Instant occurredAt,
    AgentEventType type,
    Map<String, Object> payload) {

  public NormalizedEvent {
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(agentRunId, "agentRunId");
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(type, "type");
    if (seq < 1) {
      throw new IllegalArgumentException("seq debe empezar en 1: " + seq);
    }
    payload =
        payload == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
  }
}
