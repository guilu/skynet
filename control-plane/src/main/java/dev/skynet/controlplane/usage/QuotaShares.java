package dev.skynet.controlplane.usage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La cuota atribuida a cada agente ({@code agent_run_quota}), para las vistas de las ejecuciones.
 */
@Service
public class QuotaShares {

  private final JdbcClient jdbc;

  QuotaShares(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** La de cada agente, por CLI, grupo y ventana; un agente sin cuota no aparece. */
  @Transactional(readOnly = true)
  public Map<UUID, List<QuotaShare>> ofAgents(Collection<UUID> agentRunIds) {
    Map<UUID, List<QuotaShare>> shares = new HashMap<>();
    if (agentRunIds.isEmpty()) {
      return shares;
    }
    jdbc.sql(
            "SELECT agent_run_id, provider, bucket, window_kind, sum(share) AS share,"
                + " bool_or(method = 'prorated') AS prorated FROM agent_run_quota"
                + " WHERE agent_run_id IN (:ids) GROUP BY agent_run_id, provider, bucket,"
                + " window_kind ORDER BY provider, bucket, window_kind")
        .param("ids", agentRunIds)
        .query(
            rs -> {
              shares
                  .computeIfAbsent(rs.getObject("agent_run_id", UUID.class), k -> new ArrayList<>())
                  .add(
                      new QuotaShare(
                          rs.getString("provider"),
                          rs.getString("bucket"),
                          rs.getString("window_kind"),
                          Math.min(rs.getDouble("share"), 1),
                          rs.getBoolean("prorated") ? "prorated" : "measured"));
            });
    return shares;
  }

  /** La de un agente. */
  public List<QuotaShare> ofAgent(UUID agentRunId) {
    return ofAgents(List.of(agentRunId)).getOrDefault(agentRunId, List.of());
  }
}
