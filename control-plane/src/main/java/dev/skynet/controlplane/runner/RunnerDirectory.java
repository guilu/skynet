package dev.skynet.controlplane.runner;

import dev.skynet.controlplane.shared.TimeSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Consulta de los runners registrados y su estado. */
@Service
public class RunnerDirectory {

  private final JdbcClient jdbc;
  private final TimeSource time;
  private final RunnerProperties properties;

  RunnerDirectory(JdbcClient jdbc, TimeSource time, RunnerProperties properties) {
    this.jdbc = jdbc;
    this.time = time;
    this.properties = properties;
  }

  /** Runners por nombre. */
  @Transactional(readOnly = true)
  public List<RunnerView> list() {
    Instant staleBefore = time.now().minus(properties.staleAfter());
    return jdbc.sql(
            "SELECT r.id, r.name, r.capacity, r.runner_version, r.provider_version,"
                + " r.registered_at, r.last_heartbeat_at,"
                + " (SELECT count(*) FROM agent_run a WHERE a.runner_id = r.id"
                + " AND a.status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')) AS active_agents"
                + " FROM runner r ORDER BY r.name")
        .query(
            (rs, row) -> {
              Timestamp heartbeat = rs.getTimestamp("last_heartbeat_at");
              Instant lastHeartbeat = heartbeat == null ? null : heartbeat.toInstant();
              return new RunnerView(
                  rs.getObject("id", UUID.class),
                  rs.getString("name"),
                  rs.getInt("capacity"),
                  rs.getInt("active_agents"),
                  rs.getString("runner_version"),
                  rs.getString("provider_version"),
                  rs.getTimestamp("registered_at").toInstant(),
                  lastHeartbeat,
                  lastHeartbeat != null && !lastHeartbeat.isBefore(staleBefore)
                      ? RunnerView.Status.ONLINE
                      : RunnerView.Status.STALE);
            })
        .list();
  }

  /** Runners sin latido reciente. */
  public List<RunnerView> stale() {
    return list().stream().filter(r -> r.status() == RunnerView.Status.STALE).toList();
  }
}
