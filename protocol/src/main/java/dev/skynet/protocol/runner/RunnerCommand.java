package dev.skynet.protocol.runner;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Orden pendiente para un runner. El runner la confirma con {@code ack}; mientras no lo haga, el
 * control plane la vuelve a entregar.
 *
 * @param agentRunId agente al que se refiere; en {@link RunnerCommandType#VERIFY}, el agente cuyo
 *     worktree se verifica
 * @param start datos de la invocación; solo en órdenes {@link RunnerCommandType#START} y {@link
 *     RunnerCommandType#RESUME}, y en estas con {@link StartAgent#resume()}
 * @param verify datos de la verificación; solo, y siempre, en {@link RunnerCommandType#VERIFY}
 * @param cleanup worktree que se elimina; solo, y siempre, en {@link RunnerCommandType#CLEANUP}. El
 *     agente es la última invocación del worktree, a la que se atribuye su evento
 */
public record RunnerCommand(
    UUID id,
    RunnerCommandType type,
    UUID agentRunId,
    Instant createdAt,
    StartAgent start,
    RunVerification verify,
    CleanupWorkspace cleanup) {

  public RunnerCommand {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(agentRunId, "agentRunId");
    Objects.requireNonNull(createdAt, "createdAt");
    boolean invocation = type == RunnerCommandType.START || type == RunnerCommandType.RESUME;
    if (invocation != (start != null)) {
      throw new IllegalArgumentException(
          "start solo se informa, y es obligatorio, en START y RESUME");
    }
    if (invocation && (type == RunnerCommandType.RESUME) != (start.resume() != null)) {
      throw new IllegalArgumentException("resume solo se informa, y es obligatorio, en RESUME");
    }
    if ((type == RunnerCommandType.VERIFY) != (verify != null)) {
      throw new IllegalArgumentException("verify solo se informa, y es obligatorio, en VERIFY");
    }
    if ((type == RunnerCommandType.CLEANUP) != (cleanup != null)) {
      throw new IllegalArgumentException("cleanup solo se informa, y es obligatorio, en CLEANUP");
    }
  }

  public static RunnerCommand start(UUID id, UUID agentRunId, Instant createdAt, StartAgent start) {
    return new RunnerCommand(id, RunnerCommandType.START, agentRunId, createdAt, start, null, null);
  }

  public static RunnerCommand resume(
      UUID id, UUID agentRunId, Instant createdAt, StartAgent start) {
    return new RunnerCommand(
        id, RunnerCommandType.RESUME, agentRunId, createdAt, start, null, null);
  }

  public static RunnerCommand cancel(UUID id, UUID agentRunId, Instant createdAt) {
    return new RunnerCommand(id, RunnerCommandType.CANCEL, agentRunId, createdAt, null, null, null);
  }

  public static RunnerCommand verify(
      UUID id, UUID agentRunId, Instant createdAt, RunVerification verify) {
    return new RunnerCommand(
        id, RunnerCommandType.VERIFY, agentRunId, createdAt, null, verify, null);
  }

  public static RunnerCommand cleanup(
      UUID id, UUID agentRunId, Instant createdAt, CleanupWorkspace cleanup) {
    return new RunnerCommand(
        id, RunnerCommandType.CLEANUP, agentRunId, createdAt, null, null, cleanup);
  }
}
