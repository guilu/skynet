package dev.skynet.controlplane.workflow;

import java.util.UUID;

/**
 * Algo ha cambiado en la ejecución (se ha lanzado, una fase ha terminado o se ha pedido cancelarla)
 * y el motor tiene que decidir qué viene después. Se publica dentro de la transacción del cambio.
 */
public record WorkflowRunChanged(UUID workflowRunId) {}
