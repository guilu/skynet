package dev.skynet.controlplane.workflow;

import java.util.UUID;

/**
 * La ejecución ha terminado (bien, mal o cancelada). Se publica dentro de la transacción del
 * cambio; la bitácora de memoria ({@link RunLogWriter}) la escribe después del commit.
 */
public record WorkflowRunFinished(UUID workflowRunId) {}
