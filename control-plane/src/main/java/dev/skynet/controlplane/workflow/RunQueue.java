package dev.skynet.controlplane.workflow;

import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * La cola por repositorio (DS-C): como mucho {@code maxConcurrentRuns} ejecuciones a la vez en cada
 * repositorio, también las de la web. Las que no caben se quedan en {@code PENDING} y entran por
 * orden de llegada cuando se libera un hueco.
 *
 * <p>Ocupa un hueco una ejecución {@code RUNNING} salvo si solo espera a una persona: alguna de sus
 * fases espera aprobación o respuesta y ninguna está trabajando. Así, dos puertas sin decidir no
 * bloquean el repositorio. Las ejecuciones hijas (continuar, bifurcar, reintentar un agente) no
 * esperan: las pide alguien a mano.
 */
@Component
public class RunQueue {

  private final JdbcClient jdbc;
  private final int defaultLimit;

  RunQueue(
      JdbcClient jdbc, @Value("${skynet.runs.max-concurrent-per-repository:2}") int defaultLimit) {
    this.jdbc = jdbc;
    this.defaultLimit = Math.max(1, defaultLimit);
  }

  /** El límite del repositorio: el suyo o, sin él, el general. */
  public int limit(UUID repositoryId) {
    Integer own = ownLimit(repositoryId);
    return own == null ? defaultLimit : own;
  }

  /** El límite propio del repositorio, o {@code null} si usa el general. */
  public Integer ownLimit(UUID repositoryId) {
    return jdbc.sql("SELECT max_concurrent_runs FROM repository WHERE id = ?")
        .param(repositoryId)
        .query(Integer.class)
        .optional()
        .orElse(null);
  }

  /** El límite general, para los repositorios que no tienen uno propio. */
  public int defaultLimit() {
    return defaultLimit;
  }

  /** Las ejecuciones que ocupan un hueco en el repositorio ahora. */
  public int occupied(UUID repositoryId) {
    return jdbc.sql(
            "SELECT count(*) FROM workflow_run r WHERE r.repository_id = ? AND r.status = 'RUNNING'"
                + " AND (EXISTS (SELECT 1 FROM stage_run s WHERE s.workflow_run_id = r.id"
                + "   AND s.status IN ('READY', 'STARTING', 'RUNNING'))"
                + " OR NOT EXISTS (SELECT 1 FROM stage_run s WHERE s.workflow_run_id = r.id"
                + "   AND s.status IN ('WAITING_FOR_APPROVAL', 'WAITING_FOR_INPUT')))")
        .param(repositoryId)
        .query(Integer.class)
        .single();
  }

  /**
   * Si una ejecución nueva puede empezar ya: bloquea el repositorio para que dos lanzamientos a la
   * vez no se cuelen los dos en el último hueco, y cuenta las que esperan delante.
   */
  boolean admitsNew(UUID repositoryId) {
    lock(repositoryId);
    return occupied(repositoryId) + queued(repositoryId, null) < limit(repositoryId);
  }

  /** Si la ejecución en cola {@code runId} puede empezar: le toca y hay hueco. */
  boolean admits(UUID repositoryId, UUID runId) {
    lock(repositoryId);
    return occupied(repositoryId) + queued(repositoryId, runId) < limit(repositoryId);
  }

  /** Las ejecuciones en cola del repositorio, de la más antigua a la más nueva. */
  public List<UUID> queuedRuns(UUID repositoryId) {
    return jdbc.sql(
            "SELECT id FROM workflow_run WHERE repository_id = ? AND status = 'PENDING'"
                + " ORDER BY created_at, id")
        .param(repositoryId)
        .query(UUID.class)
        .list();
  }

  /** Cambia el límite del repositorio; {@code null} vuelve al general. */
  public void setLimit(UUID repositoryId, Integer limit) {
    jdbc.sql("UPDATE repository SET max_concurrent_runs = ? WHERE id = ?")
        .params(limit, repositoryId)
        .update();
  }

  /** Las que esperan delante de {@code runId} (o todas, si es {@code null}). */
  private int queued(UUID repositoryId, UUID runId) {
    return jdbc.sql(
            "SELECT count(*) FROM workflow_run q WHERE q.repository_id = :repo"
                + " AND q.status = 'PENDING' AND q.cancel_requested_at IS NULL"
                + " AND (CAST(:run AS uuid) IS NULL OR (q.created_at, q.id) < (SELECT"
                + "   created_at, id FROM workflow_run WHERE id = :run))")
        .param("repo", repositoryId)
        .param("run", runId)
        .query(Integer.class)
        .single();
  }

  private void lock(UUID repositoryId) {
    jdbc.sql("SELECT id FROM repository WHERE id = ? FOR NO KEY UPDATE")
        .param(repositoryId)
        .query(UUID.class)
        .optional();
  }
}
