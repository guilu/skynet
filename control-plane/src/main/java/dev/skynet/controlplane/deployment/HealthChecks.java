package dev.skynet.controlplane.deployment;

import dev.skynet.controlplane.deployment.Deployments.Deployment;
import dev.skynet.controlplane.deployment.Deployments.Health;
import dev.skynet.controlplane.deployment.Deployments.Status;
import dev.skynet.controlplane.event.EventDraft;
import dev.skynet.controlplane.event.EventStore;
import dev.skynet.controlplane.project.ProjectService;
import dev.skynet.controlplane.shared.InvalidRequestException;
import dev.skynet.controlplane.shared.NotFoundException;
import dev.skynet.controlplane.shared.PayloadRedactor;
import dev.skynet.controlplane.shared.TimeSource;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Las comprobaciones de salud de los entornos (DL-C): su configuración, que pone el admin, y el
 * registro de cada intento como evidencia.
 *
 * <p>Cuando un despliegue termina bien y su entorno tiene comprobación, {@link Deployments} lo deja
 * en {@code CHECKING}. {@link HealthProber} reclama los intentos que tocan, llama a la URL y {@link
 * #complete} decide: {@code HEALTHY} al primer intento bueno, o {@code UNHEALTHY} / {@code TIMEOUT}
 * al agotar los intentos. Una comprobación fallida no revierte nada.
 */
@Service
class HealthChecks {

  /**
   * La comprobación de un entorno.
   *
   * @param expectSha si la respuesta tiene que contener el SHA desplegado (sus 7 primeros
   *     caracteres bastan)
   */
  record HealthCheck(
      UUID repositoryId,
      String environment,
      String url,
      int timeoutSeconds,
      int attempts,
      int intervalSeconds,
      int initialDelaySeconds,
      boolean expectSha,
      boolean enabled,
      Instant createdAt,
      Instant updatedAt) {}

  /** Lo que llega para crearla o cambiarla; lo que llegue vacío toma su valor por defecto. */
  record Draft(
      String url,
      Integer timeoutSeconds,
      Integer attempts,
      Integer intervalSeconds,
      Integer initialDelaySeconds,
      Boolean expectSha,
      Boolean enabled) {}

  enum Outcome {
    OK,
    BAD_STATUS,
    SHA_MISMATCH,
    TIMEOUT,
    ERROR
  }

  /** Un intento reclamado, listo para llamar. */
  record Task(
      UUID deploymentId,
      String commitSha,
      int attempt,
      String url,
      int timeoutSeconds,
      boolean expectSha) {}

  /** Lo que salió de un intento. */
  record Result(Outcome outcome, Integer statusCode, long latencyMs, String detail) {}

  /** Un intento ya registrado. */
  record Probe(
      int attempt,
      String url,
      Outcome outcome,
      Integer statusCode,
      Integer latencyMs,
      String detail,
      Instant checkedAt) {}

  private static final Pattern ENVIRONMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

  private final JdbcClient jdbc;
  private final Deployments deployments;
  private final EventStore events;
  private final ProjectService projects;
  private final PayloadRedactor redactor;
  private final TimeSource time;

  HealthChecks(
      JdbcClient jdbc,
      Deployments deployments,
      EventStore events,
      ProjectService projects,
      PayloadRedactor redactor,
      TimeSource time) {
    this.jdbc = jdbc;
    this.deployments = deployments;
    this.events = events;
    this.projects = projects;
    this.redactor = redactor;
    this.time = time;
  }

  @Transactional(readOnly = true)
  List<HealthCheck> list(UUID repositoryId) {
    projects.getRepository(repositoryId);
    return jdbc.sql(
            "SELECT * FROM deployment_health_check WHERE repository_id = ? ORDER BY environment")
        .param(repositoryId)
        .query(HealthChecks::map)
        .list();
  }

  Optional<HealthCheck> find(UUID repositoryId, String environment) {
    return jdbc.sql(
            "SELECT * FROM deployment_health_check WHERE repository_id = ? AND environment = ?")
        .params(repositoryId, environment)
        .query(HealthChecks::map)
        .optional();
  }

  /** Crea o cambia la comprobación de un entorno. */
  @Transactional
  HealthCheck save(UUID repositoryId, String environment, Draft draft) {
    projects.requireActive(projects.getRepository(repositoryId));
    if (environment == null || !ENVIRONMENT.matcher(environment).matches()) {
      throw new InvalidRequestException("El entorno no es válido: " + environment);
    }
    Optional<HealthCheck> existing = find(repositoryId, environment);
    String url =
        url(draft.url() != null ? draft.url() : existing.map(HealthCheck::url).orElse(null));
    int timeout =
        range(
            draft.timeoutSeconds(),
            existing.map(HealthCheck::timeoutSeconds),
            5,
            1,
            60,
            "timeoutSeconds");
    int attempts =
        range(draft.attempts(), existing.map(HealthCheck::attempts), 6, 1, 30, "attempts");
    int interval =
        range(
            draft.intervalSeconds(),
            existing.map(HealthCheck::intervalSeconds),
            10,
            1,
            600,
            "intervalSeconds");
    int delay =
        range(
            draft.initialDelaySeconds(),
            existing.map(HealthCheck::initialDelaySeconds),
            15,
            0,
            600,
            "initialDelaySeconds");
    boolean expectSha =
        draft.expectSha() != null
            ? draft.expectSha()
            : existing.map(HealthCheck::expectSha).orElse(false);
    boolean enabled =
        draft.enabled() != null ? draft.enabled() : existing.map(HealthCheck::enabled).orElse(true);
    Timestamp now = Timestamp.from(time.now());
    jdbc.sql(
            "INSERT INTO deployment_health_check (repository_id, environment, url, timeout_seconds,"
                + " attempts, interval_seconds, initial_delay_seconds, expect_sha, enabled,"
                + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                + " ON CONFLICT (repository_id, environment) DO UPDATE SET url = EXCLUDED.url,"
                + " timeout_seconds = EXCLUDED.timeout_seconds, attempts = EXCLUDED.attempts,"
                + " interval_seconds = EXCLUDED.interval_seconds,"
                + " initial_delay_seconds = EXCLUDED.initial_delay_seconds,"
                + " expect_sha = EXCLUDED.expect_sha, enabled = EXCLUDED.enabled,"
                + " updated_at = EXCLUDED.updated_at")
        .params(
            repositoryId,
            environment,
            url,
            timeout,
            attempts,
            interval,
            delay,
            expectSha,
            enabled,
            now,
            now)
        .update();
    return find(repositoryId, environment).orElseThrow();
  }

  @Transactional
  void delete(UUID repositoryId, String environment) {
    projects.getRepository(repositoryId);
    int deleted =
        jdbc.sql("DELETE FROM deployment_health_check WHERE repository_id = ? AND environment = ?")
            .params(repositoryId, environment)
            .update();
    if (deleted == 0) {
      throw new NotFoundException("Comprobación de salud", environment);
    }
  }

  /**
   * Reclama los intentos que tocan. Cada uno queda reservado durante su plazo más un margen: si
   * Skynet se cae a mitad, otro lo retoma al vencer.
   */
  @Transactional
  List<Task> claim(int limit, Duration margin) {
    Instant now = time.now();
    List<Task> tasks =
        jdbc.sql(
                "SELECT d.id, d.commit_sha, d.health_attempts, c.url, c.timeout_seconds,"
                    + " c.expect_sha FROM deployment d"
                    + " LEFT JOIN deployment_health_check c ON c.repository_id = d.repository_id"
                    + " AND c.environment = d.environment"
                    + " WHERE d.health_next_at <= ? AND d.health = 'CHECKING'"
                    + " ORDER BY d.health_next_at LIMIT ? FOR UPDATE OF d SKIP LOCKED")
            .params(Timestamp.from(now), limit)
            .query(
                (rs, n) ->
                    new Task(
                        rs.getObject("id", UUID.class),
                        rs.getString("commit_sha"),
                        rs.getInt("health_attempts") + 1,
                        rs.getString("url"),
                        rs.getInt("timeout_seconds"),
                        rs.getBoolean("expect_sha")))
            .list();
    for (Task task : tasks) {
      if (task.url() == null) {
        // Se borró la comprobación mientras esperaba.
        jdbc.sql(
                "UPDATE deployment SET health = 'UNKNOWN', health_next_at = NULL,"
                    + " health_detail = 'Se quitó la comprobación del entorno' WHERE id = ?")
            .param(task.deploymentId())
            .update();
        continue;
      }
      Instant lease = now.plusSeconds(task.timeoutSeconds()).plus(margin);
      jdbc.sql("UPDATE deployment SET health_next_at = ? WHERE id = ?")
          .params(Timestamp.from(lease), task.deploymentId())
          .update();
    }
    return tasks.stream().filter(t -> t.url() != null).toList();
  }

  /** Registra el intento y decide la salud. */
  @Transactional
  void complete(Task task, Result result) {
    Deployment before = deployments.get(task.deploymentId());
    deployments.lockEnvironment(before.repositoryId(), before.environment());
    Deployment current = deployments.get(task.deploymentId());
    Instant now = time.now();
    String detail = result.detail() == null ? null : redactor.redact(truncate(result.detail()));
    jdbc.sql(
            "INSERT INTO deployment_health_probe (id, deployment_id, attempt, url, outcome,"
                + " status_code, latency_ms, detail, checked_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
        .params(
            UUID.randomUUID(),
            task.deploymentId(),
            task.attempt(),
            task.url(),
            result.outcome().name(),
            result.statusCode(),
            (int) Math.min(result.latencyMs(), Integer.MAX_VALUE),
            detail,
            Timestamp.from(now))
        .update();
    if (current.health() != Health.CHECKING
        || current.status() != Status.SUCCEEDED
        || current.healthAttempts() + 1 != task.attempt()) {
      // Ya no se comprueba este despliegue (otro estado, sustituido o reprogramado): solo queda la
      // evidencia.
      return;
    }
    HealthCheck check = find(current.repositoryId(), current.environment()).orElse(null);
    Health health = null;
    Instant next = null;
    if (result.outcome() == Outcome.OK) {
      health = Health.HEALTHY;
    } else if (check == null || !check.enabled() || task.attempt() >= check.attempts()) {
      health = result.outcome() == Outcome.TIMEOUT ? Health.TIMEOUT : Health.UNHEALTHY;
    } else {
      next = now.plusSeconds(check.intervalSeconds());
    }
    String summary = summary(task, result, detail);
    jdbc.sql(
            "UPDATE deployment SET health = ?, health_attempts = ?, health_next_at = ?,"
                + " health_detail = ?, health_checked_at = ?, updated_at = ? WHERE id = ?")
        .params(
            (health == null ? Health.CHECKING : health).name(),
            task.attempt(),
            next == null ? null : Timestamp.from(next),
            summary,
            Timestamp.from(now),
            Timestamp.from(now),
            task.deploymentId())
        .update();
    if (health != null) {
      Map<String, Object> payload = Deployments.describe(deployments.get(task.deploymentId()));
      payload.put("health", health.name());
      payload.put("attempts", task.attempt());
      payload.put("detail", summary);
      events.append(
          EventDraft.of(
              Deployments.AGGREGATE, task.deploymentId(), "deployment.health", null, payload, now));
    }
  }

  @Transactional(readOnly = true)
  List<Probe> probes(UUID deploymentId) {
    return jdbc.sql(
            "SELECT * FROM deployment_health_probe WHERE deployment_id = ? ORDER BY checked_at, attempt")
        .param(deploymentId)
        .query(
            (rs, n) -> {
              int code = rs.getInt("status_code");
              Integer statusCode = rs.wasNull() ? null : code;
              int latency = rs.getInt("latency_ms");
              Integer latencyMs = rs.wasNull() ? null : latency;
              return new Probe(
                  rs.getInt("attempt"),
                  rs.getString("url"),
                  Outcome.valueOf(rs.getString("outcome")),
                  statusCode,
                  latencyMs,
                  rs.getString("detail"),
                  rs.getTimestamp("checked_at").toInstant());
            })
        .list();
  }

  private static String summary(Task task, Result result, String detail) {
    String base =
        switch (result.outcome()) {
          case OK -> "Responde " + result.statusCode() + " en " + result.latencyMs() + " ms";
          case BAD_STATUS -> "Responde " + result.statusCode();
          case SHA_MISMATCH -> "Responde, pero no con el SHA " + task.commitSha().substring(0, 7);
          case TIMEOUT -> "Sin respuesta en " + task.timeoutSeconds() + " s";
          case ERROR -> "No se pudo llamar";
        };
    return "Intento " + task.attempt() + ": " + base + (detail == null ? "" : " (" + detail + ")");
  }

  /**
   * La URL de la comprobación: http(s), con host, sin usuario ni fragmento. Skynet la llama sin
   * seguir redirecciones.
   */
  static String url(String value) {
    if (value == null || value.isBlank()) {
      throw new InvalidRequestException("Falta la URL de la comprobación");
    }
    String url = value.strip();
    try {
      URI uri = new URI(url);
      String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
      if (!(scheme.equals("http") || scheme.equals("https"))
          || uri.getHost() == null
          || uri.getRawUserInfo() != null
          || uri.getRawFragment() != null
          || url.length() > 500
          || !url.matches("https?://[^\\s@/]+(/[^\\s#]*)?")) {
        throw new InvalidRequestException(
            "La URL de la comprobación tiene que ser http(s), sin usuario ni fragmento");
      }
    } catch (URISyntaxException e) {
      throw new InvalidRequestException("La URL de la comprobación no es válida");
    }
    return url;
  }

  private static int range(
      Integer value, Optional<Integer> existing, int fallback, int min, int max, String name) {
    int v = value != null ? value : existing.orElse(fallback);
    if (v < min || v > max) {
      throw new InvalidRequestException(name + " va de " + min + " a " + max);
    }
    return v;
  }

  private static String truncate(String value) {
    return value.length() <= 500 ? value : value.substring(0, 500);
  }

  private static HealthCheck map(ResultSet rs, int row) throws SQLException {
    return new HealthCheck(
        rs.getObject("repository_id", UUID.class),
        rs.getString("environment"),
        rs.getString("url"),
        rs.getInt("timeout_seconds"),
        rs.getInt("attempts"),
        rs.getInt("interval_seconds"),
        rs.getInt("initial_delay_seconds"),
        rs.getBoolean("expect_sha"),
        rs.getBoolean("enabled"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }
}
