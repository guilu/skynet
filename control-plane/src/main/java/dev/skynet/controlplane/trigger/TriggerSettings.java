package dev.skynet.controlplane.trigger;

import dev.skynet.controlplane.github.GitHubRepositories;
import dev.skynet.controlplane.shared.InvalidRequestException;
import dev.skynet.controlplane.shared.TimeSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Los ajustes de los disparadores, en {@code app_setting} bajo la clave {@code triggers}: el
 * interruptor general (apagado hasta que se enciende), quién puede lanzar, los repositorios de
 * órdenes y el freno diario (DS-C).
 */
@Service
class TriggerSettings {

  static final String KEY = "triggers";

  /** Ejecuciones lanzadas desde GitHub al día, si no se fija otra cifra (DS-C). */
  static final int DEFAULT_DAILY_LIMIT = 10;

  static final int MAX_DAILY_LIMIT = 1000;

  /** El día del freno diario empieza a medianoche en esta zona. */
  static final ZoneId ZONE = ZoneId.of("Europe/Madrid");

  private static final Pattern LOGIN = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})");
  private static final Pattern FULL_NAME = Pattern.compile("[A-Za-z0-9-]+/[A-Za-z0-9._-]+");

  /**
   * Lo guardado.
   *
   * @param authorizedLogins quién puede lanzar; {@code null} si nunca se ha cambiado: entonces
   *     valen las cuentas donde está instalada la App
   * @param dailyLimit ejecuciones lanzadas desde GitHub al día; {@code null}: {@link
   *     #DEFAULT_DAILY_LIMIT}
   */
  record Stored(
      boolean enabled,
      List<String> authorizedLogins,
      List<String> orderRepositories,
      Integer dailyLimit) {

    int limit() {
      return dailyLimit == null ? DEFAULT_DAILY_LIMIT : dailyLimit;
    }
  }

  private static final Stored EMPTY = new Stored(false, null, List.of(), null);

  /**
   * Lo que enseña la API.
   *
   * @param authorizedLogins quién puede lanzar ahora mismo, ya resuelto
   * @param authorizedByDefault si son las cuentas de la instalación porque no se ha fijado nadie
   * @param dailyLimit cuántas ejecuciones se pueden lanzar desde GitHub al día
   * @param launchedToday cuántas se han lanzado hoy (desde las 00:00 en Madrid)
   */
  record View(
      boolean enabled,
      List<String> authorizedLogins,
      boolean authorizedByDefault,
      List<String> orderRepositories,
      int dailyLimit,
      int launchedToday,
      Instant updatedAt) {}

  private final JdbcClient jdbc;
  private final ObjectMapper json;
  private final TimeSource time;
  private final GitHubRepositories github;

  TriggerSettings(JdbcClient jdbc, ObjectMapper json, TimeSource time, GitHubRepositories github) {
    this.jdbc = jdbc;
    this.json = json;
    this.time = time;
    this.github = github;
  }

  @Transactional(readOnly = true)
  View view() {
    Row row = row();
    Stored stored = row == null ? EMPTY : row.stored();
    return new View(
        stored.enabled(),
        authorized(stored),
        stored.authorizedLogins() == null,
        stored.orderRepositories(),
        stored.limit(),
        launchedToday(),
        row == null ? null : row.updatedAt());
  }

  /** Lo que llegue vacío ({@code null}) se queda como estaba. */
  @Transactional
  View save(
      Boolean enabled,
      List<String> authorizedLogins,
      List<String> orderRepositories,
      Integer dailyLimit) {
    if (dailyLimit != null && (dailyLimit < 0 || dailyLimit > MAX_DAILY_LIMIT)) {
      throw new InvalidRequestException(
          "dailyLimit tiene que estar entre 0 y " + MAX_DAILY_LIMIT + " (0 no deja lanzar nada)");
    }
    Row row = row();
    Stored current = row == null ? EMPTY : row.stored();
    Stored next =
        new Stored(
            enabled == null ? current.enabled() : enabled,
            authorizedLogins == null
                ? current.authorizedLogins()
                : normalized(authorizedLogins, LOGIN, "login de GitHub"),
            orderRepositories == null
                ? current.orderRepositories()
                : normalized(orderRepositories, FULL_NAME, "repositorio (owner/nombre)"),
            dailyLimit == null ? current.dailyLimit() : dailyLimit);
    jdbc.sql(
            "INSERT INTO app_setting (key, value, updated_at) VALUES (?, ?::jsonb, ?)"
                + " ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value,"
                + " updated_at = EXCLUDED.updated_at")
        .params(KEY, json.writeValueAsString(next), Timestamp.from(time.now()))
        .update();
    return view();
  }

  boolean enabled() {
    Row row = row();
    return row != null && row.stored().enabled();
  }

  /** Si {@code login} puede lanzar (sin distinguir mayúsculas). */
  boolean authorized(String login) {
    if (login == null || login.isBlank()) {
      return false;
    }
    Row row = row();
    return authorized(row == null ? EMPTY : row.stored()).contains(login.toLowerCase(Locale.ROOT));
  }

  /** Si {@code fullName} es un repositorio de órdenes (sin distinguir mayúsculas). */
  boolean isOrderRepository(String fullName) {
    Row row = row();
    return row != null
        && row.stored().orderRepositories().contains(fullName.toLowerCase(Locale.ROOT));
  }

  /** Cuántas ejecuciones se pueden lanzar desde GitHub al día. */
  int dailyLimit() {
    Row row = row();
    return (row == null ? EMPTY : row.stored()).limit();
  }

  /** Las peticiones de GitHub que han lanzado una ejecución hoy (desde las 00:00 en Madrid). */
  int launchedToday() {
    Instant midnight = time.now().atZone(ZONE).toLocalDate().atStartOfDay(ZONE).toInstant();
    return jdbc.sql(
            "SELECT count(*) FROM trigger_launch WHERE status IN ('LAUNCHED', 'FINISHED')"
                + " AND created_at >= ?")
        .param(Timestamp.from(midnight))
        .query(Integer.class)
        .single();
  }

  private List<String> authorized(Stored stored) {
    return stored.authorizedLogins() == null
        ? github.installationAccounts()
        : stored.authorizedLogins();
  }

  private static List<String> normalized(List<String> values, Pattern pattern, String what) {
    if (values.size() > 100) {
      throw new InvalidRequestException("Como mucho 100 en cada lista");
    }
    for (String value : values) {
      if (value == null || !pattern.matcher(value.strip()).matches()) {
        throw new InvalidRequestException("«" + value + "» no es un " + what);
      }
    }
    return values.stream()
        .map(v -> v.strip().toLowerCase(Locale.ROOT))
        .distinct()
        .sorted()
        .toList();
  }

  private record Row(Stored stored, Instant updatedAt) {}

  private Row row() {
    return jdbc.sql("SELECT value, updated_at FROM app_setting WHERE key = ?")
        .param(KEY)
        .query(
            (rs, n) -> {
              Stored s = json.readValue(rs.getString("value"), Stored.class);
              return new Row(
                  new Stored(
                      s.enabled(),
                      s.authorizedLogins(),
                      s.orderRepositories() == null ? List.of() : s.orderRepositories(),
                      s.dailyLimit()),
                  rs.getTimestamp("updated_at").toInstant());
            })
        .optional()
        .orElse(null);
  }
}
