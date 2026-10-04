package dev.skynet.protocol.runner;

import java.util.List;
import java.util.UUID;

/**
 * Latido periódico de un runner ({@code POST /api/runner/heartbeat}).
 *
 * @param capacity capacidad total
 * @param runningAgentRunIds invocaciones con proceso vivo; permite al control plane reconciliar
 */
public record RunnerHeartbeat(int capacity, List<UUID> runningAgentRunIds) {

  public RunnerHeartbeat {
    runningAgentRunIds = runningAgentRunIds == null ? List.of() : List.copyOf(runningAgentRunIds);
  }
}
