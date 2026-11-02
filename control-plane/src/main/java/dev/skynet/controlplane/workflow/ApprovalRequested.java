package dev.skynet.controlplane.workflow;

import java.util.UUID;

/**
 * Una puerta empieza a esperar una decisión (o vuelve a preguntar tras una corrección). Se publica
 * dentro de la transacción del motor; lo escuchan, p. ej., los avisos en GitHub de los disparadores
 * (DS-C).
 *
 * @param action lo que se pide aprobar, ya resuelto
 */
public record ApprovalRequested(
    UUID approvalId, UUID workflowRunId, String stageKey, int round, String action) {}
