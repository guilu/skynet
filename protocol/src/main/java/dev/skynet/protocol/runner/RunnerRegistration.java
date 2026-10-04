package dev.skynet.protocol.runner;

import java.util.Objects;

/**
 * Petición de registro de un runner ({@code POST /api/runner/register}).
 *
 * @param registrationToken secreto compartido de registro
 * @param capacity número máximo de agentes simultáneos
 * @param providerVersion versión del CLI del proveedor instalado (p. ej. {@code claude --version}),
 *     o {@code null} si no se pudo determinar
 */
public record RunnerRegistration(
    String name,
    String registrationToken,
    String runnerVersion,
    int capacity,
    String providerVersion) {

  public RunnerRegistration {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(registrationToken, "registrationToken");
    if (capacity < 1) {
      throw new IllegalArgumentException("capacity debe ser al menos 1: " + capacity);
    }
  }
}
