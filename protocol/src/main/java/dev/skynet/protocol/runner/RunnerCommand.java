package dev.skynet.protocol.runner;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Orden pendiente para un runner. El runner la confirma con {@code ack}; mientras no lo haga, el
 * control plane la vuelve a entregar.
 *
 * @param start datos de arranque; solo en órdenes {@link RunnerCommandType#START}
 */
public record RunnerCommand(
    UUID id, RunnerCommandType type, UUID agentRunId, Instant createdAt, StartAgent start) {

  public RunnerCommand {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(agentRunId, "agentRunId");
    Objects.requireNonNull(createdAt, "createdAt");
    if ((type == RunnerCommandType.START) != (start != null)) {
      throw new IllegalArgumentException("start solo se informa, y es obligatorio, en START");
    }
  }

  public static RunnerCommand start(UUID id, UUID agentRunId, Instant createdAt, StartAgent start) {
    return new RunnerCommand(id, RunnerCommandType.START, agentRunId, createdAt, start);
  }

  public static RunnerCommand cancel(UUID id, UUID agentRunId, Instant createdAt) {
    return new RunnerCommand(id, RunnerCommandType.CANCEL, agentRunId, createdAt, null);
  }
}
