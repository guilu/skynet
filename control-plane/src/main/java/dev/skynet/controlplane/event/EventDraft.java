package dev.skynet.controlplane.event;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Evento pendiente de registrar.
 *
 * @param sourceEventId clave de idempotencia del origen (p. ej. el {@code eventId} generado por el
 *     runner); si ya existe un evento con ella, no se vuelve a registrar
 */
public record EventDraft(
    String aggregateType,
    UUID aggregateId,
    String type,
    UUID workflowRunId,
    Map<String, ?> payload,
    String sourceEventId,
    Instant occurredAt) {

  public EventDraft {
    Objects.requireNonNull(aggregateType, "aggregateType");
    Objects.requireNonNull(aggregateId, "aggregateId");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(occurredAt, "occurredAt");
    payload = payload == null ? Map.of() : payload;
  }

  public static EventDraft of(
      String aggregateType,
      UUID aggregateId,
      String type,
      UUID workflowRunId,
      Map<String, ?> payload,
      Instant occurredAt) {
    return new EventDraft(
        aggregateType, aggregateId, type, workflowRunId, payload, null, occurredAt);
  }
}
