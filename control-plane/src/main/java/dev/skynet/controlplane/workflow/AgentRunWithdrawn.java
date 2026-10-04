package dev.skynet.controlplane.workflow;

import java.util.UUID;

/**
 * Un agente se ha cancelado antes de que ningún runner lo reclamara: su orden de arranque ya no
 * debe entregarse. Se publica dentro de la transacción.
 */
public record AgentRunWithdrawn(UUID agentRunId) {}
