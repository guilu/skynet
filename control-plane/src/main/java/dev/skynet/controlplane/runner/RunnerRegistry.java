package dev.skynet.controlplane.runner;

import dev.skynet.controlplane.event.EventDraft;
import dev.skynet.controlplane.event.EventStore;
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
                    + " last_heartbeat_at = EXCLUDED.last_heartbeat_at"
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
