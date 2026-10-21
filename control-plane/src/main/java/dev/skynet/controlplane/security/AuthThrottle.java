package dev.skynet.controlplane.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fallos de autenticación seguidos por origen: tras {@code maxFailures} se bloquea ese origen
 * durante {@code lockout}, aunque luego acierte. Un acierto borra los fallos. En memoria: se pierde
 * al reiniciar, que basta para frenar a quien prueba contraseñas.
 */
public final class AuthThrottle {

  /** Orígenes que se recuerdan como mucho; al pasar, se olvidan los que ya no cuentan. */
  private static final int MAX_KEYS = 10_000;

  private final int maxFailures;
  private final Duration lockout;
  private final Clock clock;
  private final Map<String, Failures> failures = new ConcurrentHashMap<>();

  AuthThrottle(int maxFailures, Duration lockout, Clock clock) {
    this.maxFailures = maxFailures;
    this.lockout = lockout;
    this.clock = clock;
  }

  /** Cuánto falta para que {@code key} pueda volver a intentarlo, si está bloqueado. */
  Optional<Duration> blocked(String key) {
    Failures f = failures.get(key);
    Instant now = clock.instant();
    if (f == null || f.blockedUntil() == null || !f.blockedUntil().isAfter(now)) {
      return Optional.empty();
    }
    return Optional.of(Duration.between(now, f.blockedUntil()));
  }

  void failed(String key) {
    Instant now = clock.instant();
    if (failures.size() >= MAX_KEYS) {
      failures.values().removeIf(f -> f.expired(now, lockout));
    }
    failures.compute(
        key,
        (k, f) -> {
          // Fallos de hace más de un bloqueo ya no cuentan.
          int count = f == null || f.expired(now, lockout) ? 1 : f.count() + 1;
          return new Failures(count, now, count >= maxFailures ? now.plus(lockout) : null);
        });
  }

  void succeeded(String key) {
    failures.remove(key);
  }

  /** Olvida todos los fallos (tests). */
  public void clear() {
    failures.clear();
  }

  private record Failures(int count, Instant last, Instant blockedUntil) {

    boolean expired(Instant now, Duration lockout) {
      Instant until = blockedUntil != null ? blockedUntil : last.plus(lockout);
      return !until.isAfter(now);
    }
  }
}
