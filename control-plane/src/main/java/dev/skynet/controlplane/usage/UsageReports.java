package dev.skynet.controlplane.usage;

import dev.skynet.controlplane.definition.PublishedDefinition;
import dev.skynet.controlplane.definition.StageDefinition;
import dev.skynet.controlplane.definition.WorkflowDefinitions;
import dev.skynet.controlplane.shared.TimeSource;
import dev.skynet.protocol.AgentObservableStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Los informes de la página Uso: la evolución de una ventana, lo que cuesta cada agente y qué gastó
 * más.
 */
@Service
public class UsageReports {

  private static final String MEASURED = "measured";

  private final JdbcClient jdbc;
  private final TimeSource time;
  private final WorkflowDefinitions definitions;

  UsageReports(JdbcClient jdbc, TimeSource time, WorkflowDefinitions definitions) {
    this.jdbc = jdbc;
    this.time = time;
    this.definitions = definitions;
  }

  /**
   * La ventana actual de un CLI: sus muestras y, en cada una, lo que va de Skynet. Sin {@code
   * runnerId}, la del runner con la muestra más reciente de esa ventana; {@code null} si no hay
   * ninguna.
   */
  @Transactional(readOnly = true)
  public UsageHistory history(UUID runnerId, String provider, String bucket, String window) {
    Map<String, Object> key = new HashMap<>();
    key.put("provider", provider);
    key.put("bucket", bucket);
    key.put("window", window);
    String where =
        " WHERE provider = :provider AND bucket = :bucket AND window_kind = :window"
            + (runnerId == null ? "" : " AND runner_id = :runner");
    if (runnerId != null) {
      key.put("runner", runnerId);
    }
    var latest =
        jdbc.sql(
                "SELECT runner_id, resets_at FROM quota_snapshot"
                    + where
                    + " ORDER BY observed_at DESC, id DESC LIMIT 1")
            .params(key)
            .query(
                (rs, n) -> {
                  Timestamp resets = rs.getTimestamp("resets_at");
                  return new Latest(
                      rs.getObject("runner_id", UUID.class),
                      resets == null ? null : resets.toInstant());
                })
            .optional()
            .orElse(null);
    if (latest == null) {
      return null;
    }
    Instant resetsAt = latest.resetsAt();
    Instant start = QuotaWindow.start(window, resetsAt);
    if (start == null) {
      start = time.now().minus(QuotaWindow.length(window));
    }
    key.put("runner", latest.runnerId());
    key.put("start", Timestamp.from(start));
    String inWindow =
        " WHERE runner_id = :runner AND provider = :provider AND bucket = :bucket"
            + " AND window_kind = :window";
    List<Instant> ends = new ArrayList<>();
    List<Double> skynet = new ArrayList<>();
    jdbc.sql(
            "SELECT ended_at, skynet FROM quota_interval"
                + inWindow
                + " AND ended_at > :start ORDER BY ended_at")
        .params(key)
        .query(
            rs -> {
              ends.add(rs.getTimestamp("ended_at").toInstant());
              skynet.add(rs.getDouble("skynet"));
            });
    List<UsageHistory.Point> points = new ArrayList<>();
    int next = 0;
    double sum = 0;
    for (var sample :
        jdbc.sql(
                "SELECT observed_at, used_fraction, source FROM quota_snapshot"
                    + inWindow
                    + " AND observed_at >= :start ORDER BY observed_at, id")
            .params(key)
            .query(
                (rs, n) ->
                    new UsageHistory.Point(
                        rs.getTimestamp("observed_at").toInstant(),
                        rs.getDouble("used_fraction"),
                        0,
                        rs.getString("source")))
            .list()) {
      while (next < ends.size() && !ends.get(next).isAfter(sample.at())) {
        sum += skynet.get(next++);
      }
      points.add(
          new UsageHistory.Point(
              sample.at(),
              sample.usedFraction(),
              Math.min(sum, sample.usedFraction()),
              sample.source()));
    }
    return new UsageHistory(latest.runnerId(), provider, bucket, window, start, resetsAt, points);
  }

  private record Latest(UUID runnerId, Instant resetsAt) {}

