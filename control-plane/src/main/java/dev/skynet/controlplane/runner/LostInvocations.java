package dev.skynet.controlplane.runner;

import dev.skynet.controlplane.workflow.RunService;
import dev.skynet.protocol.runner.RunnerHeartbeat;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * Reconciliación con los latidos: una invocación que el runner confirmó y que deja de declarar
 * durante {@code skynet.runner.lost-after-heartbeats} latidos seguidos ya no tiene proceso, y el
 * agente pasa a {@code FAILED}. Sin esto se quedaría para siempre en «Arrancando» o en curso.
 */
@Component
class LostInvocations {

  private static final Logger log = LoggerFactory.getLogger(LostInvocations.class);

  private final CommandQueue commands;
  private final RunService runs;
  private final RunnerProperties properties;

  LostInvocations(CommandQueue commands, RunService runs, RunnerProperties properties) {
    this.commands = commands;
    this.runs = runs;
    this.properties = properties;
  }

  /** Aplica un latido; devuelve los agentes que ha dado por perdidos. */
  List<UUID> reconcile(UUID runnerId, RunnerHeartbeat heartbeat) {
    List<UUID> reported =
        heartbeat.runningAgentRunIds() == null ? List.of() : heartbeat.runningAgentRunIds();
    List<UUID> lost = commands.missedBy(runnerId, reported, properties.lostAfterHeartbeats());
    return lost.stream().filter(id -> fail(runnerId, id)).toList();
  }

  private boolean fail(UUID runnerId, UUID agentRunId) {
    try {
      boolean failed = runs.failLost(agentRunId);
      if (failed) {
        log.warn(
            "El runner {} ya no ejecuta el agente {}: se da por perdido", runnerId, agentRunId);
      }
      return failed;
    } catch (OptimisticLockingFailureException e) {
      // Ha llegado un evento a la vez: el siguiente latido lo vuelve a evaluar.
      log.debug("El agente {} ha cambiado mientras se reconciliaba", agentRunId);
      return false;
    }
  }
}
