package dev.skynet.controlplane.github;

import dev.skynet.controlplane.shared.TimeSource;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * La tabla {@code github_webhook_delivery} como cola: guardar una entrega sin duplicados,
 * reclamarla con {@code FOR UPDATE SKIP LOCKED} y un alquiler, y cerrarla o dejarla para otro
 * intento.
 */
@Component
class WebhookQueue {

  enum Status {
    PENDING,
    PROCESSED,
    FAILED,
    IGNORED
  }

  /** Una entrega reclamada por un worker. */
  record Claimed(
      String deliveryId,
      String event,
      String action,
      int attempts,
      JsonNode payload,
      Instant receivedAt) {}

  private final JdbcClient jdbc;
  private final ObjectMapper json;
  private final TimeSource time;

  WebhookQueue(JdbcClient jdbc, ObjectMapper json, TimeSource time) {
    this.jdbc = jdbc;
    this.json = json;
    this.time = time;
  }

  /**
   * Guarda una entrega. Devuelve {@code false} si ya estaba (GitHub la repitió).
   *
   * @param status {@code PENDING} para procesarla, o {@code IGNORED} con {@code reason}
   */
  boolean insert(
      String deliveryId,
      String event,
      JsonNode payload,
      String body,
      Status status,
      String reason) {
    Instant now = time.now();
    JsonNode installation = payload.path("installation").path("id");
    return jdbc.sql(
                """
                INSERT INTO github_webhook_delivery (delivery_id, event, action, installation_id,
                    repository_name, status, run_after, last_error, payload, received_at,
                    processed_at)
                VALUES (:id, :event, :action, :installation, :repository, :status, :now, :reason,
                    :payload::jsonb, :now, :processedAt)
                ON CONFLICT (delivery_id) DO NOTHING
                """)
            .param("id", deliveryId)
            .param("event", event)
            .param("action", text(payload.path("action")))
            .param("installation", installation.isNumber() ? installation.asLong() : null)
            .param("repository", text(payload.path("repository").path("full_name")))
            .param("status", status.name())
            .param("now", Timestamp.from(now))
            .param("reason", reason)
            // PostgreSQL no guarda un NUL en jsonb.
            .param("payload", body.replace("\\u0000", ""))
            .param("processedAt", status == Status.PENDING ? null : Timestamp.from(now))
            .update()
        == 1;
  }

  /** La entrega pendiente más antigua que ya toca, para este worker durante {@code lease}. */
  @Transactional
  Optional<Claimed> claim(Duration lease) {
    Instant now = time.now();
    return jdbc.sql(
            """
            UPDATE github_webhook_delivery SET locked_until = :until, attempts = attempts + 1
            WHERE delivery_id = (
                SELECT delivery_id FROM github_webhook_delivery
                WHERE status = 'PENDING' AND run_after <= :now
                  AND (locked_until IS NULL OR locked_until < :now)
                ORDER BY run_after, received_at LIMIT 1
                FOR UPDATE SKIP LOCKED)
            RETURNING delivery_id, event, action, attempts, payload::text AS payload, received_at
            """)
        .param("until", Timestamp.from(now.plus(lease)))
        .param("now", Timestamp.from(now))
        .query(
            (rs, n) ->
                new Claimed(
                    rs.getString("delivery_id"),
                    rs.getString("event"),
                    rs.getString("action"),
                    rs.getInt("attempts"),
                    json.readTree(rs.getString("payload")),
                    rs.getTimestamp("received_at").toInstant()))
        .optional();
  }

  /** Cierra una entrega: procesada (con los eventos que publicó), ignorada o fallida. */
  void finish(String deliveryId, Status status, String reason, int events) {
    jdbc.sql(
            "UPDATE github_webhook_delivery SET status = :status, last_error = :reason,"
                + " published_events = :events, processed_at = :now, locked_until = NULL"
                + " WHERE delivery_id = :id")
        .param("status", status.name())
        .param("reason", reason)
        .param("events", events)
        .param("now", Timestamp.from(time.now()))
        .param("id", deliveryId)
        .update();
  }

  /** La deja pendiente para otro intento dentro de {@code delay}. */
  void retry(String deliveryId, String error, Duration delay) {
    jdbc.sql(
            "UPDATE github_webhook_delivery SET last_error = :error, locked_until = NULL,"
                + " run_after = :after WHERE delivery_id = :id")
        .param("error", error)
        .param("after", Timestamp.from(time.now().plus(delay)))
        .param("id", deliveryId)
        .update();
  }

  boolean exists(String deliveryId) {
    return jdbc.sql("SELECT count(*) FROM github_webhook_delivery WHERE delivery_id = :id")
            .param("id", deliveryId)
            .query(Integer.class)
            .single()
        > 0;
  }

  /** Las últimas entregas, de la más nueva a la más vieja. */
  List<WebhookDelivery> latest(int limit) {
    return jdbc.sql(
            "SELECT delivery_id, event, action, installation_id, repository_name, status,"
                + " attempts, last_error, published_events, received_at, processed_at"
                + " FROM github_webhook_delivery ORDER BY received_at DESC, delivery_id"
                + " LIMIT :limit")
        .param("limit", limit)
        .query(
            (rs, n) ->
                new WebhookDelivery(
                    rs.getString("delivery_id"),
                    rs.getString("event"),
                    rs.getString("action"),
                    rs.getObject("installation_id", Long.class),
                    rs.getString("repository_name"),
                    rs.getString("status"),
                    rs.getInt("attempts"),
                    rs.getString("last_error"),
                    rs.getInt("published_events"),
                    rs.getTimestamp("received_at").toInstant(),
                    rs.getTimestamp("processed_at") == null
                        ? null
                        : rs.getTimestamp("processed_at").toInstant()))
        .list();
  }

  /** Borra las entregas recibidas antes de {@code before}. */
  int purge(Instant before) {
    return jdbc.sql("DELETE FROM github_webhook_delivery WHERE received_at < :before")
        .param("before", Timestamp.from(before))
        .update();
  }

  private static String text(JsonNode node) {
    return node.isString() ? node.asString() : null;
  }
}
