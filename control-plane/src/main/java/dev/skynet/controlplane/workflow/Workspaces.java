package dev.skynet.controlplane.workflow;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Worktrees que anuncian los runners y bloqueo de escritores sobre ellos. */
@Component
class Workspaces {

  private final JdbcClient jdbc;

  Workspaces(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Registra el worktree que ha preparado un runner. Una reanudación vuelve a anunciar el de su
   * padre, así que un worktree ya conocido no se duplica.
   */
  UUID register(
      UUID repositoryId,
      UUID runnerId,
      String path,
      String branch,
      String baseCommit,
      Instant now) {
    return jdbc.sql(
            "INSERT INTO workspace (id, repository_id, runner_id, path, branch, base_commit,"
                + " created_at) VALUES (?, ?, ?, ?, ?, ?, ?)"
                + " ON CONFLICT (runner_id, path) DO UPDATE SET branch = EXCLUDED.branch"
                + " RETURNING id")
        .params(
            UUID.randomUUID(),
            repositoryId,
            runnerId,
            path,
            branch,
            baseCommit,
            Timestamp.from(now))
        .query(UUID.class)
        .single();
  }

  Optional<WorkspaceView> find(UUID id) {
    if (id == null) {
      return Optional.empty();
    }
    return jdbc.sql("SELECT * FROM workspace WHERE id = ?").param(id).query(this::view).optional();
  }

  List<WorkspaceView> findAll(Collection<UUID> ids) {
    if (ids.isEmpty()) {
      return List.of();
    }
    return jdbc.sql("SELECT * FROM workspace WHERE id IN (:ids)")
        .param("ids", ids)
        .query(this::view)
        .list();
  }

  /**
   * Bloquea el worktree hasta el final de la transacción, para que dos peticiones simultáneas no
   * lancen dos escritores sobre él.
   */
  void lock(UUID id) {
    jdbc.sql("SELECT id FROM workspace WHERE id = ? FOR UPDATE").param(id).query(UUID.class).list();
  }

  /**
   * Alguna invocación del worktree sigue sin terminar, o alguna verificación sigue en cola o en
   * curso: las dos escriben en él.
   */
  boolean hasLiveInvocation(UUID id) {
    return jdbc.sql(
                "SELECT (SELECT count(*) FROM agent_run WHERE workspace_id = :id"
                    + " AND status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED'))"
                    + " + (SELECT count(*) FROM verification_run WHERE workspace_id = :id"
                    + " AND status IN ('QUEUED', 'RUNNING'))")
            .param("id", id)
            .query(Integer.class)
            .single()
        > 0;
  }

  /**
   * Marca el worktree como pendiente de eliminar. Devuelve {@code false} si ya estaba eliminado o
   * con la eliminación pedida.
   */
  boolean requestCleanup(UUID id, Instant now) {
    return jdbc.sql(
                "UPDATE workspace SET cleanup_requested_at = ?, cleanup_error = NULL WHERE id = ?"
                    + " AND removed_at IS NULL AND cleanup_requested_at IS NULL")
            .params(Timestamp.from(now), id)
            .update()
        == 1;
  }

  /**
   * Respuesta del runner a la orden de eliminar: sin error queda eliminado; con error vuelve a
   * poder usarse (y a pedirse su eliminación) y guarda el motivo.
   */
  void cleanupFinished(UUID id, String error, Instant at) {
    if (error == null) {
      jdbc.sql(
              "UPDATE workspace SET removed_at = COALESCE(removed_at, ?),"
                  + " cleanup_requested_at = NULL, cleanup_error = NULL WHERE id = ?")
          .params(Timestamp.from(at), id)
          .update();
    } else {
      jdbc.sql(
              "UPDATE workspace SET cleanup_requested_at = NULL, cleanup_error = ?"
                  + " WHERE id = ? AND removed_at IS NULL")
          .params(error, id)
          .update();
    }
  }

  /**
   * Worktrees que la retención debe eliminar: sin eliminar ni pedir, sin nada vivo, y cuya última
   * invocación o verificación terminó antes de {@code cutoff}. Con un fallo anterior no se
   * reintentan solos: el motivo queda a la vista y se puede pedir a mano.
   */
  List<UUID> expired(Instant cutoff) {
    return jdbc.sql(
            "SELECT w.id FROM workspace w WHERE w.removed_at IS NULL"
                + " AND w.cleanup_requested_at IS NULL AND w.cleanup_error IS NULL"
                + " AND w.created_at < :cutoff"
                + " AND NOT EXISTS (SELECT 1 FROM agent_run a WHERE a.workspace_id = w.id"
                + " AND (a.status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')"
                + " OR a.finished_at IS NULL OR a.finished_at >= :cutoff))"
                + " AND NOT EXISTS (SELECT 1 FROM verification_run v WHERE v.workspace_id = w.id"
                + " AND (v.status IN ('QUEUED', 'RUNNING') OR v.finished_at >= :cutoff))"
                + " ORDER BY w.created_at")
        .param("cutoff", Timestamp.from(cutoff))
        .query(UUID.class)
        .list();
  }

  /**
   * La invocación más reciente que trabajó en el worktree desde su runner: en su nombre va la orden
   * de eliminarlo.
   */
  Optional<UUID> lastAgentOf(UUID id) {
    return jdbc.sql(
            "SELECT a.id FROM agent_run a JOIN workspace w ON w.id = a.workspace_id"
                + " WHERE w.id = ? AND a.runner_id = w.runner_id"
                + " ORDER BY a.created_at DESC LIMIT 1")
        .param(id)
        .query(UUID.class)
        .optional();
  }

  private WorkspaceView view(ResultSet rs, int row) throws SQLException {
    return new WorkspaceView(
        rs.getObject("id", UUID.class),
        rs.getObject("runner_id", UUID.class),
        rs.getString("path"),
        rs.getString("branch"),
        rs.getString("base_commit"),
        instant(rs.getTimestamp("cleanup_requested_at")),
        rs.getString("cleanup_error"),
        instant(rs.getTimestamp("removed_at")));
  }

  private static Instant instant(Timestamp value) {
    return value == null ? null : value.toInstant();
  }
}
