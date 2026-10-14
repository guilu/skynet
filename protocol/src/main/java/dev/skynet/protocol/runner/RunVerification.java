package dev.skynet.protocol.runner;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Verificación de un worktree: el runner ejecuta el comando de validación del repositorio en el
 * worktree del agente y recoge su resultado y los informes JUnit. La de una fase lleva además sus
 * comprobaciones ({@code checks}), que el runner ejecuta una a una e informa por separado.
 *
 * @param verificationRunId verificación a la que pertenecen los eventos y artefactos
 * @param workspacePath worktree en la máquina del runner
 * @param command comando de validación ({@code sh -c}); {@code null} en la verificación de una fase
 *     que no lo necesita
 * @param testReportPaths globs de los informes JUnit XML, relativos al worktree
 * @param timeout tiempo máximo de cada comando
 * @param checks comprobaciones de la fase, en orden; vacío en la verificación del repositorio
 * @param baseCommit commit desde el que se miden los cambios ({@code diff}, {@code commit}, {@code
 *     scope}); {@code null} si no se sabe, y entonces se toma el {@code HEAD} del worktree
 */
public record RunVerification(
    UUID verificationRunId,
    String workspacePath,
    String command,
    List<String> testReportPaths,
    Duration timeout,
    List<VerificationCheck> checks,
    String baseCommit) {

  public RunVerification {
    Objects.requireNonNull(verificationRunId, "verificationRunId");
    Objects.requireNonNull(workspacePath, "workspacePath");
    Objects.requireNonNull(timeout, "timeout");
    testReportPaths = testReportPaths == null ? List.of() : List.copyOf(testReportPaths);
    checks = checks == null ? List.of() : List.copyOf(checks);
    if (command == null && checks.isEmpty()) {
      throw new IllegalArgumentException("Una verificación necesita un comando o comprobaciones");
    }
  }

  /** La verificación del repositorio: solo su comando de validación. */
  public RunVerification(
      UUID verificationRunId,
      String workspacePath,
      String command,
      List<String> testReportPaths,
      Duration timeout) {
    this(verificationRunId, workspacePath, command, testReportPaths, timeout, List.of(), null);
  }

  /** Si es la verificación de una fase, con sus comprobaciones. */
  public boolean hasChecks() {
    return !checks.isEmpty();
  }
}
