package dev.skynet.controlplane.workflow;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Verificación de un worktree, tal como la comprobó Skynet (ADR-0001 criterio 8): el comando, su
 * código de salida y los totales de los informes JUnit, no lo que declara el agente.
 *
 * @param trigger {@code AUTO} (al completarse una invocación), {@code MANUAL} o {@code STAGE} (la
 *     verificación de una fase con {@code artifacts} o {@code checks}, que decide si la fase pasa)
 * @param status {@code QUEUED}, {@code RUNNING}, {@code PASSED}, {@code FAILED} o {@code ERROR}
 * @param command comando de validación del repositorio, o {@code null} si esta verificación no lo
 *     ejecuta
 * @param tests totales de los informes JUnit, o {@code null} si no se encontró ninguno
 * @param error por qué no se pudo ejecutar o completar la verificación (p. ej. tiempo agotado)
 * @param stageRunId fase que verifica, con {@code trigger} {@code STAGE}; {@code null} en las demás
 * @param checks las comprobaciones de la fase con su resultado ({@code passed} {@code null}
 *     mientras no ha terminado); vacío en las demás
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
    Instant finishedAt,
    UUID stageRunId,
    List<CheckResult> checks) {

  public VerificationResult {
    checks = checks == null ? List.of() : List.copyOf(checks);
  }

  /** Totales de los informes JUnit. */
  public record TestTotals(int total, int failed, int errors, int skipped) {}

  /**
   * Una comprobación de la fase, tal como la hizo Skynet.
   *
   * @param target lo que comprueba (los globs o el comando), si lo tiene
   * @param passed si pasó; {@code null} mientras la verificación no ha terminado
   * @param reason por qué no pasó
   * @param omitted en {@code artifacts}, los ficheros declarados que no se guardaron, con el motivo
   *     (#145); vacío en los demás
   */
  public record CheckResult(
      String name, String target, Boolean passed, String reason, List<OmittedFile> omitted) {

    public CheckResult {
      omitted = omitted == null ? List.of() : List.copyOf(omitted);
    }

    public CheckResult(String name, String target, Boolean passed, String reason) {
      this(name, target, passed, reason, List.of());
    }
  }

  /** Un fichero declarado en {@code artifacts} que no se guardó (por tamaño, enlace o tipo). */
  public record OmittedFile(String path, String reason) {}

  public boolean isLive() {
    return "QUEUED".equals(status) || "RUNNING".equals(status);
  }
}
