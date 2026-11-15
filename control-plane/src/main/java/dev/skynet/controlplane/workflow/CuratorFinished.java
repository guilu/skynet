package dev.skynet.controlplane.workflow;

import java.util.UUID;

/**
 * Una invocación con {@code memory: curate} terminó bien (MV-E). Se publica dentro de la
 * transacción del cambio; {@link MemoryCurator} aplica su salida después del commit.
 */
public record CuratorFinished(UUID agentRunId) {}
