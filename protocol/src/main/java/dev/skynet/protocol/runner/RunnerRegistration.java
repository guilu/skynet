package dev.skynet.protocol.runner;

import java.util.List;
import java.util.Objects;

/**
 * Petición de registro de un runner ({@code POST /api/runner/register}).
 *
 * @param registrationToken secreto compartido de registro
 * @param capacity número máximo de agentes simultáneos
 * @param providerVersion versión del CLI del proveedor instalado (p. ej. {@code claude --version}),
 *     o {@code null} si no se pudo determinar; se mantiene para los control planes anteriores a
 *     {@code providers}
 * @param providers proveedores que el runner tiene disponibles, o {@code null} si es un runner
 *     anterior a esta lista (cuenta como solo {@code claude-code})
 */
public record RunnerRegistration(
    String name,
    String registrationToken,
    String runnerVersion,
    int capacity,
    String providerVersion,
    List<ProviderInfo> providers) {

  public RunnerRegistration {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(registrationToken, "registrationToken");
    if (capacity < 1) {
      throw new IllegalArgumentException("capacity debe ser al menos 1: " + capacity);
    }
    providers = providers == null ? null : List.copyOf(providers);
  }

  /** Registro de un runner que no declara sus proveedores. */
  public RunnerRegistration(
      String name,
      String registrationToken,
      String runnerVersion,
      int capacity,
      String providerVersion) {
    this(name, registrationToken, runnerVersion, capacity, providerVersion, null);
  }
}
