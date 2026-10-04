package dev.skynet.controlplane.workflow;

import java.util.UUID;

/**
 * Se ha pedido cancelar un agente que ya está en un runner: hay que ordenarle que mate el proceso.
 * Se publica dentro de la transacción.
 */
public record AgentCancelRequested(UUID agentRunId, UUID runnerId) {}
