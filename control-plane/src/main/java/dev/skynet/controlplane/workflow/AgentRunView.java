package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.usage.QuotaShare;
import dev.skynet.protocol.AgentObservableStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Proyección de una invocación de agente: estado, actividad actual, sesión, tokens, coste y
 * timestamps. La web la muestra tal cual, sin deducir estado de los eventos.
 *
 * @param lastEventType tipo del último evento del agente (p. ej. {@code agent.tool.started})
 * @param currentTool herramienta en curso, o {@code null} si no hay ninguna
 * @param workspace worktree de la invocación, cuando el runner lo ha preparado
 * @param kind cómo se originó: lanzamiento, reanudación, reintento o fork de {@code
 *     parentAgentRunId}
 * @param childKey hijo de una fase {@code parallel} que ejecuta, o {@code null} en las demás
 * @param structuredOutput salida estructurada que devolvió (la que pide el {@code output} de su
 *     agente), o {@code null}
 * @param failure por qué falló ({@code lost}, {@code error}…), o {@code null} si no ha fallado o no
 *     se sabe (W6)
 * @param quota parte de las ventanas de cuota de su CLI que se le atribuye (CU-B); vacía si no hay
 *     muestras de cuota en el tiempo en que corrió
 * @param simulated se ejecutó con un CLI simulado (fake-claude, fake-codex…), no con el real (AR-A)
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
    String currentTool,
    WorkspaceView workspace,
    String childKey,
    JsonNode structuredOutput,
    String failure,
    List<QuotaShare> quota,
    boolean simulated) {

  static AgentRunView of(AgentRun a, WorkspaceView workspace, List<QuotaShare> quota) {
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
        a.getCurrentTool(),
        workspace,
        a.getChildKey(),
        a.getStructuredOutput() == null
            ? null
            : JsonMapper.shared().readTree(a.getStructuredOutput()),
        a.getFailure() == null ? null : a.getFailure().id(),
        quota == null ? List.of() : quota,
        a.isSimulated());
  }
}