  private record TopRun(
      UUID id,
      String status,
      Instant createdAt,
      UUID definitionId,
      String definitionKey,
      String workItemKey,
      String workItemTitle) {}

  private record AgentKey(String workflowKey, String agent, String provider) {}

  /**
   * Lo que cuesta cada agente (decisión 5 de CU): sus invocaciones lanzadas en el periodo, de la
   * que más semana gasta a la que menos.
   */
  @Transactional(readOnly = true)
  public List<AgentUsage> agents(String period, UUID runnerId) {
    Instant since = time.now().minus(UsagePeriod.parse(period));
    Map<AgentKey, long[]> counts = new LinkedHashMap<>();
    Map<AgentKey, double[]> shares = new HashMap<>();
    Map<AgentKey, Long[]> tokens = new HashMap<>();
    Map<AgentKey, String> names = new HashMap<>();
    Map<UUID, PublishedDefinition> published = new HashMap<>();
    jdbc.sql(
            "SELECT a.provider, a.status, a.input_tokens, a.output_tokens, s.stage_key,"
                + " r.definition_id, d.key AS definition_key,"
                + " (SELECT coalesce(sum(q.share), 0) FROM agent_run_quota q"
                + "   WHERE q.agent_run_id = a.id AND q.window_kind = 'five_hour') AS five_hour,"
                + " (SELECT coalesce(sum(q.share), 0) FROM agent_run_quota q"
                + "   WHERE q.agent_run_id = a.id AND q.window_kind = 'seven_day') AS seven_day"
                + " FROM agent_run a JOIN stage_run s ON s.id = a.stage_run_id"
                + " JOIN workflow_run r ON r.id = s.workflow_run_id"
                + " JOIN workflow_definition d ON d.id = r.definition_id"
                + " WHERE a.created_at >= :since"
                + (runnerId == null ? "" : " AND a.runner_id = :runner"))
        .params(
            runnerId == null
                ? Map.of("since", Timestamp.from(since))
                : Map.of("since", Timestamp.from(since), "runner", runnerId))
        .query(
            rs -> {
              UUID definitionId = rs.getObject("definition_id", UUID.class);
              PublishedDefinition definition =
                  published.computeIfAbsent(definitionId, this::publishedOrNull);
              String stageKey = rs.getString("stage_key");
              StageDefinition stage =
                  definition == null ? null : definition.definition().stage(stageKey).orElse(null);
              AgentKey agentKey =
                  new AgentKey(
                      rs.getString("definition_key"),
                      stage != null && stage.agent() != null ? stage.agent() : stageKey,
                      rs.getString("provider"));
              if (definition != null) {
                names.putIfAbsent(agentKey, definition.definition().name());
              }
              long[] c = counts.computeIfAbsent(agentKey, k -> new long[2]);
              c[0]++;
              if (AgentObservableStatus.COMPLETED.name().equals(rs.getString("status"))) {
                c[1]++;
              }
              double[] s = shares.computeIfAbsent(agentKey, k -> new double[2]);
              s[0] += rs.getDouble("five_hour");
              s[1] += rs.getDouble("seven_day");
              Long[] t = tokens.computeIfAbsent(agentKey, k -> new Long[2]);
              t[0] = add(t[0], rs.getObject("input_tokens", Long.class));
              t[1] = add(t[1], rs.getObject("output_tokens", Long.class));
            });
    List<AgentUsage> rows = new ArrayList<>();
    counts.forEach(
        (k, c) -> {
          double[] s = shares.get(k);
          Long[] t = tokens.get(k);
          rows.add(
              new AgentUsage(
                  k.workflowKey(),
                  names.get(k),
                  k.agent(),
                  k.provider(),
                  QuotaSnapshots.displayName(k.provider()),
                  c[0],
                  c[1],
                  s[0],
                  s[1],
                  s[1] / c[0],
                  c[1] == 0 ? null : s[1] / c[1],
                  t[0],
                  t[1]));
        });
    rows.sort(
        Comparator.comparingDouble(AgentUsage::sevenDay)
            .thenComparingDouble(AgentUsage::fiveHour)
            .reversed()
            .thenComparing(AgentUsage::workflowKey)
            .thenComparing(AgentUsage::agent));
    return rows;
  }

