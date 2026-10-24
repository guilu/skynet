package dev.skynet.controlplane.memory;

import java.util.UUID;

/**
 * Quién guarda y desde dónde.
 *
 * @param actor usuario de la web, o {@code null} para un agente o Skynet
 * @param workflowRunId ejecución de la que sale, si sale de una
 * @param agentRunId invocación de la que sale, si sale de un agente
 */
public record MemorySource(MemoryOrigin origin, String actor, UUID workflowRunId, UUID agentRunId) {

  public static MemorySource human(String actor) {
    return new MemorySource(MemoryOrigin.HUMAN, actor, null, null);
  }

  public static MemorySource agent(UUID workflowRunId, UUID agentRunId) {
    return new MemorySource(MemoryOrigin.AGENT, null, workflowRunId, agentRunId);
  }

  public static MemorySource system(UUID workflowRunId) {
    return new MemorySource(MemoryOrigin.SYSTEM, null, workflowRunId, null);
  }
}
