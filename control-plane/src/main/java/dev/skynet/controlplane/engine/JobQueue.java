package dev.skynet.controlplane.engine;

import dev.skynet.controlplane.shared.TimeSource;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * La tabla {@code workflow_job}: las ejecuciones que el motor tiene que evaluar. Hay como mucho un
 * trabajo por ejecución; encolarlo otra vez sube su {@code generation}, de modo que un worker que
 * lo estaba evaluando no lo borra al terminar.
 */
@Component
class JobQueue {

  /** Un trabajo reclamado por un worker. */
  record Job(UUID runId, long generation, int attempts) {}

  private final JdbcClient jdbc;
  private final TimeSource time;

  JobQueue(JdbcClient jdbc, TimeSource time) {
    this.jdbc = jdbc;
    this.time = time;
  }

  /**
   * Deja la ejecución pendiente de evaluar ya, en la transacción en curso. Devuelve la generación
   * del trabajo.
   */
  long enqueue(UUID runId) {
    Timestamp now = Timestamp.from(time.now());
    return jdbc.sql(
            "INSERT INTO workflow_job (workflow_run_id, run_after, created_at) VALUES (?, ?, ?)"
                + " ON CONFLICT (workflow_run_id) DO UPDATE SET generation ="
                + " workflow_job.generation + 1, run_after = LEAST(workflow_job.run_after,"
                + " EXCLUDED.run_after) RETURNING generation")
        .params(runId, now, now)
        .query(Long.class)
        .single();
  }

  /**
   * Termina el trabajo si nadie lo ha vuelto a encolar desde {@code generation}; si no, lo suelta
   * para que se evalúe otra vez.
   */
  @Transactional
  void complete(UUID runId, long generation) {
    int deleted =
        jdbc.sql("DELETE FROM workflow_job WHERE workflow_run_id = ? AND generation = ?")
            .params(runId, generation)
            .update();
    if (deleted == 0) {
      jdbc.sql(
              "UPDATE workflow_job SET locked_until = NULL, attempts = 0, last_error = NULL"
                  + " WHERE workflow_run_id = ?")
          .param(runId)
          .update();
    }
  }

  /**
   * La evaluación tuvo que esperar o falló: se vuelve a intentar pasado {@code delay}, más tarde
   * cuantos más intentos lleve.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  void retryLater(UUID runId, long generation, Duration delay, String error) {
    // Si se ha vuelto a encolar mientras tanto, se evalúa ya: el cambio nuevo puede desbloquearla.
    jdbc.sql(
            "UPDATE workflow_job SET locked_until = NULL, attempts = attempts + 1,"
                + " last_error = ?, run_after = CASE WHEN generation = ? THEN ? +"
                + " make_interval(secs => ? * LEAST(attempts + 1, 12)) ELSE run_after END"
                + " WHERE workflow_run_id = ?")
        .params(error, generation, Timestamp.from(time.now()), delay.toMillis() / 1000.0, runId)
        .update();
  }

  /**
   * Reclama hasta {@code limit} trabajos vencidos y libres (o con el alquiler caducado) y se los
   * queda durante {@code lease}. {@code SKIP LOCKED} reparte los trabajos entre varios control
   * planes sin que se esperen.
   */
  @Transactional
  List<Job> claim(int limit, Duration lease) {
    Instant now = time.now();
    return jdbc.sql(
            "UPDATE workflow_job SET locked_until = ? WHERE workflow_run_id IN (SELECT"
                + " workflow_run_id FROM workflow_job WHERE run_after <= ? AND (locked_until IS"
                + " NULL OR locked_until < ?) ORDER BY run_after LIMIT ? FOR UPDATE SKIP LOCKED)"
                + " RETURNING workflow_run_id, generation, attempts")
        .params(Timestamp.from(now.plus(lease)), Timestamp.from(now), Timestamp.from(now), limit)
        .query(
            (rs, n) ->
                new Job(
                    rs.getObject("workflow_run_id", UUID.class),
                    rs.getLong("generation"),
                    rs.getInt("attempts")))
        .list();
  }

  /** Encola todas las ejecuciones sin terminar, al arrancar el control plane. */
  @Transactional
  int reconcile() {
    Timestamp now = Timestamp.from(time.now());
    return jdbc.sql(
            "INSERT INTO workflow_job (workflow_run_id, run_after, created_at) SELECT id, ?, ?"
                + " FROM workflow_run WHERE status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')"
                + " ON CONFLICT (workflow_run_id) DO NOTHING")
        .params(now, now)
        .update();
  }
}