  /** Las ejecuciones lanzadas en el periodo que más cuota semanal (luego de 5 h) han gastado. */
  @Transactional(readOnly = true)
  public List<RunUsage> runs(String period, int limit, UUID runnerId) {
    Instant since = time.now().minus(UsagePeriod.parse(period));
    Map<String, Object> params = new HashMap<>();
    params.put("since", Timestamp.from(since));
    params.put("limit", limit);
    if (runnerId != null) {
      params.put("runner", runnerId);
    }
    String runner = runnerId == null ? "" : " AND a.runner_id = :runner";
    List<TopRun> top =
        jdbc.sql(
                "SELECT r.id, r.status, r.created_at, r.definition_id, d.key AS definition_key,"
                    + " w.key AS work_item_key, w.title AS work_item_title,"
                    + " sum(q.share) FILTER (WHERE q.window_kind = 'seven_day') AS seven_day,"
                    + " sum(q.share) FILTER (WHERE q.window_kind = 'five_hour') AS five_hour"
                    + " FROM workflow_run r JOIN workflow_definition d ON d.id = r.definition_id"
                    + " JOIN work_item w ON w.id = r.work_item_id"
                    + " JOIN stage_run s ON s.workflow_run_id = r.id"
                    + " JOIN agent_run a ON a.stage_run_id = s.id"
                    + " JOIN agent_run_quota q ON q.agent_run_id = a.id"
                    + " WHERE r.created_at >= :since"
                    + runner
                    + " GROUP BY r.id, d.key, w.key, w.title"
                    + " ORDER BY seven_day DESC NULLS LAST, five_hour DESC NULLS LAST,"
                    + " r.created_at DESC LIMIT :limit")
            .params(params)
            .query(
                (rs, n) ->
                    new TopRun(
                        rs.getObject("id", UUID.class),
                        rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getObject("definition_id", UUID.class),
                        rs.getString("definition_key"),
                        rs.getString("work_item_key"),
                        rs.getString("work_item_title")))
            .list();
    if (top.isEmpty()) {
      return List.of();
    }
    Map<UUID, List<QuotaShare>> quota = new HashMap<>();
    params.put("runs", top.stream().map(TopRun::id).toList());
    jdbc.sql(
            "SELECT s.workflow_run_id, q.provider, q.bucket, q.window_kind, sum(q.share) AS share,"
                + " bool_or(q.method = 'prorated') AS prorated FROM agent_run_quota q"
                + " JOIN agent_run a ON a.id = q.agent_run_id"
                + " JOIN stage_run s ON s.id = a.stage_run_id"
                + " WHERE s.workflow_run_id IN (:runs)"
                + runner
                + " GROUP BY s.workflow_run_id, q.provider, q.bucket, q.window_kind"
                + " ORDER BY q.provider, q.bucket, q.window_kind")
        .params(params)
        .query(
            rs -> {
              quota
                  .computeIfAbsent(
                      rs.getObject("workflow_run_id", UUID.class), k -> new ArrayList<>())
                  .add(
                      new QuotaShare(
                          rs.getString("provider"),
                          rs.getString("bucket"),
                          rs.getString("window_kind"),
                          rs.getDouble("share"),
                          rs.getBoolean("prorated") ? "prorated" : MEASURED));
            });
    Map<UUID, PublishedDefinition> published = new HashMap<>();
    return top.stream()
        .map(
            r -> {
              PublishedDefinition definition =
                  published.computeIfAbsent(r.definitionId(), this::publishedOrNull);
              return new RunUsage(
                  r.id(),
                  r.workItemKey(),
                  r.workItemTitle(),
                  r.definitionKey(),
                  definition == null ? null : definition.definition().name(),
                  r.status(),
                  r.createdAt(),
                  quota.getOrDefault(r.id(), List.of()));
            })
        .toList();
  }

  private PublishedDefinition publishedOrNull(UUID id) {
    try {
      return definitions.published(id);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static Long add(Long total, Long value) {
    if (value == null) {
      return total;
    }
    return total == null ? value : total + value;
  }
}
