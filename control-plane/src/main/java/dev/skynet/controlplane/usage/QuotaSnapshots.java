package dev.skynet.controlplane.usage;

import dev.skynet.controlplane.shared.TimeSource;
import dev.skynet.protocol.AgentProviderKind;
import dev.skynet.protocol.runner.QuotaSample;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Las muestras de cuota de los CLIs ({@code quota_snapshot}): las que trae el latido del runner
 * (sus consultas) y las de los eventos {@code agent.quota} de cada invocación. Se borran a los 35
 * días.
 */
@Service
public class QuotaSnapshots {

  private static final Logger log = LoggerFactory.getLogger(QuotaSnapshots.class);
  static final Duration RETENTION = Duration.ofDays(35);

  /** Un dato más viejo que la ventana corta ya no dice cómo está. */
  static final Duration STALE_AFTER = Duration.ofHours(5);

  /** Una ventana por encima de esto avisa. */
  static final double LIMIT = 0.9;

  /** La semana avisa si va esto por encima del ritmo uniforme. */
  static final double AHEAD_OF_PACE = 0.15;

  private final JdbcClient jdbc;
  private final TimeSource time;
  private final QuotaAttributor attributor;

  QuotaSnapshots(JdbcClient jdbc, TimeSource time, QuotaAttributor attributor) {
    this.jdbc = jdbc;
    this.time = time;
    this.attributor = attributor;
  }

  /** Las que consultó el runner por su cuenta (latido). */
  @Transactional
  public void recordPolled(UUID runnerId, List<QuotaSample> samples) {
    for (QuotaSample sample : samples) {
      insert(runnerId, sample, "poll", null);
    }
  }

  /**
   * Las de un evento {@code agent.quota}: {@code provider} y {@code windows}. Lo que no tenga la
   * forma esperada se ignora: el evento ya está en el event store.
   */
  @Transactional
  public void recordInvocation(
      UUID runnerId, UUID agentRunId, Map<String, Object> payload, Instant occurredAt) {
    for (QuotaSample sample : fromEvent(payload, occurredAt)) {
      insert(runnerId, sample, "invocation", agentRunId);
    }
  }

  static List<QuotaSample> fromEvent(Map<String, Object> payload, Instant occurredAt) {
    List<QuotaSample> samples = new ArrayList<>();
    if (!(payload.get("provider") instanceof String provider)
        || !(payload.get("windows") instanceof List<?> windows)) {
      return samples;
    }
    for (Object item : windows) {
      if (!(item instanceof Map<?, ?> w)
          || !(w.get("window") instanceof String window)
          || !(w.get("usedFraction") instanceof Number used)
          || !(QuotaSample.FIVE_HOUR.equals(window) || QuotaSample.SEVEN_DAY.equals(window))) {
        continue;
      }
      samples.add(
          new QuotaSample(
              provider,
              w.get("bucket") instanceof String b ? b : null,
              w.get("label") instanceof String l ? l : null,
              window,
              used.doubleValue(),
              w.get("resetsAt") instanceof Number r ? Instant.ofEpochSecond(r.longValue()) : null,
              w.get("plan") instanceof String p ? p : null,
              occurredAt));
    }
    return samples;
  }

  private void insert(UUID runnerId, QuotaSample s, String source, UUID agentRunId) {
    if (!QuotaSample.FIVE_HOUR.equals(s.window()) && !QuotaSample.SEVEN_DAY.equals(s.window())) {
      return;
    }
    long id =
        jdbc.sql(
                "INSERT INTO quota_snapshot (runner_id, provider, bucket, label, window_kind,"
                    + " used_fraction, resets_at, plan, source, agent_run_id, observed_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id")
            .params(
                runnerId,
                s.provider(),
                s.bucket(),
                s.label(),
                s.window(),
                s.usedFraction(),
                s.resetsAt() == null ? null : Timestamp.from(s.resetsAt()),
                s.plan(),
                source,
                agentRunId,
                Timestamp.from(s.observedAt()))
            .query(Long.class)
            .single();
    attributor.sampleRecorded(
        new QuotaAttributor.Key(runnerId, s.provider(), s.bucket(), s.window()), id);
  }

