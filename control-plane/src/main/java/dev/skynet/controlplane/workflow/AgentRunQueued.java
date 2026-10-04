package dev.skynet.controlplane.workflow;

import dev.skynet.protocol.runner.StartAgent;
import java.util.UUID;

/** Un agente ha quedado en cola y necesita un runner. Se publica dentro de la transacción. */
public record AgentRunQueued(UUID agentRunId, StartAgent start) {}
