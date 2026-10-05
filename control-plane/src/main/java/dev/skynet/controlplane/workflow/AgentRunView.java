package dev.skynet.controlplane.workflow;

import dev.skynet.protocol.AgentObservableStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Proyección de una invocación de agente: estado, actividad actual, sesión, tokens, coste y
 * timestamps. La web la muestra tal cual, sin deducir estado de los eventos.
 *
 * @param lastEventType tipo del último evento del agente (p. ej. {@code agent.tool.started})
 * @param currentTool herramienta en curso, o {@code null} si no hay ninguna
 */
public record AgentRunView(
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
    Long cacheReadTokens,
    Long cacheCreationTokens,
    BigDecimal costUsd,
    BigDecimal costUsdCumulative,
    UUID runnerId,
    Instant cancelRequestedAt,
    String resultSubtype,
    String error,
    String lastEventType,
    String currentTool) {

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
        a.getCacheReadTokens(),
        a.getCacheCreationTokens(),
        a.getCostUsd(),
        a.getCostUsdCumulative(),
        a.getRunnerId(),
        a.getCancelRequestedAt(),
        a.getResultSubtype(),
        a.getError(),
        a.getLastEventType(),
        a.getCurrentTool());
  }
}
