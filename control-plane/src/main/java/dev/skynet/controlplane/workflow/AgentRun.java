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
  private Instant cancelRequestedAt;
  private String resultSubtype;
  private Boolean resultIsError;
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
      Instant cancelRequestedAt,
      String resultSubtype,
      Boolean resultIsError,
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
    this.cancelRequestedAt = cancelRequestedAt;
    this.resultSubtype = resultSubtype;
    this.resultIsError = resultIsError;
    this.version = version;
  }

  /**
   * Agente en cola. El id de sesión se fija aquí y el runner lo pasa al proveedor ({@code
   * --session-id}), de modo que se conoce antes de que el proceso arranque.
   */
  static AgentRun queued(
      UUID stageRunId, UUID repositoryId, String provider, UUID sessionId, Instant now) {
    return new AgentRun(
        UUID.randomUUID(),
        stageRunId,
        null,
        repositoryId,
        AgentRunKind.START,
        AgentObservableStatus.QUEUED,
        provider,
        sessionId.toString(),
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

  /** Un runner ha reclamado la invocación. */
  void assignRunner(UUID runnerId) {
    this.runnerId = runnerId;
  }

  /** El proveedor ha confirmado la sesión. */
  void startSession(String sessionId, String model, Instant at) {
    if (sessionId != null) {
      providerSessionId = sessionId;
    }
    if (model != null) {
      this.model = model;
    }
    touch(at);
  }

  /** Registra actividad; los eventos pueden llegar desordenados, así que solo avanza. */
  void touch(Instant at) {
    if (lastActivityAt == null || at.isAfter(lastActivityAt)) {
      lastActivityAt = at;
    }
  }

  /**
   * Registra el resultado que declara el proveedor. Claude Code informa del coste acumulado de la
   * sesión, así que el de esta invocación es la diferencia con el de su predecesora.
   */
  void recordResult(
      String subtype,
      boolean isError,
      Integer numTurns,
      Long inputTokens,
      Long outputTokens,
      BigDecimal costCumulative,
      BigDecimal previousCumulative,
      String error) {
    resultSubtype = subtype;
    resultIsError = isError;
    this.numTurns = numTurns;
    this.inputTokens = inputTokens;
    this.outputTokens = outputTokens;
    if (costCumulative != null) {
      costUsdCumulative = costCumulative;
      costUsd =
          previousCumulative == null
              ? costCumulative
              : costCumulative.subtract(previousCumulative).max(BigDecimal.ZERO);
    }
    if (isError && error != null) {
      this.error = error;
    }
  }

  /**
   * Estado final al terminar el proceso: cancelado si lo pedimos nosotros; completado solo con un
   * resultado sin error y código de salida 0; fallido en cualquier otro caso.
   */
  AgentObservableStatus exited(Integer exitCode, String signal) {
    this.exitCode = exitCode;
    if (cancelRequestedAt != null) {
      return AgentObservableStatus.CANCELLED;
    }
    if (Boolean.FALSE.equals(resultIsError) && Integer.valueOf(0).equals(exitCode)) {
      return AgentObservableStatus.COMPLETED;
    }
    if (error == null) {
      error =
          resultIsError == null
              ? "El proceso terminó sin resultado (código "
                  + exitCode
                  + (signal != null ? ", señal " + signal : "")
                  + ")"
              : "El proceso terminó con código " + exitCode;
    }
    return AgentObservableStatus.FAILED;
  }

  /** Marca la cancelación pedida. Devuelve {@code false} si ya estaba pedida. */
  boolean requestCancel(Instant now) {
    if (cancelRequestedAt != null) {
      return false;
    }
    cancelRequestedAt = now;
    return true;
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

  public Instant getCancelRequestedAt() {
    return cancelRequestedAt;
  }

  public String getResultSubtype() {
    return resultSubtype;
  }

  public Boolean getResultIsError() {
    return resultIsError;
  }

  public Long getVersion() {
    return version;
  }
}
