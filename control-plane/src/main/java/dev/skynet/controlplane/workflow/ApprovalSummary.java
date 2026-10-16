package dev.skynet.controlplane.workflow;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Una petición de aprobación sin su evidencia, para listarla.
 *
 * @param round 1 la primera vez que pregunta la puerta; una más cada vez que vuelve a preguntar
 * @param action la acción que se pide aprobar, con sus variables ya resueltas
 * @param risk {@code LOW}, {@code MEDIUM} o {@code HIGH}
 * @param status {@code PENDING}, {@code APPROVED}, {@code REJECTED}, {@code CHANGES_REQUESTED} o
 *     {@code CANCELLED}
 * @param origin proyecto, trabajo, ejecución, workflow y fase de la puerta, como en la evidencia
 */
public record ApprovalSummary(
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
    Map<String, Object> origin) {}
