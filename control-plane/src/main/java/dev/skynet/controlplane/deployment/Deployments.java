package dev.skynet.controlplane.deployment;

import dev.skynet.controlplane.event.EventDraft;
import dev.skynet.controlplane.event.EventStore;
import dev.skynet.controlplane.project.ProjectService;
import dev.skynet.controlplane.shared.ConflictException;
import dev.skynet.controlplane.shared.InvalidRequestException;
import dev.skynet.controlplane.shared.NotFoundException;
import dev.skynet.controlplane.shared.PayloadRedactor;
import dev.skynet.controlplane.shared.TimeSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Los despliegues de cada repositorio y entorno.
 *
 * <p>Cada despliegue tiene dos ejes que no se mezclan: su {@link Status} (cómo terminó el
 * despliegue) y su {@link Health} (si lo desplegado responde, lo decide DL-C). Un despliegue que
 * termina bien en un entorno deja {@link Status#SUPERSEDED} a los anteriores de ese entorno: así
 * una comprobación nunca se atribuye a un SHA que ya no está desplegado.
 *
 * <p>Es idempotente: el mismo despliegue ({@code externalId}) o el mismo estado se registran una
 * vez, y un estado más viejo que llega tarde se guarda en la historia pero no cambia el despliegue.
 * Los cambios de un entorno se serializan con un bloqueo de la transacción.
 */
@Service
public class Deployments {

  public enum Status {
    PENDING,
    IN_PROGRESS,
    SUCCEEDED,
    FAILED,
    SUPERSEDED;

    boolean finished() {
      return this == SUCCEEDED || this == FAILED;
    }

    /** A igual hora, gana el más avanzado: un final pisa a un «en curso». */
    int rank() {
      return switch (this) {
        case PENDING -> 0;
        case IN_PROGRESS -> 1;
        case SUCCEEDED, FAILED -> 2;
        case SUPERSEDED -> 3;
      };
    }
  }

  public enum Health {
    UNKNOWN,
    CHECKING,
    HEALTHY,
    UNHEALTHY,
    TIMEOUT
  }

  /**
   * Un despliegue tal como lo cuenta su origen.
   *
   * @param externalId su id en el origen (el de GitHub Deployments); la clave de idempotencia
   * @param ref la rama o la referencia desplegada
   * @param url dónde queda la aplicación; solo se enseña, nunca se llama
   * @param requestedAt cuándo se creó en el origen; ordena los despliegues de un entorno
   */
  public record NewDeployment(
      UUID repositoryId,
      String environment,
      String externalId,
      String commitSha,
      String ref,
      Integer pullRequestNumber,
      String url,
      String creator,
      Instant requestedAt) {}

  /**
   * Un estado de un despliegue.
   *
   * @param externalId su id en el origen; la clave de idempotencia
   * @param state {@code PENDING}, {@code IN_PROGRESS}, {@code SUCCEEDED} o {@code FAILED}
   * @param description el texto del origen; en un fallo, el error. Pasa por el redactor.
   * @param url dónde queda la aplicación según este estado, o {@code null}; si no es una URL
   *     http(s) sin credenciales, se descarta. Solo se enseña.
   */
  public record StatusUpdate(
      String externalId, Status state, String description, Instant occurredAt, String url) {

    public StatusUpdate(String externalId, Status state, String description, Instant occurredAt) {
      this(externalId, state, description, occurredAt, null);
    }
  }

  public record Deployment(
      UUID id,
      UUID repositoryId,
      String environment,
      String externalId,
      String commitSha,
      String ref,
      Integer pullRequestNumber,
      String url,
      String creator,
      Status status,
      Health health,
      String error,
      Instant requestedAt,
      Instant startedAt,
      Instant finishedAt,
      Instant statusAt,
      UUID supersededBy,
      Instant createdAt,
      Instant updatedAt,
      String healthDetail,
      Instant healthCheckedAt,
      int healthAttempts,
      Instant stuckAt) {}

  /**
   * Un estado recibido; {@code applied} falso si llegó tarde o el despliegue ya estaba superado.
   */
  public record StatusRecord(
      UUID id,
      String externalId,
      Status state,
      String description,
      Instant occurredAt,
      boolean applied,
      Instant receivedAt) {}

  /**
   * Un entorno de un repositorio.
   *
   * @param deployed lo que está desplegado ahora: el último despliegue que terminó bien
   * @param latest el último despliegue pedido, termine como termine
   */
  public record EnvironmentState(String environment, Deployment deployed, Deployment latest) {}

  static final String AGGREGATE = "deployment";
  static final int MAX_LIMIT = 200;

  private static final Pattern ENVIRONMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
  private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");
  private static final Pattern SHA_PREFIX = Pattern.compile("[0-9a-f]{7,40}");
  private static final int MAX_TEXT = 2000;

  private final JdbcClient jdbc;
  private final EventStore events;
  private final ProjectService projects;
  private final PayloadRedactor redactor;
  private final TimeSource time;

  Deployments(
      JdbcClient jdbc,
      EventStore events,
      ProjectService projects,
      PayloadRedactor redactor,
      TimeSource time) {
    this.jdbc = jdbc;
    this.events = events;
    this.projects = projects;
    this.redactor = redactor;
    this.time = time;
  }

  /**
   * Registra un despliegue. Si ya estaba (mismo repositorio y {@code externalId}), devuelve el
   * existente sin tocarlo.
   */
  @Transactional
  public Deployment register(NewDeployment draft) {
    NewDeployment d = normalized(draft);
    projects.getRepository(d.repositoryId());
    lockEnvironment(d.repositoryId(), d.environment());
    Optional<Deployment> existing = findByExternalId(d.repositoryId(), d.externalId());
    if (existing.isPresent()) {
      return existing.get();
    }
    UUID id = UUID.randomUUID();
    Timestamp now = Timestamp.from(time.now());
    jdbc.sql(
            "INSERT INTO deployment (id, repository_id, environment, external_id, commit_sha, ref,"
                + " pull_request_number, url, creator, requested_at, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
        .params(
            id,
            d.repositoryId(),
            d.environment(),
            d.externalId(),
            d.commitSha(),
            d.ref(),
            d.pullRequestNumber(),
            d.url(),
            d.creator(),
            Timestamp.from(d.requestedAt()),
            now,
            now)
        .update();
    Deployment created = get(id);
    Map<String, Object> payload = describe(created);
    events.append(EventDraft.of(AGGREGATE, id, "deployment.created", null, payload, time.now()));
    return created;
  }

  /**
   * Aplica un estado al despliegue. El mismo estado dos veces no hace nada; uno más viejo que el
   * último aplicado se guarda en la historia sin cambiar el despliegue.
   */
  @Transactional
  public Deployment record(UUID deploymentId, StatusUpdate draft) {
    Deployment current = get(deploymentId);
    StatusUpdate update = normalized(draft);
    lockEnvironment(current.repositoryId(), current.environment());
    current = get(deploymentId);
    boolean known =
        jdbc.sql(
                    "SELECT count(*) FROM deployment_status WHERE deployment_id = ? AND external_id = ?")
                .params(deploymentId, update.externalId())
                .query(Integer.class)
                .single()
            > 0;
    if (known) {
      return current;
    }
    boolean applies = applies(current, update);
    Instant now = time.now();
    jdbc.sql(
            "INSERT INTO deployment_status (id, deployment_id, external_id, state, description,"
                + " occurred_at, applied, received_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
        .params(
            UUID.randomUUID(),
            deploymentId,
            update.externalId(),
            update.state().name(),
            update.description(),
            Timestamp.from(update.occurredAt()),
            applies,
            Timestamp.from(now))
        .update();
    Map<String, Object> payload = describe(current);
    payload.put("state", update.state().name());
    payload.put("previous", current.status().name());
    payload.put("applied", applies);
    payload.put("statusId", update.externalId());
    if (!applies) {
      events.append(
          EventDraft.of(AGGREGATE, deploymentId, "deployment.status", null, payload, now));
      return current;
    }
    Timestamp at = Timestamp.from(update.occurredAt());
    Instant started =
        current.startedAt() == null && update.state() == Status.IN_PROGRESS
            ? update.occurredAt()
            : current.startedAt();
    jdbc.sql(
            "UPDATE deployment SET status = ?, status_at = ?, updated_at = ?, started_at = ?,"
                + " finished_at = ?, error = ?, url = ?, stuck_at = NULL,"
                // Un estado nuevo empieza otra fase: la salud de la anterior ya no vale.
                + " health = 'UNKNOWN', health_detail = NULL, health_next_at = NULL,"
                + " health_attempts = 0 WHERE id = ?")
        .params(
            update.state().name(),
            at,
            Timestamp.from(now),
            started == null ? null : Timestamp.from(started),
            update.state().finished() ? at : null,
            update.state() == Status.FAILED ? update.description() : null,
            update.url() == null ? current.url() : update.url(),
            deploymentId)
        .update();
    events.append(EventDraft.of(AGGREGATE, deploymentId, "deployment.status", null, payload, now));
    if (update.state() == Status.SUCCEEDED) {
      supersede(get(deploymentId), now);
      Deployment succeeded = get(deploymentId);
      if (succeeded.status() == Status.SUCCEEDED) {
        scheduleHealth(succeeded, false, now);
      }
    }
    return get(deploymentId);
  }

  /**
   * Vuelve a comprobar la salud de lo desplegado ahora, sin esperar el retraso inicial.
   *
   * @throws ConflictException si el despliegue no es el desplegado de su entorno o el entorno no
   *     tiene comprobación
   */
  @Transactional
  public Deployment recheck(UUID deploymentId) {
    Deployment current = get(deploymentId);
    lockEnvironment(current.repositoryId(), current.environment());
    current = get(deploymentId);
    if (current.status() != Status.SUCCEEDED) {
      throw new ConflictException(
          "Solo se comprueba lo desplegado: este despliegue está " + current.status());
    }
    if (!scheduleHealth(current, true, time.now())) {
      throw new ConflictException(
          "El entorno " + current.environment() + " no tiene una comprobación de salud activa");
    }
    return get(deploymentId);
  }

  /**
   * Programa la comprobación de salud si el entorno la tiene activa (DL-C).
   *
   * @param immediately si empieza ya o tras el retraso inicial de la comprobación
   * @return si se ha programado
   */
  private boolean scheduleHealth(Deployment deployment, boolean immediately, Instant now) {
    Optional<Integer> delay =
        jdbc.sql(
                "SELECT initial_delay_seconds FROM deployment_health_check"
                    + " WHERE repository_id = ? AND environment = ? AND enabled")
            .params(deployment.repositoryId(), deployment.environment())
            .query(Integer.class)
            .optional();
    if (delay.isEmpty()) {
      return false;
    }
    Instant next = immediately ? now : now.plusSeconds(delay.get());
    jdbc.sql(
            "UPDATE deployment SET health = 'CHECKING', health_detail = NULL, health_attempts = 0,"
                + " health_next_at = ?, updated_at = ? WHERE id = ?")
        .params(Timestamp.from(next), Timestamp.from(now), deployment.id())
        .update();
    Map<String, Object> payload = describe(get(deployment.id()));
    payload.put("health", Health.CHECKING.name());
    events.append(
        EventDraft.of(AGGREGATE, deployment.id(), "deployment.health", null, payload, now));
    return true;
  }

  private static boolean applies(Deployment current, StatusUpdate update) {
    if (current.status() == Status.SUPERSEDED) {
      return false;
    }
    if (current.statusAt() == null) {
      return true;
    }
    int order = update.occurredAt().compareTo(current.statusAt());
    return order > 0 || (order == 0 && update.state().rank() > current.status().rank());
  }

  /**
   * Tras un despliegue que termina bien: los anteriores de su entorno quedan superados por él y, si
   * ya había uno posterior que terminó bien, es este el que queda superado.
   */
  private void supersede(Deployment succeeded, Instant now) {
    Optional<UUID> newer =
        jdbc.sql(
                "SELECT id FROM deployment WHERE repository_id = ? AND environment = ? AND id <> ?"
                    + " AND status = 'SUCCEEDED' AND (requested_at, created_at) > (?, ?)"
                    + " ORDER BY requested_at DESC, created_at DESC LIMIT 1")
            .params(
                succeeded.repositoryId(),
                succeeded.environment(),
                succeeded.id(),
                Timestamp.from(succeeded.requestedAt()),
                Timestamp.from(succeeded.createdAt()))
            .query(UUID.class)
            .optional();
    if (newer.isPresent()) {
      markSuperseded(List.of(succeeded.id()), newer.get(), now);
      return;
    }
    List<UUID> older =
        jdbc.sql(
                "SELECT id FROM deployment WHERE repository_id = ? AND environment = ? AND id <> ?"
                    + " AND status IN ('PENDING', 'IN_PROGRESS', 'SUCCEEDED')"
                    + " AND (requested_at, created_at) < (?, ?) ORDER BY requested_at")
            .params(
                succeeded.repositoryId(),
                succeeded.environment(),
                succeeded.id(),
                Timestamp.from(succeeded.requestedAt()),
                Timestamp.from(succeeded.createdAt()))
            .query(UUID.class)
            .list();
    markSuperseded(older, succeeded.id(), now);
  }

  private void markSuperseded(List<UUID> ids, UUID by, Instant now) {
    for (UUID id : ids) {
      jdbc.sql(
              "UPDATE deployment SET status = 'SUPERSEDED', superseded_by = ?, updated_at = ?,"
                  // Lo que se comprobaba ya no está desplegado: la comprobación se abandona.
                  + " health_next_at = NULL,"
                  + " health_detail = CASE WHEN health = 'CHECKING'"
                  + " THEN 'Sustituido antes de terminar la comprobación' ELSE health_detail END,"
                  + " health = CASE WHEN health = 'CHECKING' THEN 'UNKNOWN' ELSE health END"
                  + " WHERE id = ?")
          .params(by, Timestamp.from(now), id)
          .update();
      Map<String, Object> payload = describe(get(id));
      payload.put("supersededBy", by.toString());
      events.append(EventDraft.of(AGGREGATE, id, "deployment.superseded", null, payload, now));
    }
  }

  /**
   * Bloquea a la vez el entorno en varios repositorios, antes de registrar ningún evento: así el
   * bloqueo del entorno se toma siempre antes que el de la secuencia de eventos.
   */
  @Transactional
  void lockEnvironments(java.util.Collection<UUID> repositoryIds, String environment) {
    repositoryIds.stream().sorted().forEach(id -> lockEnvironment(id, environment));
  }

  /**
   * Marca como atascados los despliegues sin estado final desde hace más de {@code after}.
   *
   * @return cuántos ha marcado
   */
  @Transactional
  public int markStuck(java.time.Duration after) {
    Instant now = time.now();
    Timestamp limit = Timestamp.from(now.minus(after));
    String due =
        " WHERE status IN ('PENDING', 'IN_PROGRESS') AND stuck_at IS NULL"
            + " AND coalesce(status_at, requested_at) < ?";
    // Primero los entornos, en orden, y después las filas y los eventos: el mismo orden de
    // bloqueos que record(), para no cruzarse con él.
    jdbc
        .sql("SELECT DISTINCT repository_id, environment FROM deployment" + due)
        .param(limit)
        .query((rs, n) -> rs.getObject(1, UUID.class) + ":" + rs.getString(2))
        .list()
        .stream()
        .sorted()
        .forEach(
            key -> {
              int colon = key.indexOf(':');
              lockEnvironment(UUID.fromString(key.substring(0, colon)), key.substring(colon + 1));
            });
    List<UUID> stuck =
        jdbc.sql("UPDATE deployment SET stuck_at = ?, updated_at = ?" + due + " RETURNING id")
            .params(Timestamp.from(now), Timestamp.from(now), limit)
            .query(UUID.class)
            .list();
    for (UUID id : stuck) {
      Map<String, Object> payload = describe(get(id));
      payload.put("stuckAfterMinutes", after.toMinutes());
      events.append(EventDraft.of(AGGREGATE, id, "deployment.stuck", null, payload, now));
    }
    return stuck.size();
  }

  /** Serializa los cambios de un entorno: dos despliegues del mismo destino no se cruzan. */
  void lockEnvironment(UUID repositoryId, String environment) {
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
        .param("deployment:" + repositoryId + ":" + environment)
        .query(Object.class)
        .single();
  }

  @Transactional(readOnly = true)
  public Deployment get(UUID id) {
    return jdbc.sql("SELECT * FROM deployment WHERE id = ?")
        .param(id)
        .query(Deployments::map)
        .optional()
        .orElseThrow(() -> new NotFoundException("Despliegue", id));
  }

  @Transactional(readOnly = true)
  public Optional<Deployment> findByExternalId(UUID repositoryId, String externalId) {
    return jdbc.sql("SELECT * FROM deployment WHERE repository_id = ? AND external_id = ?")
        .params(repositoryId, externalId)
        .query(Deployments::map)
        .optional();
  }

  /** Los despliegues de un repositorio, del más reciente al más viejo. */
  @Transactional(readOnly = true)
  public List<Deployment> list(UUID repositoryId, String environment, String sha, int limit) {
    projects.getRepository(repositoryId);
    if (environment != null && !ENVIRONMENT.matcher(environment).matches()) {
      throw new InvalidRequestException("El entorno no es válido: " + environment);
    }
    String prefix = sha == null ? null : sha.toLowerCase(Locale.ROOT);
    if (prefix != null && !SHA_PREFIX.matcher(prefix).matches()) {
      throw new InvalidRequestException(
          "El SHA tiene que tener de 7 a 40 caracteres hexadecimales");
    }
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new InvalidRequestException("El límite va de 1 a " + MAX_LIMIT);
    }
    return jdbc.sql(
            "SELECT * FROM deployment WHERE repository_id = :repository"
                + " AND (CAST(:environment AS text) IS NULL OR environment = :environment)"
                + " AND (CAST(:sha AS text) IS NULL OR commit_sha LIKE :sha || '%')"
                + " ORDER BY requested_at DESC, created_at DESC LIMIT :limit")
        .param("repository", repositoryId)
        .param("environment", environment)
        .param("sha", prefix)
        .param("limit", limit)
        .query(Deployments::map)
        .list();
  }

  /** Cada entorno del repositorio: lo desplegado ahora y el último despliegue pedido. */
  @Transactional(readOnly = true)
  public List<EnvironmentState> environments(UUID repositoryId) {
    projects.getRepository(repositoryId);
    List<String> names =
        jdbc.sql(
                "SELECT DISTINCT environment FROM deployment WHERE repository_id = ?"
                    + " ORDER BY environment")
            .param(repositoryId)
            .query(String.class)
            .list();
    return names.stream()
        .map(
            name ->
                new EnvironmentState(
                    name,
                    latest(repositoryId, name, "AND status = 'SUCCEEDED'").orElse(null),
                    latest(repositoryId, name, "").orElseThrow()))
        .toList();
  }

  private Optional<Deployment> latest(UUID repositoryId, String environment, String filter) {
    return jdbc.sql(
            "SELECT * FROM deployment WHERE repository_id = ? AND environment = ? "
                + filter
                + " ORDER BY requested_at DESC, created_at DESC LIMIT 1")
        .params(repositoryId, environment)
        .query(Deployments::map)
        .optional();
  }

  /** Los estados recibidos de un despliegue, en el orden en que ocurrieron. */
  @Transactional(readOnly = true)
  public List<StatusRecord> statuses(UUID deploymentId) {
    get(deploymentId);
    return jdbc.sql(
            "SELECT * FROM deployment_status WHERE deployment_id = ?"
                + " ORDER BY occurred_at, received_at")
        .param(deploymentId)
        .query(
            (rs, n) ->
                new StatusRecord(
                    rs.getObject("id", UUID.class),
                    rs.getString("external_id"),
                    Status.valueOf(rs.getString("state")),
                    rs.getString("description"),
                    instant(rs, "occurred_at"),
                    rs.getBoolean("applied"),
                    instant(rs, "received_at")))
        .list();
  }

  private NewDeployment normalized(NewDeployment d) {
    Objects.requireNonNull(d.repositoryId(), "repositoryId");
    String environment = required(d.environment(), "entorno");
    if (!ENVIRONMENT.matcher(environment).matches()) {
      throw new InvalidRequestException("El entorno no es válido: " + environment);
    }
    String sha = required(d.commitSha(), "commitSha").toLowerCase(Locale.ROOT);
    if (!SHA.matcher(sha).matches()) {
      throw new InvalidRequestException(
          "El commitSha tiene que ser un SHA completo de 40 caracteres");
    }
    if (d.pullRequestNumber() != null && d.pullRequestNumber() < 1) {
      throw new InvalidRequestException("El número de PR no es válido");
    }
    return new NewDeployment(
        d.repositoryId(),
        environment,
        limited(required(d.externalId(), "externalId"), 200, "externalId"),
        sha,
        optional(d.ref(), 255),
        d.pullRequestNumber(),
        url(d.url()),
        optional(d.creator(), 100),
        d.requestedAt() == null ? time.now() : d.requestedAt());
  }

  private StatusUpdate normalized(StatusUpdate u) {
    if (u.state() == null || u.state() == Status.SUPERSEDED) {
      throw new InvalidRequestException(
          "El estado tiene que ser PENDING, IN_PROGRESS, SUCCEEDED o FAILED");
    }
    String description = u.description() == null ? null : redactor.redact(u.description().strip());
    return new StatusUpdate(
        limited(required(u.externalId(), "externalId"), 200, "externalId"),
        u.state(),
        description == null || description.isEmpty() ? null : truncate(description, MAX_TEXT),
        u.occurredAt() == null ? time.now() : u.occurredAt(),
        lenientUrl(u.url()));
  }

  private static String lenientUrl(String value) {
    try {
      return url(value);
    } catch (InvalidRequestException e) {
      return null;
    }
  }

  /** Solo http(s) y sin credenciales: la URL se enseña como enlace. */
  private static String url(String value) {
    String url = optional(value, 500);
    if (url == null) {
      return null;
    }
    if (!url.matches("https?://[^\\s@]+")) {
      throw new InvalidRequestException(
          "La URL del despliegue tiene que ser http(s) y sin usuario");
    }
    return url;
  }

  private static String required(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new InvalidRequestException("Falta " + name);
    }
    return value.strip();
  }

  private static String limited(String value, int max, String name) {
    if (value.length() > max) {
      throw new InvalidRequestException(name + " es demasiado largo");
    }
    return value;
  }

  private static String optional(String value, int max) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return truncate(value.strip(), max);
  }

  private static String truncate(String value, int max) {
    return value.length() <= max ? value : value.substring(0, max);
  }

  static Map<String, Object> describe(Deployment d) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("repositoryId", d.repositoryId().toString());
    payload.put("environment", d.environment());
    payload.put("externalId", d.externalId());
    payload.put("commitSha", d.commitSha());
    payload.put("status", d.status().name());
    if (d.ref() != null) {
      payload.put("ref", d.ref());
    }
    if (d.pullRequestNumber() != null) {
      payload.put("pullRequestNumber", d.pullRequestNumber());
    }
    return payload;
  }

  private static Deployment map(ResultSet rs, int row) throws SQLException {
    int number = rs.getInt("pull_request_number");
    Integer pr = rs.wasNull() ? null : number;
    return new Deployment(
        rs.getObject("id", UUID.class),
        rs.getObject("repository_id", UUID.class),
        rs.getString("environment"),
        rs.getString("external_id"),
        rs.getString("commit_sha"),
        rs.getString("ref"),
        pr,
        rs.getString("url"),
        rs.getString("creator"),
        Status.valueOf(rs.getString("status")),
        Health.valueOf(rs.getString("health")),
        rs.getString("error"),
        instant(rs, "requested_at"),
        instant(rs, "started_at"),
        instant(rs, "finished_at"),
        instant(rs, "status_at"),
        rs.getObject("superseded_by", UUID.class),
        instant(rs, "created_at"),
        instant(rs, "updated_at"),
        rs.getString("health_detail"),
        instant(rs, "health_checked_at"),
        rs.getInt("health_attempts"),
        instant(rs, "stuck_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}
