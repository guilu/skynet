package dev.skynet.controlplane.runner;

import dev.skynet.protocol.runner.ProviderInfo;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Proyección de un runner para la web.
 *
 * @param activeAgents agentes asignados que aún no han terminado
 * @param status {@code ONLINE} si ha enviado un latido recientemente; {@code STALE} si no
 * @param archivedAt cuándo se olvidó, o {@code null}
 * @param providers proveedores de agentes que tiene, con la versión de su CLI
 */
public record RunnerView(
    UUID id,
    String name,
    int capacity,
    int activeAgents,
    String runnerVersion,
    String providerVersion,
    Instant registeredAt,
    Instant lastHeartbeatAt,
    Status status,
    Instant archivedAt,
    List<ProviderInfo> providers) {

  public enum Status {
    ONLINE,
    STALE
  }
}
