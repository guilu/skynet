package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.shared.TimeSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Continuar tras la cuota (CQ-A): sus ajustes y las fases que esperan ahora a que se renueve la
 * cuota de su proveedor.
 */
@RestController
class QuotaController {

  /** Lo que llegue vacío se queda como estaba; {@code maxWaitHours: 0} es sin límite. */
  record UpdateQuotaSettings(
      Boolean enabled, Integer maxWaitHours, Integer maxWaits, Integer fallbackAfterMinutes) {}

  /** Una fase que espera a la cuota: de qué ejecución, de qué proveedor y hasta cuándo. */
  record QuotaWaitRow(
      UUID workflowRunId,
      String workItemKey,
      String stageKey,
      int attempt,
      String provider,
      String window,
      Instant resetsAt,
      Instant notBefore) {}

  private final QuotaSettings settings;
  private final JdbcClient jdbc;
  private final TimeSource time;

  QuotaController(QuotaSettings settings, JdbcClient jdbc, TimeSource time) {
    this.settings = settings;
    this.jdbc = jdbc;
    this.time = time;
  }

  @GetMapping("/api/settings/quota")
  QuotaSettings.View get() {
    return settings.view();
  }

  @PutMapping("/api/settings/quota")
  QuotaSettings.View update(@RequestBody UpdateQuotaSettings request) {
    return settings.save(
        request.enabled(),
        request.maxWaitHours(),
        request.maxWaits(),
        request.fallbackAfterMinutes());
  }

  /** Las fases que esperan ahora a la cuota, la que antes sigue primero. */
  @GetMapping("/api/quota/waits")
  List<QuotaWaitRow> waits() {
    return jdbc.sql(
            "SELECT s.workflow_run_id, w.key AS work_item_key, s.stage_key, s.attempt,"
                + " s.quota_provider, s.quota_window, s.quota_resets_at, s.not_before"
                + " FROM stage_run s JOIN workflow_run r ON r.id = s.workflow_run_id"
                + " JOIN work_item w ON w.id = r.work_item_id"
                + " WHERE s.quota_provider IS NOT NULL AND s.quota_skipped_by IS NULL"
                + " AND s.status IN ('PENDING', 'READY') AND s.not_before > ?"
                + " AND r.status IN ('PENDING', 'RUNNING')"
                + " ORDER BY s.not_before")
        .param(Timestamp.from(time.now()))
        .query(
            (rs, n) ->
                new QuotaWaitRow(
                    rs.getObject("workflow_run_id", UUID.class),
                    rs.getString("work_item_key"),
                    rs.getString("stage_key"),
                    rs.getInt("attempt"),
                    rs.getString("quota_provider"),
                    rs.getString("quota_window"),
                    instant(rs.getTimestamp("quota_resets_at")),
                    instant(rs.getTimestamp("not_before"))))
        .list();
  }

  private static Instant instant(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }
}
