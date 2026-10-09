package dev.skynet.controlplane.runner;

import java.time.Instant;
import java.util.UUID;

/**
 * Proyección de un runner para la web.
 *
 * @param activeAgents agentes asignados que aún no han terminado
 * @param status {@code ONLINE} si ha enviado un latido recientemente; {@code STALE} si no
 * @param archivedAt cuándo se olvidó, o {@code null}
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
    Instant archivedAt) {

  public enum Status {
    ONLINE,
    STALE
  }
}
