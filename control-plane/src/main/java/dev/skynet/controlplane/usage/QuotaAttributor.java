package dev.skynet.controlplane.usage;

import dev.skynet.controlplane.shared.TimeSource;
import dev.skynet.protocol.AgentProviderKind;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToDoubleFunction;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reparte la cuota entre los agentes (decisión 4 de CU, ver {@code docs/usage-windows.md}).
 *
 * <p>Entre dos muestras seguidas de una misma ventana, la subida se reparte entre los agentes de
 * ese CLI que corrieron en ese runner durante el intervalo, en proporción a su coste (lo que cae
 * dentro del intervalo); si alguno no tiene coste, a sus tokens; si tampoco, al tiempo que corrió.
 * Si no corrió ninguno, va a «Fuera de Skynet». No cuentan los agentes que no llegaron a arrancar
 * el CLI. Claude Code da la cuota desde el principio de cada invocación: su primera muestra es el
 * punto de partida (lo que había antes de empezar), así que el intervalo que acaba en ella no es
 * del agente. Codex solo la da al final: lo que sigue a esa muestra ya no es suyo.
 *
 * <p>Si la ventana se renovó entre las dos muestras, la subida se cuenta desde 0 y desde la
 * renovación. Se recalcula al llegar cada muestra (también si llega desordenada) y cuando termina
 * un agente, porque hasta entonces no se sabe su coste.
 */
@Service
public class QuotaAttributor {

  /** Una bajada mayor que el redondeo de Claude (dos decimales) es que la ventana se renovó. */
  private static final double DROP = 0.005;

  private static final double NOTHING = 1e-9;

  /** CLIs cuya primera muestra de cada invocación es de antes de que el agente gastara. */
  private static final Set<String> BASELINE_FIRST = Set.of(AgentProviderKind.CLAUDE_CODE.id());

  private final JdbcClient jdbc;
  private final TimeSource time;

  QuotaAttributor(JdbcClient jdbc, TimeSource time) {
    this.jdbc = jdbc;
    this.time = time;
  }

  /** Una muestra de una ventana. */
  record Sample(long id, double used, Instant resetsAt, Instant observedAt) {}

  /** La ventana de un CLI en un runner. */
  record Key(UUID runnerId, String provider, String bucket, String window) {}

  private record Interval(long id, Key key, Instant start, Instant end, double delta) {}

  private record Agent(
      UUID id, Instant start, Instant end, BigDecimal cost, Long tokens, double overlapSeconds) {}

  private record Share(UUID agentRunId, double share) {}

  private record AgentSpan(UUID runnerId, String provider, Instant start, Instant end) {}

  /** Ha llegado la muestra {@code sampleId}: rehace los intervalos a su alrededor. */
  @Transactional
  public void sampleRecorded(Key key, long sampleId) {
    lock(key.runnerId(), key.provider());
    Sample sample = sample(sampleId);
    Sample previous = neighbour(key, sample, true);
    Sample next = neighbour(key, sample, false);
    Instant from = previous == null ? sample.observedAt() : previous.observedAt();
    Instant to = next == null ? sample.observedAt() : next.observedAt();
    // Lo que había entre las vecinas se sustituye: si la muestra llega desordenada, parte en dos un
    // intervalo que ya existía.
    List<Long> replaced =
        jdbc.sql(
                "SELECT id FROM quota_interval WHERE runner_id = :runner AND provider = :provider"
                    + " AND bucket = :bucket AND window_kind = :window"
                    + " AND ended_at > :from AND ended_at <= :to")
            .params(params(key))
            .param("from", Timestamp.from(from))
            .param("to", Timestamp.from(to))
            .query(Long.class)
            .list();
    if (!replaced.isEmpty()) {
      jdbc.sql("DELETE FROM agent_run_quota WHERE interval_id IN (:ids)")
          .param("ids", replaced)
          .update();
      jdbc.sql("DELETE FROM quota_interval WHERE id IN (:ids)").param("ids", replaced).update();
    }
    if (previous != null) {
      create(key, previous, sample);
    }
    if (next != null) {
      create(key, sample, next);
    }
  }

