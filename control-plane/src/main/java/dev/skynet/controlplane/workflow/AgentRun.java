package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.shared.ConflictException;
import dev.skynet.protocol.AgentFailure;
import dev.skynet.protocol.AgentObservableStatus;
import dev.skynet.protocol.runner.AgentLimits;
import java.math.BigDecimal;
import java.time.Duration;
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

  /** Una fase {@code command}: el runner ejecuta un comando de shell en lugar de un agente. */
  public static final String PROVIDER_COMMAND = "command";

  /**
   * Una fase {@code verification}: no hay agente ni comando, solo la verificación de la fase en el
   * worktree que hereda. Su estado es el de esa verificación.
   */
  public static final String PROVIDER_VERIFICATION = "verification";

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
  private Long cacheReadTokens;
  private Long cacheCreationTokens;
  private String lastEventType;
  private String currentTool;
  private UUID workspaceId;
  private final Integer maxTurns;
  private final BigDecimal maxBudgetUsd;
  private final Long timeoutSeconds;

  /** Hijo de una fase {@code parallel} que ejecuta, o {@code null} en las demás fases. */
  private String childKey;

  /** JSON Schema de la salida estructurada que tiene que devolver, o {@code null}. */
  private String outputSchema;

  /** La salida estructurada que devolvió (JSON), o {@code null}. */
  private String structuredOutput;

  /** {@code HEAD} del worktree al empezar: la base de los checks {@code diff} y {@code commit}. */
  private String baseCommit;

  /**
   * Instantánea del worktree que continúa, tal como estaba al empezar ({@code snapshot} de {@code
   * workspace.ready}): a ella vuelve un reintento de la fase (W6).
   */
  private String baseSnapshot;

  /** Por qué falló, o {@code null} si no ha fallado (W6). */
  private AgentFailure failure;

  /**
   * Se paró porque su fase llegó a su {@code timeout} (W6-B): termina {@code FAILED} con {@link
   * AgentFailure#TIMEOUT} en vez de {@code CANCELLED}.
   */
  private Instant timedOutAt;

  /** Se ejecutó con un CLI simulado (fake-claude, fake-codex…), no con el real (AR-A). */
  private boolean simulated;

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
      Long cacheReadTokens,
      Long cacheCreationTokens,
      String lastEventType,
      String currentTool,
      UUID workspaceId,
      Integer maxTurns,
      BigDecimal maxBudgetUsd,
      Long timeoutSeconds,
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
    this.cacheReadTokens = cacheReadTokens;
    this.cacheCreationTokens = cacheCreationTokens;
    this.lastEventType = lastEventType;
    this.currentTool = currentTool;
    this.workspaceId = workspaceId;
    this.maxTurns = maxTurns;
    this.maxBudgetUsd = maxBudgetUsd;
    this.timeoutSeconds = timeoutSeconds;
    this.version = version;
  }

  /**
   * Agente en cola. El id de sesión se fija aquí y el runner lo pasa al proveedor ({@code
   * --session-id}), de modo que se conoce antes de que el proceso arranque.
   */
  static AgentRun queued(
      UUID stageRunId,
      UUID repositoryId,
      String provider,
      UUID sessionId,
      AgentLimits limits,
      Instant now) {
    return queued(stageRunId, repositoryId, provider, sessionId, null, limits, now);
  }

  /**
   * Lanzamiento en cola con sesión nueva; con {@code workspaceId}, en ese worktree (una fase que
   * continúa el de la fase de la que depende) en vez de en uno nuevo.
   */
  static AgentRun queued(
      UUID stageRunId,
      UUID repositoryId,
      String provider,
      UUID sessionId,
      UUID workspaceId,
      AgentLimits limits,
      Instant now) {
    return create(
        stageRunId,
        new Origin(null, repositoryId, AgentRunKind.START, provider),
        sessionId,
        workspaceId,
        limits,
        now);
  }

  /**
   * Invocación en cola que parte de {@code parent}, con su repositorio y su proveedor: una
   * reanudación (misma sesión y worktree), un fork (sesión nueva bifurcada) o un reintento (sesión
   * y worktree nuevos).
   */
  static AgentRun queued(
      UUID stageRunId,
      AgentRun parent,
      AgentRunKind kind,
      UUID sessionId,
      UUID workspaceId,
      AgentLimits limits,
      Instant now) {
    return create(
        stageRunId,
        new Origin(parent.getId(), parent.getRepositoryId(), kind, parent.getProvider()),
        sessionId,
        workspaceId,
        limits,
        now);
  }

  /** De dónde sale una invocación nueva. */
  private record Origin(
      UUID parentAgentRunId, UUID repositoryId, AgentRunKind kind, String provider) {}

  private static AgentRun create(
      UUID stageRunId,
      Origin origin,
      UUID sessionId,
      UUID workspaceId,
      AgentLimits limits,
      Instant now) {
    return new AgentRun(
        UUID.randomUUID(),
        stageRunId,
        origin.parentAgentRunId(),
        origin.repositoryId(),
        origin.kind(),
        AgentObservableStatus.QUEUED,
        origin.provider(),
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
        null,
        null,
        null,
        null,
        workspaceId,
        limits.maxTurns(),
        limits.maxBudgetUsd(),
        limits.timeout() == null ? null : limits.timeout().toSeconds(),
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
      currentTool = null;
    }
    return previous;
  }

  /** Un runner ha reclamado la invocación. */
  void assignRunner(UUID runnerId) {
    this.runnerId = runnerId;
  }

  /** El proveedor ha confirmado la sesión. */
  void startSession(String sessionId, String model, boolean simulated, Instant at) {
    this.simulated = simulated;
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
   * Registra la actividad actual: el tipo del último evento y la herramienta en curso, que se abre
   * con {@code agent.tool.started} y se cierra con su resultado.
   */
  void recordActivity(String eventType, String toolStarted, boolean toolCompleted) {
    lastEventType = eventType;
    if (toolStarted != null) {
      currentTool = toolStarted;
    } else if (toolCompleted) {
      currentTool = null;
    }
  }

  /**
   * Suma los tokens de un mensaje del asistente, para verlos en vivo. El resultado final los
   * sustituye por los totales que declara el proveedor.
   */
  void addUsage(TokenUsage usage) {
    inputTokens = sum(inputTokens, usage.input());
    outputTokens = sum(outputTokens, usage.output());
    cacheReadTokens = sum(cacheReadTokens, usage.cacheRead());
    cacheCreationTokens = sum(cacheCreationTokens, usage.cacheCreation());
  }

  private static Long sum(Long total, Long value) {
    if (value == null) {
      return total;
    }
    return total == null ? value : total + value;
  }

  /**
   * Registra el resultado que declara el proveedor. Claude Code informa del coste acumulado de la
   * sesión, así que el de esta invocación es la diferencia con el de su predecesora; los demás
   * proveedores (Codex) informan del de la invocación, y el acumulado es la suma.
   */
  void recordResult(
      String subtype,
      boolean isError,
      Integer numTurns,
      TokenUsage usage,
      BigDecimal costCumulative,
      BigDecimal costInvocation,
      BigDecimal previousCumulative,
      String error) {
    resultSubtype = subtype;
    resultIsError = isError;
    this.numTurns = numTurns;
    if (usage.input() != null || usage.output() != null) {
      inputTokens = usage.input();
      outputTokens = usage.output();
      cacheReadTokens = usage.cacheRead();
      cacheCreationTokens = usage.cacheCreation();
    }
    if (costCumulative != null) {
      costUsdCumulative = costCumulative;
      costUsd =
          previousCumulative == null
              ? costCumulative
              : costCumulative.subtract(previousCumulative).max(BigDecimal.ZERO);
    } else if (costInvocation != null) {
      costUsd = costInvocation;
      costUsdCumulative =
          previousCumulative == null ? costInvocation : previousCumulative.add(costInvocation);
    }
    if (isError && error != null) {
      this.error = error;
    }
  }

  /**
   * Coste que estimó el runner con la salida del CLI, para un agente que terminó sin que el
   * proveedor declarase el suyo: uno cancelado (el fail-fast de una {@code parallel}), agotado o
   * caído antes del resultado. Un coste ya declarado no se toca.
   */
  void recordEstimatedCost(BigDecimal estimated, BigDecimal previousCumulative) {
    if (costUsd != null || estimated == null || estimated.signum() <= 0) {
      return;
    }
    costUsd = estimated;
    costUsdCumulative = previousCumulative == null ? estimated : previousCumulative.add(estimated);
  }

  /**
   * Estado final al terminar el proceso: cancelado si lo pedimos nosotros; completado solo con un
   * resultado sin error y código de salida 0; fallido en cualquier otro caso.
   */
  AgentObservableStatus exited(Integer exitCode, String signal, String runnerError) {
    return exited(exitCode, signal, runnerError, null);
  }

  /**
   * Como {@link #exited(Integer, String, String)}, con la categoría del fallo que manda el runner
   * ({@code failure}): sin ella, un error del runner cuenta como {@link AgentFailure#ERROR}. Un
   * resultado que agota los turnos o el presupuesto cuenta como {@link AgentFailure#BUDGET} y una
   * salida estructurada que falta, como {@link AgentFailure#VERIFICATION}.
   */
  AgentObservableStatus exited(
      Integer exitCode, String signal, String runnerError, AgentFailure runnerFailure) {
    this.exitCode = exitCode;
    if (cancelRequestedAt != null) {
      return timedOutAt != null ? timedOutStatus() : AgentObservableStatus.CANCELLED;
    }
    if (runnerError != null) {
      error = runnerError;
      failure = runnerFailure != null ? runnerFailure : AgentFailure.ERROR;
      return AgentObservableStatus.FAILED;
    }
    if (isCommand()) {
      if (Integer.valueOf(0).equals(exitCode)) {
        return AgentObservableStatus.COMPLETED;
      }
      error =
          "El comando terminó con código "
              + exitCode
              + (signal != null ? " (señal " + signal + ")" : "");
      failure = AgentFailure.ERROR;
      return AgentObservableStatus.FAILED;
    }
    if (Boolean.FALSE.equals(resultIsError) && Integer.valueOf(0).equals(exitCode)) {
      if (outputSchema != null && structuredOutput == null) {
        error = "El agente no devolvió la salida estructurada que pide su `output`";
        failure = AgentFailure.VERIFICATION;
        return AgentObservableStatus.FAILED;
      }
      return AgentObservableStatus.COMPLETED;
    }
    failure =
        resultSubtype != null
                && (resultSubtype.contains("max_turns") || resultSubtype.contains("budget"))
            ? AgentFailure.BUDGET
            : AgentFailure.ERROR;
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

  /** El runner ha preparado el worktree de la invocación. */
  void useWorkspace(UUID workspaceId) {
    this.workspaceId = workspaceId;
  }

  /** Límites efectivos con los que se lanzó la invocación. */
  AgentLimits limits() {
    return new AgentLimits(
        maxTurns, maxBudgetUsd, timeoutSeconds == null ? null : Duration.ofSeconds(timeoutSeconds));
  }

  /**
   * Su fase ha llegado a su {@code timeout}: lo que quede de él se para y cuenta como fallo por
   * tiempo. Devuelve {@code false} si ya estaba marcado.
   */
  boolean timeOut(Instant now) {
    if (timedOutAt != null) {
      return false;
    }
    timedOutAt = now;
    return true;
  }

  /** El estado de un agente parado por el {@code timeout} de su fase. */
  AgentObservableStatus timedOutStatus() {
    error = "Se acabó el tiempo de su fase";
    failure = AgentFailure.TIMEOUT;
    return AgentObservableStatus.FAILED;
  }

  public Instant getTimedOutAt() {
    return timedOutAt;
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

  public String getChildKey() {
    return childKey;
  }

  public String getOutputSchema() {
    return outputSchema;
  }

  public String getStructuredOutput() {
    return structuredOutput;
  }

  /** Tiene que devolver una salida estructurada que cumpla {@code schema} (JSON). */
  void expectOutput(String schema) {
    this.outputSchema = schema;
  }

  /** La salida estructurada del resultado, en JSON. */
  void recordStructuredOutput(String json) {
    this.structuredOutput = json;
  }

  public boolean isSimulated() {
    return simulated;
  }

  /** La invocación es el hijo {@code childKey} de una fase {@code parallel}. */
  void forChild(String childKey) {
    this.childKey = childKey;
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

  /** Si es una fase {@code command}: sin sesión que reanudar ni verificación. */
  public boolean isCommand() {
    return PROVIDER_COMMAND.equals(provider);
  }

  public boolean isVerification() {
    return PROVIDER_VERIFICATION.equals(provider);
  }

  public String getBaseCommit() {
    return baseCommit;
  }

  /** Por qué falla una invocación que no termina con un proceso (una fase {@code verification}). */
  void fail(String reason) {
    this.error = reason;
    this.failure = AgentFailure.VERIFICATION;
  }

  /** Por qué falló, o {@code null} si no ha fallado. */
  public AgentFailure getFailure() {
    return failure;
  }

  public String getBaseSnapshot() {
    return baseSnapshot;
  }

  /** La instantánea del worktree cuando la invocación lo recibió; solo cuenta la primera. */
  void recordBaseSnapshot(String snapshot) {
    if (baseSnapshot == null) {
      baseSnapshot = snapshot;
    }
  }

  /** El {@code HEAD} del worktree cuando la invocación lo recibió; solo cuenta el primero. */
  void recordBaseCommit(String commit) {
    if (baseCommit == null) {
      baseCommit = commit;
    }
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

  public Long getCacheReadTokens() {
    return cacheReadTokens;
  }

  public Long getCacheCreationTokens() {
    return cacheCreationTokens;
  }

  public String getLastEventType() {
    return lastEventType;
  }

  public String getCurrentTool() {
    return currentTool;
  }

  public UUID getWorkspaceId() {
    return workspaceId;
  }

  public Integer getMaxTurns() {
    return maxTurns;
  }

  public BigDecimal getMaxBudgetUsd() {
    return maxBudgetUsd;
  }

  public Long getTimeoutSeconds() {
    return timeoutSeconds;
  }

  public Long getVersion() {
    return version;
  }
}
