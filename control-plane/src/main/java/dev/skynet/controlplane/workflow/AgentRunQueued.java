package dev.skynet.controlplane.workflow;

import dev.skynet.protocol.runner.StartAgent;
import java.util.UUID;

/**
 * Un agente ha quedado en cola y necesita un runner. Se publica dentro de la transacción.
 *
 * @param runnerId runner que debe ejecutarlo, o {@code null} si vale cualquiera. Una reanudación
 *     ({@code start.resume() != null}) va siempre al runner de su padre.
 */
public record AgentRunQueued(UUID agentRunId, StartAgent start, UUID runnerId) {

  public boolean isResume() {
    return start.resume() != null;
  }
}
