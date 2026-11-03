package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.shared.InvalidRequestException;
import dev.skynet.controlplane.shared.TimeSource;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Los ajustes de «Continuar tras la cuota» (CQ-A), en {@code app_setting} bajo la clave {@code
 * quota}: si está encendido (lo está hasta que se apaga), cuánto se espera como mucho a que se
 * renueve una ventana, cuántas veces puede esperar una fase y, con proveedor de reserva (CQ-B), a
 * partir de cuántos minutos de espera se cambia a él.
 */
@Service
public class QuotaSettings {

  static final String KEY = "quota";

  /** Espera máxima si no se fija otra (horas). */
  public static final int DEFAULT_MAX_WAIT_HOURS = 6;

  /** Como mucho, 30 días: «sin límite» es {@code 0}. */
  public static final int MAX_WAIT_HOURS = 24 * 30;

  /** Esperas por fase si no se fija otra cifra. */
  public static final int DEFAULT_MAX_WAITS = 4;

  public static final int MAX_WAITS = 20;

  /** Minutos de espera a partir de los que se cambia a la reserva si no se fija otra cifra. */
  public static final int DEFAULT_FALLBACK_AFTER_MINUTES = 15;

  public static final int MAX_FALLBACK_AFTER_MINUTES = 24 * 60;

  /**
   * Lo guardado; lo que es {@code null}, por defecto.
   *
   * @param maxWaitHours horas que se espera como mucho; {@code 0}, sin límite
   */
  record Stored(
      Boolean enabled, Integer maxWaitHours, Integer maxWaits, Integer fallbackAfterMinutes) {}

  private static final Stored EMPTY = new Stored(null, null, null, null);

  /**
   * Lo que se aplica.
   *
   * @param maxWaitHours horas que se espera como mucho; {@code 0}, sin límite
   * @param maxWaits veces que puede esperar una fase
   * @param fallbackAfterMinutes con reserva, a partir de cuántos minutos de espera se cambia a
   *     ella; {@code 0}, siempre
   */
  public record View(
      boolean enabled,
      int maxWaitHours,
      int maxWaits,
      int fallbackAfterMinutes,
      Instant updatedAt) {

    /** La espera máxima, o {@code null} si no hay límite. */
    public Duration maxWait() {
      return maxWaitHours == 0 ? null : Duration.ofHours(maxWaitHours);
    }

    /** A partir de cuánta espera se cambia a la reserva. */
    public Duration fallbackAfter() {
      return Duration.ofMinutes(fallbackAfterMinutes);
    }
  }

  private record Row(Stored stored, Instant updatedAt) {}

  private final JdbcClient jdbc;
  private final ObjectMapper json;
  private final TimeSource time;

  QuotaSettings(JdbcClient jdbc, ObjectMapper json, TimeSource time) {
    this.jdbc = jdbc;
    this.json = json;
    this.time = time;
  }

  @Transactional(readOnly = true)
  public View view() {
    Row row = row();
    Stored stored = row == null ? EMPTY : row.stored();
    return new View(
        stored.enabled() == null || stored.enabled(),
        stored.maxWaitHours() == null ? DEFAULT_MAX_WAIT_HOURS : stored.maxWaitHours(),
        stored.maxWaits() == null ? DEFAULT_MAX_WAITS : stored.maxWaits(),
        stored.fallbackAfterMinutes() == null
            ? DEFAULT_FALLBACK_AFTER_MINUTES
            : stored.fallbackAfterMinutes(),
        row == null ? null : row.updatedAt());
  }

  /** Lo que llegue vacío ({@code null}) se queda como estaba. */
  @Transactional
  public View save(
      Boolean enabled, Integer maxWaitHours, Integer maxWaits, Integer fallbackAfterMinutes) {
    if (maxWaitHours != null && (maxWaitHours < 0 || maxWaitHours > MAX_WAIT_HOURS)) {
      throw new InvalidRequestException(
          "maxWaitHours tiene que estar entre 0 (sin límite) y " + MAX_WAIT_HOURS);
    }
    if (maxWaits != null && (maxWaits < 1 || maxWaits > MAX_WAITS)) {
      throw new InvalidRequestException("maxWaits tiene que estar entre 1 y " + MAX_WAITS);
    }
    if (fallbackAfterMinutes != null
        && (fallbackAfterMinutes < 0 || fallbackAfterMinutes > MAX_FALLBACK_AFTER_MINUTES)) {
      throw new InvalidRequestException(
          "fallbackAfterMinutes tiene que estar entre 0 y " + MAX_FALLBACK_AFTER_MINUTES);
    }
    Row row = row();
    Stored current = row == null ? EMPTY : row.stored();
    Stored next =
        new Stored(
            enabled == null ? current.enabled() : enabled,
            maxWaitHours == null ? current.maxWaitHours() : maxWaitHours,
            maxWaits == null ? current.maxWaits() : maxWaits,
            fallbackAfterMinutes == null ? current.fallbackAfterMinutes() : fallbackAfterMinutes);
    jdbc.sql(
            "INSERT INTO app_setting (key, value, updated_at) VALUES (?, ?::jsonb, ?)"
                + " ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value,"
                + " updated_at = EXCLUDED.updated_at")
        .params(KEY, json.writeValueAsString(next), Timestamp.from(time.now()))
        .update();
    return view();
  }

  private Row row() {
    return jdbc.sql("SELECT value, updated_at FROM app_setting WHERE key = ?")
        .param(KEY)
        .query(
            (rs, n) ->
                new Row(
                    json.readValue(rs.getString("value"), Stored.class),
                    rs.getTimestamp("updated_at").toInstant()))
        .optional()
        .orElse(null);
  }
}
