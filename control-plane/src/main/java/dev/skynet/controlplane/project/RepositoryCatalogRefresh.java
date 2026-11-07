package dev.skynet.controlplane.project;

import dev.skynet.controlplane.shared.TimeSource;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * «Volver a buscar» en el asistente de importación (IP-A): todos los runners se enteran en la
 * respuesta a su siguiente latido, una vez cada uno. Una petición caduca a los {@link #TTL}: un
 * runner que se conecta después no busca por ella. Solo en memoria: si el control plane se
 * reinicia, los runners vuelven a buscar en su intervalo.
 */
@Component
public class RepositoryCatalogRefresh {

  static final Duration TTL = Duration.ofMinutes(2);

  private final TimeSource time;
  private volatile Instant requestedAt;
  private final Map<UUID, Instant> consumed = new ConcurrentHashMap<>();

  RepositoryCatalogRefresh(TimeSource time) {
    this.time = time;
  }

  void request() {
    requestedAt = time.now();
  }

  /** Si hay una búsqueda pedida que este runner aún no ha recibido; la marca como recibida. */
  public boolean consume(UUID runnerId) {
    Instant requested = requestedAt;
    if (requested == null || requested.plus(TTL).isBefore(time.now())) {
      return false;
    }
    Instant previous = consumed.put(runnerId, requested);
    return previous == null || previous.isBefore(requested);
  }
}
