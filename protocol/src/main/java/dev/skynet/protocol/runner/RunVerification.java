package dev.skynet.protocol.runner;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Verificación de un worktree: el runner ejecuta el comando de validación del repositorio en el
 * worktree del agente y recoge su resultado y los informes JUnit.
 *
 * @param verificationRunId verificación a la que pertenecen los eventos y artefactos
 * @param workspacePath worktree en la máquina del runner
 * @param command comando de validación ({@code sh -c})
 * @param testReportPaths globs de los informes JUnit XML, relativos al worktree
 * @param timeout tiempo máximo del comando
 */
public record RunVerification(
    UUID verificationRunId,
    String workspacePath,
    String command,
    List<String> testReportPaths,
    Duration timeout) {

  public RunVerification {
    Objects.requireNonNull(verificationRunId, "verificationRunId");
    Objects.requireNonNull(workspacePath, "workspacePath");
    Objects.requireNonNull(command, "command");
    Objects.requireNonNull(timeout, "timeout");
    testReportPaths = testReportPaths == null ? List.of() : List.copyOf(testReportPaths);
  }
}
