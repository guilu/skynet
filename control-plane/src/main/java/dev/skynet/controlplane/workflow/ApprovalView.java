package dev.skynet.controlplane.workflow;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Una petición de aprobación con lo que se enseña al decidir (ADR-0001 §3.7).
 *
 * @param evidence la foto que se tomó al crear la petición: {@code origin}, {@code revise}, {@code
 *     dependencies}, {@code costUsd}, {@code grants} y {@code consequences}
 * @param decisions las decisiones y comentarios, del más antiguo al más reciente
 */
public record ApprovalView(
    UUID id,
    UUID workflowRunId,
    UUID stageRunId,
    String stageKey,
    int round,
    String action,
    String risk,
    String status,
    Instant createdAt,
    Instant decidedAt,
    Map<String, Object> evidence,
    List<Decision> decisions) {

  /**
   * Una decisión o un comentario, que no se reescribe.
   *
   * @param action {@code APPROVE}, {@code REJECT}, {@code REQUEST_CHANGES}, {@code COMMENT} o
   *     {@code CANCEL} (la petición se canceló con su ejecución)
   * @param actor quién: el usuario de la web, o {@code skynet} en una cancelación
   */
  public record Decision(UUID id, String action, String comment, String actor, Instant createdAt) {}
}
