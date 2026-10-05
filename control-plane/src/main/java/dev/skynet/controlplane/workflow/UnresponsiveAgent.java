package dev.skynet.controlplane.workflow;

import java.time.Instant;
import java.util.UUID;

/** Agente que ha dejado de dar señales de actividad, con la ejecución a la que pertenece. */
public record UnresponsiveAgent(
    UUID agentRunId, UUID workflowRunId, String workItemKey, Instant lastActivityAt) {}
