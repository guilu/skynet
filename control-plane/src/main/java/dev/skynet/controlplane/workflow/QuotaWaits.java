package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.definition.AgentDefinition;
import dev.skynet.controlplane.shared.TimeSource;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Continuar tras la cuota (CQ-A): si una cuenta está sin cuota, hasta cuándo, y cuánto espera una
 * fase antes de seguir.
 *
 * <ul>
 *   <li>Una cuenta (un proveedor en un runner) está sin cuota si su última muestra de una ventana
 *       (CU) la tiene gastada y aún no se ha renovado, o si uno de sus agentes falló por {@code
 *       quota} y su ventana aún no se ha renovado (sin hora, durante {@link #BLIND_WAIT}).
 *   <li>Se espera a la ventana bloqueada que <b>más tarde</b> se renueva, más un margen (30 s) y
 *       una dispersión (hasta 60 s), para que las fases de una misma cuenta no despierten a la vez
 *       y la vuelvan a agotar. La dispersión sale del id del intento: siempre la misma.
 *   <li>Sin hora de renovación, {@link #BLIND_WAIT}, como mucho {@link #MAX_BLIND_WAITS} veces.
 *   <li>Con más espera de la máxima de {@link QuotaSettings}, o tras {@code maxWaits} esperas, no
 *       se espera: la fase falla.
 * </ul>
 */
@Component
public class QuotaWaits {

  /**
   * Lo que se espera además de la hora de renovación, porque los relojes no coinciden ({@code
   * skynet.quota.margin}, 30 s).
   */
  private final Duration margin;

  /** Dispersión máxima sobre el margen ({@code skynet.quota.jitter}, 60 s). */
  private final Duration jitter;

  /** Espera sin hora de renovación. */
  public static final Duration BLIND_WAIT = Duration.ofMinutes(30);

  public static final int MAX_BLIND_WAITS = 2;

  /** A partir de esta parte gastada, la ventana está a cero (los CLIs redondean). */
  static final double EXHAUSTED = 0.995;

  /**
   * Una cuenta sin cuota.
   *
   * @param window la ventana que bloquea ({@code five_hour} o {@code seven_day}), o {@code null}
   * @param resetsAt cuándo se renueva, o {@code null} si no se sabe
   */
  public record Blocked(String provider, String window, Instant resetsAt) {}

  /**
   * Cuánto esperar.
   *
   * @param blocked lo que se espera
   * @param notBefore cuándo puede seguir la fase
   * @param switches si no espera porque cambia de proveedor (CQ-B): a la reserva o, desde ella, de
   *     vuelta al principal
   */
  public record Wait(Blocked blocked, Instant notBefore, boolean switches) {

    public Wait(Blocked blocked, Instant notBefore) {
      this(blocked, notBefore, false);
    }
  }

  private final JdbcClient jdbc;
  private final QuotaSettings settings;
  private final TimeSource time;

  QuotaWaits(
      JdbcClient jdbc,
      QuotaSettings settings,
      TimeSource time,
      @Value("${skynet.quota.margin:30s}") Duration margin,
      @Value("${skynet.quota.jitter:60s}") Duration jitter) {
    this.jdbc = jdbc;
    this.settings = settings;
    this.time = time;
    this.margin = margin;
    this.jitter = jitter;
  }

  public Instant now() {
    return time.now();
  }

  public QuotaSettings.View settings() {
    return settings.view();
  }

  /**
   * Si la cuenta de {@code agent} (su proveedor en su runner) está sin cuota según su última
   * muestra: el respaldo para los proveedores que no avisan con un evento propio.
   */
  Optional<Blocked> exhausted(AgentRun agent) {
    if (agent.getRunnerId() == null || agent.getProvider() == null) {
      return Optional.empty();
    }
    return snapshotBlock(agent.getRunnerId(), agent.getProvider(), agent.getModel(), time.now());
  }

  /**
   * Si {@code provider} está sin cuota en todos los runners que podrían ejecutar el agente ({@code
   * runnerId} si ya está fijado): hasta cuándo, el primero que se renueva. Vacío si alguno tiene
   * cuota o no se sabe.
   */
  public Optional<Blocked> blocked(String provider, String model, UUID runnerId) {
    if (provider == null || !settings.view().enabled()) {
      return Optional.empty();
    }
    Instant now = time.now();
    List<UUID> runners =
        runnerId != null
            ? List.of(runnerId)
            : jdbc.sql(
                    "SELECT p.runner_id FROM runner_provider p JOIN runner r ON r.id = p.runner_id"
                        + " WHERE p.provider = ? AND r.archived_at IS NULL")
                .param(provider)
                .query(UUID.class)
                .list();
    Blocked first = null;
    for (UUID runner : runners) {
      Optional<Blocked> blocked = accountBlock(runner, provider, model, now);
      if (blocked.isEmpty()) {
        return Optional.empty();
      }
      Blocked b = blocked.get();
      if (first == null
          || (b.resetsAt() != null
              && first.resetsAt() != null
              && b.resetsAt().isBefore(first.resetsAt()))) {
        first = b;
      }
    }
    return Optional.ofNullable(first);
  }

  /**
   * Cuánto espera el intento {@code attemptId} tras un fallo {@code quota} de {@code agent}, o
   * vacío si no espera: apagado, el agente dice {@code onQuota: fail}, ya esperó {@code maxWaits}
   * veces o la renovación queda más allá de la espera máxima.
   *
   * <p>Con proveedor de reserva (CQ-B), no espera nada si el otro proveedor del agente (la reserva,
   * o el principal si el que falló era la reserva) tiene cuota y la espera pasa de {@code
   * fallbackAfterMinutes} (o de la máxima); al principal se vuelve en cuanto tiene cuota. Si los
   * dos están sin cuota, espera al primero que se renueve. Qué proveedor sigue lo decide {@link
   * #route} al arrancar.
   *
   * @param quotaFailures fallos {@code quota} de la fase, incluido este
   * @param blindFailures de ellos, los que no sabían cuándo se renovaba la cuota, incluido este
   */
  public Optional<Wait> afterFailure(
      AgentRun agent,
      AgentDefinition definition,
      int quotaFailures,
      int blindFailures,
      UUID attemptId) {
    QuotaSettings.View view = settings.view();
    if (!view.enabled()
        || (definition != null && !definition.waitsForQuota())
        || quotaFailures > view.maxWaits()) {
      return Optional.empty();
    }
    Instant now = time.now();
    Blocked own =
        new Blocked(agent.getProvider(), agent.getQuotaWindow(), agent.getQuotaResetsAt());
    Blocked blocked =
        latest(
            own,
            agent.getRunnerId() == null
                ? null
                : snapshotBlock(agent.getRunnerId(), agent.getProvider(), agent.getModel(), now)
                    .orElse(null));
    Optional<Wait> wait = waitFor(blocked, blindFailures, attemptId, now, view);
    if (definition == null || !definition.fallsBack()) {
      return wait;
    }
    boolean wasFallback = definition.fallback().provider().equals(agent.getProvider());
    String other = wasFallback ? primaryProvider(definition) : definition.fallback().provider();
    String otherModel = wasFallback ? definition.model() : definition.fallback().model();
    Optional<Blocked> otherBlocked = blocked(other, otherModel, null);
    if (otherBlocked.isEmpty()) {
      // El principal con cuota se usa enseguida; la reserva, solo si la espera es larga.
      return wasFallback || longWait(wait, now, view)
          ? Optional.of(new Wait(blocked, now, true))
          : wait;
    }
    return earliest(wait, waitFor(otherBlocked.get(), 0, attemptId, now, view));
  }

  /**
   * Con qué proveedor arranca ahora un agente con reserva (CQ-B): su reserva si el principal está
   * sin cuota, la reserva la tiene y la espera del principal pasa de {@code fallbackAfterMinutes}
   * (o de la máxima). Vacío: con el principal (si los dos están sin cuota, la comprobación previa
   * espera).
   *
   * @return lo que bloquea al principal
   */
  public Optional<Blocked> route(AgentDefinition definition, UUID attemptId) {
    QuotaSettings.View view = settings.view();
    if (!view.enabled() || definition == null || !definition.fallsBack()) {
      return Optional.empty();
    }
    Optional<Blocked> primary = blocked(primaryProvider(definition), definition.model(), null);
    if (primary.isEmpty()
        || blocked(definition.fallback().provider(), definition.fallback().model(), null)
            .isPresent()) {
      return Optional.empty();
    }
    Instant now = time.now();
    return longWait(waitFor(primary.get(), 0, attemptId, now, view), now, view)
        ? primary
        : Optional.empty();
  }

  /**
   * Cuánto espera un agente con reserva cuyo principal está sin cuota si la reserva también lo
   * está: hasta que se renueve la reserva. Vacío si la reserva tiene cuota o no hay.
   */
  public Optional<Wait> fallbackWait(AgentDefinition definition, UUID attemptId) {
    QuotaSettings.View view = settings.view();
    if (!view.enabled() || definition == null || !definition.fallsBack()) {
      return Optional.empty();
    }
    return blocked(definition.fallback().provider(), definition.fallback().model(), null)
        .flatMap(b -> waitFor(b, 0, attemptId, time.now(), view));
  }

  private static String primaryProvider(AgentDefinition definition) {
    return definition.provider() == null ? AgentDefinition.CLAUDE_CODE : definition.provider();
  }

  /** Si la espera pasa de la que se aguanta antes de cambiar a la reserva (o no se espera). */
  private static boolean longWait(Optional<Wait> wait, Instant now, QuotaSettings.View view) {
    return wait.isEmpty()
        || Duration.between(now, wait.get().notBefore()).compareTo(view.fallbackAfter()) > 0;
  }

  private static Optional<Wait> earliest(Optional<Wait> a, Optional<Wait> b) {
    if (a.isEmpty()) {
      return b;
    }
    if (b.isEmpty()) {
      return a;
    }
    return b.get().notBefore().isBefore(a.get().notBefore()) ? b : a;
  }

  /**
   * Cuánto espera una fase lista cuyo proveedor ya está sin cuota (la comprobación previa): no
   * cuenta como espera ni tiene tope de veces, porque no ha gastado nada.
   */
  public Optional<Wait> beforeStart(Blocked blocked, UUID attemptId) {
    QuotaSettings.View view = settings.view();
    if (!view.enabled()) {
      return Optional.empty();
    }
    return waitFor(blocked, 0, attemptId, time.now(), view);
  }

  private Optional<Wait> waitFor(
      Blocked blocked, int blindFailures, UUID attemptId, Instant now, QuotaSettings.View view) {
    Instant base;
    if (blocked.resetsAt() == null || !blocked.resetsAt().isAfter(now)) {
      if (blocked.resetsAt() == null) {
        if (blindFailures > MAX_BLIND_WAITS) {
          return Optional.empty();
        }
        base = now.plus(BLIND_WAIT);
      } else {
        base = now;
      }
    } else {
      base = blocked.resetsAt();
    }
    Duration maxWait = view.maxWait();
    if (maxWait != null && Duration.between(now, base).compareTo(maxWait) > 0) {
      return Optional.empty();
    }
    return Optional.of(new Wait(blocked, base.plus(margin).plus(jitter(attemptId, jitter))));
  }

  /** Una dispersión de 0 a {@code max}, siempre la misma para el mismo intento. */
  static Duration jitter(UUID attemptId, Duration max) {
    if (max.toMillis() <= 0) {
      return Duration.ZERO;
    }
    return Duration.ofMillis(Math.floorMod(attemptId.hashCode(), max.toMillis()));
  }

  private static Blocked latest(Blocked a, Blocked b) {
    if (b == null) {
      return a;
    }
    if (a.resetsAt() == null) {
      return b.resetsAt() == null && a.window() != null ? a : b;
    }
    return b.resetsAt() != null && b.resetsAt().isAfter(a.resetsAt()) ? b : a;
  }

  /**
   * Si la cuenta de {@code provider} en {@code runner} está sin cuota: por una muestra o por un
   * agente que falló por {@code quota} hace poco.
   */
  private Optional<Blocked> accountBlock(UUID runner, String provider, String model, Instant now) {
    Blocked found = snapshotBlock(runner, provider, model, now).orElse(null);
    List<Blocked> failures =
        jdbc.sql(
                "SELECT quota_window, quota_resets_at, finished_at FROM agent_run"
                    + " WHERE failure = 'QUOTA' AND provider = ? AND runner_id = ?"
                    + " AND (quota_resets_at > ? OR (quota_resets_at IS NULL AND finished_at > ?))")
            .params(provider, runner, Timestamp.from(now), Timestamp.from(now.minus(BLIND_WAIT)))
            .query(
                (rs, n) -> {
                  Timestamp resets = rs.getTimestamp("quota_resets_at");
                  Timestamp finished = rs.getTimestamp("finished_at");
                  // Sin hora, se da por bloqueada hasta media hora después del fallo.
                  Instant until =
                      resets != null
                          ? resets.toInstant()
                          : finished == null ? null : finished.toInstant().plus(BLIND_WAIT);
                  return new Blocked(provider, rs.getString("quota_window"), until);
                })
            .list();
    for (Blocked failure : failures) {
      found = found == null ? failure : latest(found, failure);
    }
    return Optional.ofNullable(found);
  }

  /**
   * La ventana gastada que más tarde se renueva en las últimas muestras de la cuenta. Solo cuentan
   * los grupos del modelo (en Antigravity, Gemini y «3p» tienen cuotas separadas).
   */
  private Optional<Blocked> snapshotBlock(UUID runner, String provider, String model, Instant now) {
    List<Object[]> rows =
        jdbc.sql(
                "SELECT DISTINCT ON (bucket, window_kind) bucket, window_kind, used_fraction,"
                    + " resets_at FROM quota_snapshot WHERE runner_id = ? AND provider = ?"
                    + " ORDER BY bucket, window_kind, observed_at DESC")
            .params(runner, provider)
            .query(
                (rs, n) ->
                    new Object[] {
                      rs.getString("bucket"),
                      rs.getString("window_kind"),
                      rs.getDouble("used_fraction"),
                      rs.getTimestamp("resets_at") == null
                          ? null
                          : rs.getTimestamp("resets_at").toInstant()
                    })
            .list();
    List<String> buckets = buckets(provider, model, rows);
    Blocked found = null;
    for (Object[] row : rows) {
      Instant resets = (Instant) row[3];
      if (!buckets.contains((String) row[0])
          || (double) row[2] < EXHAUSTED
          || resets == null
          || !resets.isAfter(now)) {
        continue;
      }
      Blocked b = new Blocked(provider, (String) row[1], resets);
      found = found == null ? b : latest(found, b);
    }
    return Optional.ofNullable(found);
  }

  /**
   * Los grupos de cuota que usa el modelo: en Antigravity, los de Gemini para un modelo Gemini (o
   * el de la cuenta) y {@code 3p} para Claude y GPT; en los demás, todos (solo tienen uno).
   */
  static List<String> buckets(String provider, String model, List<Object[]> rows) {
    List<String> all = new ArrayList<>();
    rows.forEach(r -> all.add((String) r[0]));
    if (!AgentDefinition.ANTIGRAVITY.equals(provider)) {
      return all;
    }
    String m = model == null ? "" : model.toLowerCase(Locale.ROOT);
    boolean thirdParty = m.contains("claude") || m.contains("gpt");
    boolean argon = m.contains("argon");
    // El grupo de Argon (gemini-argon-wtu) solo cuenta para Argon.
    return all.stream()
        .filter(
            b ->
                thirdParty
                    ? b.equals("3p")
                    : b.startsWith("gemini") && (argon || !b.contains("argon")))
        .toList();
  }
}
