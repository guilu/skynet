package dev.skynet.controlplane.runner;

import dev.skynet.controlplane.event.EventDraft;
import dev.skynet.controlplane.event.EventStore;
import dev.skynet.controlplane.shared.ArchiveState;
import dev.skynet.controlplane.shared.Archiving;
import dev.skynet.controlplane.shared.ConflictException;
import dev.skynet.controlplane.shared.NotFoundException;
import dev.skynet.controlplane.shared.TimeSource;
import dev.skynet.protocol.runner.RunnerHeartbeat;
import dev.skynet.protocol.runner.RunnerRegistered;
import dev.skynet.protocol.runner.RunnerRegistration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Alta de runners, autenticación por token y latidos. */
@Component
class RunnerRegistry {

  private static final SecureRandom RANDOM = new SecureRandom();

  private final JdbcClient jdbc;
  private final EventStore events;
  private final TimeSource time;
  private final RunnerProperties properties;

  RunnerRegistry(JdbcClient jdbc, EventStore events, TimeSource time, RunnerProperties properties) {
    this.jdbc = jdbc;
    this.events = events;
    this.time = time;
    this.properties = properties;
  }

  /**
   * Registra un runner. Si ya existe uno con el mismo nombre, conserva su id (y sus órdenes) y le
   * asigna un token nuevo, lo que invalida el anterior.
   */
  @Transactional
  RunnerRegistered register(RunnerRegistration registration) {
    String expected = properties.registrationToken();
    if (expected == null
        || expected.isBlank()
        || !MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            registration.registrationToken().getBytes(StandardCharsets.UTF_8))) {
      throw new UnauthorizedRunnerException("Token de registro inválido");
    }
    Instant now = time.now();
    String token = newToken();
    UUID id =
        jdbc.sql(
                "INSERT INTO runner (id, name, token_hash, capacity, runner_version,"
                    + " provider_version, registered_at, last_heartbeat_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                    + " ON CONFLICT (name) DO UPDATE SET token_hash = EXCLUDED.token_hash,"
                    + " capacity = EXCLUDED.capacity, runner_version = EXCLUDED.runner_version,"
                    + " provider_version = EXCLUDED.provider_version,"
                    + " last_heartbeat_at = EXCLUDED.last_heartbeat_at, archived_at = NULL"
                    + " RETURNING id")
            .params(
                UUID.randomUUID(),
                registration.name(),
                hash(token),
                registration.capacity(),
                registration.runnerVersion(),
                registration.providerVersion(),
                Timestamp.from(now),
                Timestamp.from(now))
            .query(UUID.class)
            .single();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("name", registration.name());
    payload.put("capacity", registration.capacity());
    payload.put("runnerVersion", registration.runnerVersion());
    payload.put("providerVersion", registration.providerVersion());
    events.append(EventDraft.of("runner", id, "runner.registered", null, payload, now));
    return new RunnerRegistered(id, token);
  }

  /**
   * Invalida el token del runner: se cambia por el hash de uno que nadie conoce. El runner legítimo
   * recibe un 401 y se vuelve a registrar con el secreto de registro; quien solo tenga el token
   * robado se queda fuera.
   */
  @Transactional
  void revoke(UUID runnerId) {
    int updated =
        jdbc.sql("UPDATE runner SET token_hash = ? WHERE id = ?")
            .params(hash(newToken()), runnerId)
            .update();
    if (updated == 0) {
      throw new NotFoundException("Runner", runnerId);
    }
    events.append(
        EventDraft.of("runner", runnerId, "runner.token.revoked", null, Map.of(), time.now()));
  }

  /**
   * Olvida el runner: invalida su token y lo saca de las listas. Es para los runners que ya no
   * existen; si el runner sigue vivo, se vuelve a registrar solo y reaparece. No se olvida un
   * runner con agentes en marcha.
   */
  @Transactional
  ArchiveState archive(UUID runnerId) {
    Instant archivedAt = archivedAt(runnerId);
    if (archivedAt != null) {
      return new ArchiveState(runnerId, archivedAt);
    }
    long active =
        jdbc.sql(
                "SELECT count(*) FROM agent_run WHERE runner_id = ?"
                    + " AND status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')")
            .param(runnerId)
            .query(Long.class)
            .single();
    if (active > 0) {
      throw new ConflictException(
          "El runner tiene "
              + active
              + " agente(s) en marcha: cancélalos o espera a que terminen antes de olvidarlo");
    }
    Instant now = time.now();
    jdbc.sql("UPDATE runner SET token_hash = ?, archived_at = ? WHERE id = ?")
        .params(hash(newToken()), Timestamp.from(now), runnerId)
        .update();
    events.append(EventDraft.of("runner", runnerId, "runner.archived", null, Map.of(), now));
    return new ArchiveState(runnerId, now);
  }

  /** Vuelve a enseñar el runner; su token sigue invalidado hasta que se registre de nuevo. */
  @Transactional
  ArchiveState restore(UUID runnerId) {
    if (archivedAt(runnerId) == null) {
      return new ArchiveState(runnerId, null);
    }
    Archiving.set(jdbc, "runner", runnerId, null);
    events.append(EventDraft.of("runner", runnerId, "runner.restored", null, Map.of(), time.now()));
    return new ArchiveState(runnerId, null);
  }

  private Instant archivedAt(UUID runnerId) {
    return jdbc.sql("SELECT archived_at FROM runner WHERE id = ? FOR UPDATE")
        .param(runnerId)
        .query((rs, row) -> Optional.ofNullable(rs.getTimestamp(1)).map(Timestamp::toInstant))
        .optional()
        .orElseThrow(() -> new NotFoundException("Runner", runnerId))
        .orElse(null);
  }

  /** Resuelve el runner a partir de la cabecera {@code Authorization: Bearer <token>}. */
  UUID authenticate(String authorization) {
    if (authorization == null || !authorization.startsWith("Bearer ")) {
      throw new UnauthorizedRunnerException("Falta el token del runner");
    }
    return jdbc.sql("SELECT id FROM runner WHERE token_hash = ?")
        .param(hash(authorization.substring("Bearer ".length()).trim()))
        .query(UUID.class)
        .optional()
        .orElseThrow(() -> new UnauthorizedRunnerException("Token de runner inválido"));
  }

  @Transactional
  void heartbeat(UUID runnerId, RunnerHeartbeat heartbeat) {
    jdbc.sql("UPDATE runner SET last_heartbeat_at = ?, capacity = ? WHERE id = ?")
        .params(Timestamp.from(time.now()), Math.max(1, heartbeat.capacity()), runnerId)
        .update();
  }

  int capacity(UUID runnerId) {
    return jdbc.sql("SELECT capacity FROM runner WHERE id = ?")
        .param(runnerId)
        .query(Integer.class)
        .single();
  }

  private static String newToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  static String hash(String token) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
