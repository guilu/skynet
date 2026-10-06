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

  private WorkspaceView view(ResultSet rs, int row) throws SQLException {
    return new WorkspaceView(
        rs.getObject("id", UUID.class),
        rs.getObject("runner_id", UUID.class),
        rs.getString("path"),
        rs.getString("branch"),
        rs.getString("base_commit"));
  }
}