  /**
   * Ha terminado (o ha informado de su coste) un agente: rehace el reparto de los intervalos en los
   * que corrió.
   */
  @Transactional
  public void agentChanged(UUID agentRunId) {
    AgentSpan agent =
        jdbc.sql(
                "SELECT runner_id, provider, started_at, finished_at FROM agent_run"
                    + " WHERE id = ? AND runner_id IS NOT NULL AND started_at IS NOT NULL")
            .param(agentRunId)
            .query(
                (rs, n) -> {
                  Timestamp finished = rs.getTimestamp("finished_at");
                  return new AgentSpan(
                      rs.getObject("runner_id", UUID.class),
                      rs.getString("provider"),
                      rs.getTimestamp("started_at").toInstant(),
                      finished == null ? null : finished.toInstant());
                })
            .optional()
            .orElse(null);
    if (agent == null) {
      return;
    }
    lock(agent.runnerId(), agent.provider());
    List<Interval> intervals =
        jdbc.sql(
                "SELECT id, bucket, window_kind, started_at, ended_at, delta FROM quota_interval"
                    + " WHERE runner_id = :runner AND provider = :provider AND ended_at >= :start"
                    + (agent.end() == null ? "" : " AND started_at <= :end")
                    + " ORDER BY id")
            .param("runner", agent.runnerId())
            .param("provider", agent.provider())
            .param("start", Timestamp.from(agent.start()))
            .params(agent.end() == null ? Map.of() : Map.of("end", Timestamp.from(agent.end())))
            .query(
                (rs, n) ->
                    new Interval(
                        rs.getLong("id"),
                        new Key(
                            agent.runnerId(),
                            agent.provider(),
                            rs.getString("bucket"),
                            rs.getString("window_kind")),
                        rs.getTimestamp("started_at").toInstant(),
                        rs.getTimestamp("ended_at").toInstant(),
                        rs.getDouble("delta")))
            .list();
    for (Interval interval : intervals) {
      jdbc.sql("DELETE FROM agent_run_quota WHERE interval_id = ?").param(interval.id()).update();
      attribute(interval);
    }
  }

  private void create(Key key, Sample from, Sample to) {
    boolean renewed =
        (from.resetsAt() != null && !to.observedAt().isBefore(from.resetsAt()))
            || to.used() < from.used() - DROP;
    double delta = renewed ? to.used() : to.used() - from.used();
    if (delta <= NOTHING) {
      return;
    }
    Instant start = from.observedAt();
    if (renewed && from.resetsAt() != null && from.resetsAt().isAfter(start)) {
      // Lo de antes de la renovación ya no cuenta en esta ventana.
      start = from.resetsAt().isAfter(to.observedAt()) ? to.observedAt() : from.resetsAt();
    }
    long id =
        jdbc.sql(
                "INSERT INTO quota_interval (runner_id, provider, bucket, window_kind, started_at,"
                    + " ended_at, delta, skynet, renewed) VALUES (:runner, :provider, :bucket,"
                    + " :window, :start, :end, :delta, 0, :renewed) RETURNING id")
            .params(params(key))
            .param("start", Timestamp.from(start))
            .param("end", Timestamp.from(to.observedAt()))
            .param("delta", Math.min(delta, 1))
            .param("renewed", renewed)
            .query(Long.class)
            .single();
    attribute(new Interval(id, key, start, to.observedAt(), Math.min(delta, 1)));
  }

  private void attribute(Interval interval) {
    List<Agent> agents = agents(interval);
    double skynet = agents.isEmpty() ? 0 : interval.delta();
    List<Share> shares = split(agents, skynet);
    String method = agents.size() == 1 ? "measured" : "prorated";
    for (Share share : shares) {
      jdbc.sql(
              "INSERT INTO agent_run_quota (interval_id, agent_run_id, provider, bucket,"
                  + " window_kind, share, method, ended_at) VALUES (:interval, :agent, :provider,"
                  + " :bucket, :window, :share, :method, :end)")
          .params(params(interval.key()))
          .param("interval", interval.id())
          .param("agent", share.agentRunId())
          .param("share", Math.clamp(share.share(), 0, 1))
          .param("method", method)
          .param("end", Timestamp.from(interval.end()))
          .update();
    }
    jdbc.sql("UPDATE quota_interval SET skynet = ? WHERE id = ?")
        .params(skynet, interval.id())
        .update();
  }

  /** Los agentes de ese CLI que corrieron en ese runner durante el intervalo. */
  private List<Agent> agents(Interval interval) {
    Instant now = time.now();
    return jdbc
        .sql(
            "SELECT id, started_at, finished_at, cost_usd, input_tokens, output_tokens,"
                + " cache_creation_tokens FROM agent_run WHERE runner_id = :runner"
                + " AND provider = :provider AND started_at IS NOT NULL AND started_at <= :end"
                + " AND (finished_at IS NULL OR finished_at >= :start)"
                + " AND failure IS DISTINCT FROM 'START'"
                + (BASELINE_FIRST.contains(interval.key().provider()) ? baseline() : reported())
                + " ORDER BY started_at, id")
        .params(params(interval.key()))
        .param("start", Timestamp.from(interval.start()))
        .param("end", Timestamp.from(interval.end()))
        .query((rs, n) -> agent(rs, interval, now))
        .list()
        .stream()
        .filter(a -> a.overlapSeconds() > 0 || interval.start().equals(interval.end()))
        .toList();
  }

  /**
   * El agente ya dio su cuota al terminar, en el inicio del intervalo o antes: lo que le queda de
   * vida (el proceso sale justo después) no es gasto.
   */
  private static String reported() {
    return " AND NOT EXISTS (SELECT 1 FROM quota_snapshot s WHERE s.agent_run_id = agent_run.id"
        + "   AND s.runner_id = :runner AND s.provider = :provider AND s.bucket = :bucket"
        + "   AND s.window_kind = :window AND s.observed_at <= :start)";
  }

