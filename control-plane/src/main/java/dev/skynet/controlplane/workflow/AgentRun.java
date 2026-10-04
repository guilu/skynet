package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.shared.ConflictException;
import dev.skynet.protocol.AgentObservableStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/** Una invocación concreta de un agente dentro de una fase (§5.5, §12.2). */
@Table("agent_run")
public class AgentRun {

  public static final String PROVIDER_CLAUDE_CODE = "claude-code";

  @Id private final UUID id;
  private final UUID stageRunId;
  private final UUID parentAgentRunId;
  private final UUID repositoryId;
  private final AgentRunKind kind;
  private AgentObservableStatus status;
  private final String provider;
  private String providerSessionId;
  private String model;
  private UUID runnerId;
  private Long processId;
  private final Instant createdAt;
  private Instant startedAt;
  private Instant lastActivityAt;
  private Instant finishedAt;
  private Integer exitCode;
  private Integer numTurns;
  private Long inputTokens;
  private Long outputTokens;
  private BigDecimal costUsd;
  private BigDecimal costUsdCumulative;
  private String error;
  @Version private Long version;

  @PersistenceCreator
  AgentRun(
      UUID id,
      UUID stageRunId,
      UUID parentAgentRunId,
      UUID repositoryId,
      AgentRunKind kind,
      AgentObservableStatus status,
      String provider,
      String providerSessionId,
      String model,
      UUID runnerId,
      Long processId,
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
      String error,
      Long version) {
    this.id = id;
    this.stageRunId = stageRunId;
    this.parentAgentRunId = parentAgentRunId;
    this.repositoryId = repositoryId;
    this.kind = kind;
    this.status = status;
    this.provider = provider;
    this.providerSessionId = providerSessionId;
    this.model = model;
    this.runnerId = runnerId;
    this.processId = processId;
    this.createdAt = createdAt;
    this.startedAt = startedAt;
    this.lastActivityAt = lastActivityAt;
    this.finishedAt = finishedAt;
    this.exitCode = exitCode;
    this.numTurns = numTurns;
    this.inputTokens = inputTokens;
    this.outputTokens = outputTokens;
    this.costUsd = costUsd;
    this.costUsdCumulative = costUsdCumulative;
    this.error = error;
    this.version = version;
  }

  static AgentRun queued(UUID stageRunId, UUID repositoryId, String provider, Instant now) {
    return new AgentRun(
        UUID.randomUUID(),
        stageRunId,
        null,
        repositoryId,
        AgentRunKind.START,
        AgentObservableStatus.QUEUED,
        provider,
        null,
        null,
        null,
        null,
        now,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  /** Aplica la transición y devuelve el estado anterior. */
  AgentObservableStatus transitionTo(AgentObservableStatus next, Instant now) {
    if (!status.canTransitionTo(next)) {
      throw new ConflictException(
          "El agente " + id + " no puede pasar de " + status + " a " + next);
    }
    AgentObservableStatus previous = status;
    status = next;
    lastActivityAt = now;
    if (next == AgentObservableStatus.STARTING && startedAt == null) {
      startedAt = now;
    }
    if (next.isTerminal()) {
      finishedAt = now;
    }
    return previous;
  }

  public UUID getId() {
    return id;
  }

  public UUID getStageRunId() {
    return stageRunId;
  }

  public UUID getParentAgentRunId() {
    return parentAgentRunId;
  }

  public UUID getRepositoryId() {
    return repositoryId;
  }

  public AgentRunKind getKind() {
    return kind;
  }

  public AgentObservableStatus getStatus() {
    return status;
  }

  public String getProvider() {
    return provider;
  }

  public String getProviderSessionId() {
    return providerSessionId;
  }

  public String getModel() {
    return model;
  }

  public UUID getRunnerId() {
    return runnerId;
  }

  public Long getProcessId() {
    return processId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public Instant getLastActivityAt() {
    return lastActivityAt;
  }

  public Instant getFinishedAt() {
    return finishedAt;
  }

  public Integer getExitCode() {
    return exitCode;
  }

  public Integer getNumTurns() {
    return numTurns;
  }

  public Long getInputTokens() {
    return inputTokens;
  }

  public Long getOutputTokens() {
    return outputTokens;
  }

  public BigDecimal getCostUsd() {
    return costUsd;
  }

  public BigDecimal getCostUsdCumulative() {
    return costUsdCumulative;
  }

  public String getError() {
    return error;
  }

  public Long getVersion() {
    return version;
  }
}
