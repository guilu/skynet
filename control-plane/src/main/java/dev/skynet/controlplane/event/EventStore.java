package dev.skynet.controlplane.event;

import dev.skynet.controlplane.shared.TimeSource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Registro append-only de eventos y sus consultas. */
@Component
public class EventStore {

  private final JdbcClient jdbc;
  private final ObjectMapper json;
  private final TimeSource time;
  private final EventRepository events;
  private final EventDispatcher dispatcher;

  EventStore(
      JdbcClient jdbc,
      ObjectMapper json,
      TimeSource time,
      EventRepository events,
      EventDispatcher dispatcher) {
    this.jdbc = jdbc;
    this.json = json;
    this.time = time;
    this.events = events;
    this.dispatcher = dispatcher;
  }

  /**
   * Registra un evento dentro de la transacción en curso.
   *
   * <p>La fila de {@code event_sequence} queda bloqueada hasta el commit, lo que serializa las
   * escrituras y garantiza que las secuencias confirmadas no tienen huecos ni se confirman fuera de
   * orden. Si el evento trae {@code sourceEventId} y ya estaba registrado, devuelve el existente.
   */
  @Transactional
  public StoredEvent append(EventDraft draft) {
    return appendIfNew(draft)
        .orElseGet(() -> events.findBySourceEventId(draft.sourceEventId()).orElseThrow());
  }

  /**
   * Como {@link #append}, pero devuelve vacío si el evento ya estaba registrado. Permite aplicar
   * los efectos de un evento una sola vez aunque el origen lo reenvíe.
   */
  @Transactional
  public Optional<StoredEvent> appendIfNew(EventDraft draft) {
    long last =
        jdbc.sql("SELECT last_value FROM event_sequence WHERE id = 1 FOR UPDATE")
            .query(Long.class)
            .single();
    if (draft.sourceEventId() != null) {
      Optional<StoredEvent> existing = events.findBySourceEventId(draft.sourceEventId());
      if (existing.isPresent()) {
        return Optional.empty();
      }
    }
    long sequence = last + 1;
    jdbc.sql("UPDATE event_sequence SET last_value = ? WHERE id = 1").param(sequence).update();
    StoredEvent event =
        new StoredEvent(
            sequence,
            UUID.randomUUID(),
            draft.workflowRunId(),
            draft.aggregateType(),
            draft.aggregateId(),
            draft.type(),
            json.valueToTree(draft.payload()),
            draft.occurredAt(),
            time.now());
    jdbc.sql(
            "INSERT INTO event (sequence, event_id, source_event_id, workflow_run_id,"
                + " aggregate_type, aggregate_id, event_type, payload, occurred_at, recorded_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)")
        .params(
            event.sequence(),
            event.eventId(),
            draft.sourceEventId(),
            event.workflowRunId(),
            event.aggregateType(),
            event.aggregateId(),
            event.type(),
            json.writeValueAsString(event.payload()),
            java.sql.Timestamp.from(event.occurredAt()),
            java.sql.Timestamp.from(event.recordedAt()))
        .update();
    notifyAfterCommit();
    return Optional.of(event);
  }

  /** Eventos con secuencia mayor que {@code after}, en orden, filtrados y paginados. */
  public List<StoredEvent> list(long after, EventFilter filter, int limit) {
    return events.list(after, filter, limit);
  }

  private void notifyAfterCommit() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              dispatcher.wakeUp();
            }
          });
    } else {
      dispatcher.wakeUp();
    }
  }
}
