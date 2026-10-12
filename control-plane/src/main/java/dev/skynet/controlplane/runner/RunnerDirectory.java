package dev.skynet.controlplane.runner;

import dev.skynet.controlplane.shared.Archived;
import dev.skynet.controlplane.shared.TimeSource;
import dev.skynet.protocol.AgentProviderKind;
import dev.skynet.protocol.runner.ProviderInfo;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

  /** Runners por nombre; los olvidados (archivados) según {@code archived}. */
  @Transactional(readOnly = true)
  public List<RunnerView> list(Archived archived) {
    Instant staleBefore = time.now().minus(properties.staleAfter());
    Map<UUID, List<ProviderInfo>> providers = providers();
    return jdbc.sql(
            "SELECT r.id, r.name, r.capacity, r.runner_version, r.provider_version,"
                + " r.registered_at, r.last_heartbeat_at, r.archived_at,"
                + " (SELECT count(*) FROM agent_run a WHERE a.runner_id = r.id"
                + " AND a.status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')) AS active_agents"
                + " FROM runner r WHERE true"
                + archived.sql("r.archived_at")
                + " ORDER BY r.name")
        .query(
            (rs, row) -> {
              Timestamp heartbeat = rs.getTimestamp("last_heartbeat_at");
              Instant lastHeartbeat = heartbeat == null ? null : heartbeat.toInstant();
              Timestamp archivedAt = rs.getTimestamp("archived_at");
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
                      : RunnerView.Status.STALE,
                  archivedAt == null ? null : archivedAt.toInstant(),
                  providers.getOrDefault(rs.getObject("id", UUID.class), List.of()));
            })
        .list();
  }

  /** Proveedores de todos los runners, por runner. */
  private Map<UUID, List<ProviderInfo>> providers() {
    Map<UUID, List<ProviderInfo>> byRunner = new HashMap<>();
    jdbc.sql("SELECT runner_id, provider, version FROM runner_provider ORDER BY provider")
        .query(
            rs -> {
              byRunner
                  .computeIfAbsent(rs.getObject("runner_id", UUID.class), k -> new ArrayList<>())
                  .add(new ProviderInfo(rs.getString("provider"), rs.getString("version")));
            });
    return byRunner;
  }

  /**
   * Proveedores con agentes en cola sin asignar y ningún runner en línea (no olvidado) que los
   * tenga.
   */
  @Transactional(readOnly = true)
  public List<ProviderShortage> shortages() {
    Instant staleBefore = time.now().minus(properties.staleAfter());
    return jdbc.sql(
            "SELECT a.provider, count(*) AS queued FROM agent_run a WHERE a.status = 'QUEUED'"
                + " AND a.runner_id IS NULL AND a.provider <> 'command' AND NOT EXISTS (SELECT 1"
                + " FROM runner_provider p JOIN runner r ON r.id = p.runner_id"
                + " WHERE p.provider = a.provider AND r.archived_at IS NULL"
                + " AND r.last_heartbeat_at >= ?) GROUP BY a.provider ORDER BY a.provider")
        .param(Timestamp.from(staleBefore))
        .query(
            (rs, row) ->
                new ProviderShortage(
                    rs.getString("provider"),
                    AgentProviderKind.byId(rs.getString("provider"))
                        .map(AgentProviderKind::displayName)
                        .orElse(rs.getString("provider")),
                    rs.getInt("queued")))
        .list();
  }

  /** Runners sin latido reciente, sin contar los olvidados. */
  public List<RunnerView> stale() {
    return list(Archived.EXCLUDE).stream()
        .filter(r -> r.status() == RunnerView.Status.STALE)
        .toList();
  }
}
