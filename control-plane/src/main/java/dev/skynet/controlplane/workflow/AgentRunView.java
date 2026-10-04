package dev.skynet.controlplane.workflow;

import dev.skynet.protocol.AgentObservableStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

record AgentRunView(
    UUID id,
    UUID stageRunId,
    UUID parentAgentRunId,
    UUID repositoryId,
    AgentRunKind kind,
    AgentObservableStatus status,
    String provider,
    String providerSessionId,
    String model,
    Instant createdAt,
    Instant startedAt,
    Instant lastActivityAt,
    Instant finishedAt,
    Integer exitCode,
    Integer numTurns,
    Long inputTokens,
    Long outputTokens,
    BigDecimal costUsd,
    BigDecimal costUsdCumulative,
    UUID runnerId,
    Instant cancelRequestedAt,
    String resultSubtype,
    String error) {

  static AgentRunView of(AgentRun a) {
    return new AgentRunView(
        a.getId(),
        a.getStageRunId(),
        a.getParentAgentRunId(),
        a.getRepositoryId(),
        a.getKind(),
        a.getStatus(),
        a.getProvider(),
        a.getProviderSessionId(),
        a.getModel(),
        a.getCreatedAt(),
        a.getStartedAt(),
        a.getLastActivityAt(),
        a.getFinishedAt(),
        a.getExitCode(),
        a.getNumTurns(),
        a.getInputTokens(),
        a.getOutputTokens(),
        a.getCostUsd(),
        a.getCostUsdCumulative(),
        a.getRunnerId(),
        a.getCancelRequestedAt(),
        a.getResultSubtype(),
        a.getError());
  }
}
