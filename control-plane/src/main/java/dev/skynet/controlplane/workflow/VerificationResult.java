package dev.skynet.controlplane.workflow;

import java.time.Instant;
import java.util.UUID;

/**
 * Verificación de un worktree, tal como la comprobó Skynet (ADR-0001 criterio 8): el comando, su
 * código de salida y los totales de los informes JUnit, no lo que declara el agente.
 *
 * @param trigger {@code AUTO} (al completarse una invocación) o {@code MANUAL}
 * @param status {@code QUEUED}, {@code RUNNING}, {@code PASSED}, {@code FAILED} o {@code ERROR}
 * @param tests totales de los informes JUnit, o {@code null} si no se encontró ninguno
 * @param error por qué no se pudo ejecutar o completar el comando (p. ej. tiempo agotado)
 */
public record VerificationResult(
    UUID id,
    UUID agentRunId,
    String trigger,
    String status,
    String command,
    Integer exitCode,
    String signal,
    String error,
    TestTotals tests,
    Instant createdAt,
    Instant startedAt,
    Instant finishedAt) {

  /** Totales de los informes JUnit. */
  public record TestTotals(int total, int failed, int errors, int skipped) {}

  public boolean isLive() {
    return "QUEUED".equals(status) || "RUNNING".equals(status);
  }
}
