package dev.skynet.controlplane.event;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Evento ya registrado, tal y como se expone por la API y por SSE. */
public record StoredEvent(
    long sequence,
    UUID eventId,
    UUID workflowRunId,
    String aggregateType,
    UUID aggregateId,
    String type,
    JsonNode payload,
    Instant occurredAt,
    Instant recordedAt) {}
