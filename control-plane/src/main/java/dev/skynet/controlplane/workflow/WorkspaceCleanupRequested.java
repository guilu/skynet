package dev.skynet.controlplane.workflow;

import java.util.UUID;

/**
 * Se ha pedido eliminar un worktree. La orden va a su runner en nombre de {@code agentRunId}, una
 * invocación que trabajó en él. Se publica dentro de la transacción.
 */
public record WorkspaceCleanupRequested(
    UUID workspaceId, UUID runnerId, UUID agentRunId, String path) {}
