package dev.skynet.controlplane.usage;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Los runners a los que se ha pedido «Actualizar» las cuotas: se enteran en la respuesta a su
 * siguiente latido. Solo en memoria: si el control plane se reinicia, el runner las consultará en
 * su intervalo.
 */
@Component
public class QuotaRefresh {

  private final Set<UUID> requested = ConcurrentHashMap.newKeySet();

  void request(Collection<UUID> runnerIds) {
    requested.addAll(runnerIds);
  }

  /** Si se le pidió al runner, y lo olvida: se le pide una vez. */
  public boolean consume(UUID runnerId) {
    return requested.remove(runnerId);
  }
}