  /** La muestra con la que acaba el intervalo es la primera del agente: es su punto de partida. */
  private static String baseline() {
    return " AND NOT EXISTS (SELECT 1 FROM quota_snapshot s WHERE s.agent_run_id = agent_run.id"
        + "   AND s.runner_id = :runner AND s.provider = :provider AND s.bucket = :bucket"
        + "   AND s.window_kind = :window AND s.observed_at = :end"
        + "   AND NOT EXISTS (SELECT 1 FROM quota_snapshot e WHERE e.agent_run_id = s.agent_run_id"
        + "     AND e.provider = s.provider AND e.bucket = s.bucket"
        + "     AND e.window_kind = s.window_kind AND e.observed_at < s.observed_at))";
  }

  private static Agent agent(ResultSet rs, Interval interval, Instant now) throws SQLException {
    Instant start = rs.getTimestamp("started_at").toInstant();
    Timestamp finished = rs.getTimestamp("finished_at");
    Instant end = finished != null ? finished.toInstant() : max(now, interval.end());
    Long tokens = null;
    long input = rs.getLong("input_tokens");
    boolean hasInput = !rs.wasNull();
    long output = rs.getLong("output_tokens");
    boolean hasOutput = !rs.wasNull();
    long creation = rs.getLong("cache_creation_tokens");
    if (hasInput || hasOutput) {
      tokens = input + output + creation;
    }
    double overlap = seconds(max(start, interval.start()), min(end, interval.end()));
    return new Agent(
        rs.getObject("id", UUID.class), start, end, rs.getBigDecimal("cost_usd"), tokens, overlap);
  }

  /**
   * Reparte {@code total} según el coste, los tokens o el tiempo de cada agente en el intervalo.
   */
  private static List<Share> split(List<Agent> agents, double total) {
    if (agents.isEmpty()) {
      return List.of();
    }
    ToDoubleFunction<Agent> weight;
    if (agents.stream().allMatch(a -> a.cost() != null && a.cost().signum() > 0)) {
      weight = a -> a.cost().doubleValue() * inside(a);
    } else if (agents.stream().allMatch(a -> a.tokens() != null && a.tokens() > 0)) {
      weight = a -> a.tokens() * inside(a);
    } else {
      weight = Agent::overlapSeconds;
    }
    double sum = agents.stream().mapToDouble(weight).sum();
    List<Share> shares = new ArrayList<>();
    for (Agent agent : agents) {
      double part = sum > 0 ? weight.applyAsDouble(agent) / sum : 1.0 / agents.size();
      shares.add(new Share(agent.id(), total * part));
    }
    return shares;
  }

  /** La parte de la vida del agente que cae dentro del intervalo. */
  private static double inside(Agent agent) {
    double span = seconds(agent.start(), agent.end());
    return span == 0 ? 1 : Math.min(agent.overlapSeconds() / span, 1);
  }

  private Sample sample(long id) {
    return jdbc.sql(
            "SELECT id, used_fraction, resets_at, observed_at FROM quota_snapshot WHERE id = ?")
        .param(id)
        .query((rs, n) -> sample(rs))
        .single();
  }

  /** La muestra anterior ({@code before}) o la siguiente de la misma ventana. */
  private Sample neighbour(Key key, Sample sample, boolean before) {
    return jdbc.sql(
            "SELECT id, used_fraction, resets_at, observed_at FROM quota_snapshot"
                + " WHERE runner_id = :runner AND provider = :provider AND bucket = :bucket"
                + " AND window_kind = :window AND (observed_at, id) "
                + (before ? "<" : ">")
                + " (:observed, :id) ORDER BY observed_at "
                + (before ? "DESC" : "ASC")
                + ", id "
                + (before ? "DESC" : "ASC")
                + " LIMIT 1")
        .params(params(key))
        .param("observed", Timestamp.from(sample.observedAt()))
        .param("id", sample.id())
        .query((rs, n) -> sample(rs))
        .optional()
        .orElse(null);
  }

  private static Sample sample(ResultSet rs) throws SQLException {
    Timestamp resets = rs.getTimestamp("resets_at");
    return new Sample(
        rs.getLong("id"),
        rs.getDouble("used_fraction"),
        resets == null ? null : resets.toInstant(),
        rs.getTimestamp("observed_at").toInstant());
  }

  /**
   * Las muestras y los agentes de un CLI en un runner se procesan de uno en uno aunque lleguen a la
   * vez: los intervalos de sus dos ventanas dependen de los mismos agentes.
   */
  private void lock(UUID runnerId, String provider) {
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(?))")
        .param("quota|" + runnerId + "|" + provider)
        .query((rs, n) -> 1)
        .single();
  }

  private static Map<String, Object> params(Key key) {
    return Map.of(
        "runner", key.runnerId(),
        "provider", key.provider(),
        "bucket", key.bucket(),
        "window", key.window());
  }

  private static double seconds(Instant from, Instant to) {
    return Math.max(0, Duration.between(from, to).toMillis() / 1000.0);
  }

  private static Instant max(Instant a, Instant b) {
    return a.isAfter(b) ? a : b;
  }

  private static Instant min(Instant a, Instant b) {
    return a.isBefore(b) ? a : b;
  }
}
