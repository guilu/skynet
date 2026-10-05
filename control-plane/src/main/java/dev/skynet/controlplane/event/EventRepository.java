package dev.skynet.controlplane.event;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Consultas de lectura sobre la tabla {@code event}. */
@Component
class EventRepository {

  private static final String COLUMNS =
      "sequence, event_id, workflow_run_id, aggregate_type, aggregate_id, event_type, payload,"
          + " occurred_at, recorded_at";

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  EventRepository(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  List<StoredEvent> list(long after, EventFilter filter, int limit) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM event WHERE sequence > :after"
                + " AND (CAST(:run AS uuid) IS NULL OR workflow_run_id = :run)"
                + " AND (CAST(:aggregate AS uuid) IS NULL OR aggregate_id = :aggregate)"
                + " ORDER BY sequence LIMIT :limit")
        .param("after", after)
        .param("run", filter.workflowRunId())
        .param("aggregate", filter.aggregateId())
        .param("limit", limit)
        .query(this::map)
        .list();
  }

  /** Los {@code limit} eventos inmediatamente anteriores a {@code before}, en orden ascendente. */
  List<StoredEvent> listBefore(long before, EventFilter filter, int limit) {
    List<StoredEvent> page =
        new ArrayList<>(
            jdbc.sql(
                    "SELECT "
                        + COLUMNS
                        + " FROM event WHERE sequence < :before"
                        + " AND (CAST(:run AS uuid) IS NULL OR workflow_run_id = :run)"
                        + " AND (CAST(:aggregate AS uuid) IS NULL OR aggregate_id = :aggregate)"
                        + " ORDER BY sequence DESC LIMIT :limit")
                .param("before", before)
                .param("run", filter.workflowRunId())
                .param("aggregate", filter.aggregateId())
                .param("limit", limit)
                .query(this::map)
                .list());
    Collections.reverse(page);
    return page;
  }

  Optional<StoredEvent> find(long sequence) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM event WHERE sequence = ?")
        .param(sequence)
        .query(this::map)
        .optional();
  }

  Optional<StoredEvent> findBySourceEventId(String sourceEventId) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM event WHERE source_event_id = ?")
        .param(sourceEventId)
        .query(this::map)
        .optional();
  }

  long lastSequence() {
    return jdbc.sql("SELECT coalesce(max(sequence), 0) FROM event").query(Long.class).single();
  }

  private StoredEvent map(ResultSet rs, int row) throws SQLException {
    return new StoredEvent(
        rs.getLong("sequence"),
        rs.getObject("event_id", UUID.class),
        rs.getObject("workflow_run_id", UUID.class),
        rs.getString("aggregate_type"),
        rs.getObject("aggregate_id", UUID.class),
        rs.getString("event_type"),
        json.readTree(rs.getString("payload")),
        rs.getTimestamp("occurred_at").toInstant(),
        rs.getTimestamp("recorded_at").toInstant());
  }
}