  /** La última muestra de cada ventana de cada runner no olvidado (o solo de {@code runnerId}). */
  @Transactional(readOnly = true)
  public UsageWindows latest(UUID runnerId) {
    Instant now = time.now();
    Instant staleBefore = now.minus(STALE_AFTER);
    Map<UUID, UsageWindows.Runner> runners = new LinkedHashMap<>();
    jdbc.sql(
            "SELECT id, name FROM runner WHERE archived_at IS NULL"
                + (runnerId == null ? "" : " AND id = :runner")
                + " ORDER BY name")
        .params(runnerId == null ? Map.of() : Map.of("runner", runnerId))
        .query(
            rs -> {
              UUID id = rs.getObject("id", UUID.class);
              runners.put(id, new UsageWindows.Runner(id, rs.getString("name"), new ArrayList<>()));
            });
    if (runners.isEmpty()) {
      return new UsageWindows(List.of());
    }
    jdbc.sql(
            "SELECT DISTINCT ON (runner_id, provider, bucket, window_kind) runner_id, provider,"
                + " bucket, label, window_kind, used_fraction, resets_at, plan, source,"
                + " observed_at FROM quota_snapshot WHERE runner_id IN (:runners)"
                + " ORDER BY runner_id, provider, bucket, window_kind, observed_at DESC, id DESC")
        .param("runners", runners.keySet())
        .query(
            rs -> {
              Timestamp resets = rs.getTimestamp("resets_at");
              Instant resetsAt = resets == null ? null : resets.toInstant();
              Instant observed = rs.getTimestamp("observed_at").toInstant();
              String provider = rs.getString("provider");
              String window = rs.getString("window_kind");
              runners
                  .get(rs.getObject("runner_id", UUID.class))
                  .windows()
                  .add(
                      new UsageWindow(
                          provider,
                          displayName(provider),
                          rs.getString("bucket"),
                          rs.getString("label"),
                          window,
                          rs.getDouble("used_fraction"),
                          resetsAt,
                          rs.getString("plan"),
                          rs.getString("source"),
                          observed,
                          observed.isBefore(staleBefore),
                          QuotaWindow.pace(window, resetsAt, now),
                          0));
            });
    return new UsageWindows(
        runners.values().stream()
            .map(
                r ->
                    new UsageWindows.Runner(
                        r.id(),
                        r.name(),
                        r.windows().stream().map(w -> withSkynet(r.id(), w, now)).toList()))
            .toList());
  }

  /** Lo que va de Skynet en la ventana actual: lo atribuido desde que empezó. */
  private UsageWindow withSkynet(UUID runnerId, UsageWindow w, Instant now) {
    Instant start = QuotaWindow.start(w.window(), w.resetsAt());
    if (start == null) {
      start = now.minus(QuotaWindow.length(w.window()));
    }
    double skynet =
        jdbc.sql(
                "SELECT coalesce(sum(skynet), 0) FROM quota_interval WHERE runner_id = ?"
                    + " AND provider = ? AND bucket = ? AND window_kind = ? AND ended_at > ?"
                    + " AND ended_at <= ?")
            .params(
                runnerId,
                w.provider(),
                w.bucket(),
                w.window(),
                Timestamp.from(start),
                Timestamp.from(w.observedAt()))
            .query(Double.class)
            .single();
    return new UsageWindow(
        w.provider(),
        w.providerName(),
        w.bucket(),
        w.label(),
        w.window(),
        w.usedFraction(),
        w.resetsAt(),
        w.plan(),
        w.source(),
        w.observedAt(),
        w.stale(),
        w.pace(),
        Math.min(skynet, w.usedFraction()));
  }

  /**
   * Las ventanas que piden atención (decisión 6 de CU): las que pasan del 90 % y las semanales que
   * van 15 puntos por encima del ritmo uniforme. No cuentan las que ya se han renovado.
   */
  @Transactional(readOnly = true)
  public List<QuotaWarning> warnings() {
    Instant now = time.now();
    List<QuotaWarning> warnings = new ArrayList<>();
    for (UsageWindows.Runner runner : latest(null).runners()) {
      for (UsageWindow w : runner.windows()) {
        if (w.resetsAt() != null && !now.isBefore(w.resetsAt())) {
          continue;
        }
        String reason = null;
        if (w.usedFraction() >= LIMIT) {
          reason = "limit";
        } else if (QuotaSample.SEVEN_DAY.equals(w.window())
            && w.pace() != null
            && w.usedFraction() - w.pace() >= AHEAD_OF_PACE) {
          reason = "pace";
        }
        if (reason != null) {
          warnings.add(
              new QuotaWarning(
                  runner.id(),
                  runner.name(),
                  w.provider(),
                  w.providerName(),
                  w.bucket(),
                  w.label(),
                  w.window(),
                  w.usedFraction(),
                  w.pace(),
                  w.resetsAt(),
                  reason));
        }
      }
    }
    return warnings;
  }

  /** Antigravity aún no es un proveedor de agentes: solo se lee su cuota. */
  static String displayName(String provider) {
    if ("antigravity".equals(provider)) {
      return "Antigravity CLI";
    }
    return AgentProviderKind.byId(provider).map(AgentProviderKind::displayName).orElse(provider);
  }

  /**
   * Borra las muestras y los intervalos de más de 35 días. Lo atribuido a cada agente se queda: la
   * ejecución sigue diciendo cuánto gastó.
   */
  @Scheduled(fixedDelayString = "${skynet.usage.purge-interval:1h}")
  @Transactional
  public void purge() {
    Timestamp before = Timestamp.from(time.now().minus(RETENTION));
    int deleted =
        jdbc.sql("DELETE FROM quota_snapshot WHERE observed_at < ?").param(before).update();
    jdbc.sql("DELETE FROM quota_interval WHERE ended_at < ?").param(before).update();
    if (deleted > 0) {
      log.info("Borradas {} muestras de cuota de más de {} días", deleted, RETENTION.toDays());
    }
  }
}
