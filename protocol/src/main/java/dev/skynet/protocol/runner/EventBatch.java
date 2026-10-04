package dev.skynet.protocol.runner;

import dev.skynet.protocol.NormalizedEvent;
import java.util.List;

/**
 * Lote de eventos que el runner envía al control plane ({@code POST /api/runner/events}). La
 * ingestión es idempotente por {@link NormalizedEvent#eventId()}: reenviar un lote es seguro.
 */
public record EventBatch(List<NormalizedEvent> events) {

  public EventBatch {
    events = events == null ? List.of() : List.copyOf(events);
  }
}
