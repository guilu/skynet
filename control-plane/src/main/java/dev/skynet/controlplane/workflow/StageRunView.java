package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.definition.StageDefinition;
import dev.skynet.controlplane.definition.StageDefinition.Dependency;
import dev.skynet.controlplane.definition.WorkflowDefinition;
import dev.skynet.protocol.AgentFailure;
import dev.skynet.protocol.StageStatus;
import dev.skynet.protocol.runner.VerificationCheck;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Proyección de una fase con sus agentes y lo que dice de ella su definición.
 *
 * @param name nombre de la fase en el YAML, o {@code null}
 * @param agent agente con nombre que la ejecuta, o {@code null} si usa la política del repositorio
 * @param dependsOn fases que tienen que terminar antes, en el orden del YAML
 * @param type tipo de la fase en el YAML ({@code agent}, {@code command}…), o {@code null}
 * @param command comando de shell de una fase {@code command}, o {@code null}
 * @param condition condición que decide si se ejecuta, o {@code null} si se ejecuta siempre
 * @param checks lo que Skynet comprueba en su worktree antes de darla por buena; vacío si nada
 * @param reads datos de otras fases que lee su prompt, sin {@code stages.} ({@code
 *     spec.output.summary})
 * @param verifications la última verificación de fase de cada uno de sus agentes; con una en curso,
 *     la fase está «Verificando»
 * @param approval la última petición de aprobación de una fase {@code human-approval}, o {@code
 *     null} si no ha preguntado todavía (o no es una puerta)
 * @param retry cuándo se reintenta la fase si falla ({@code retry} del YAML), o {@code null}
 * @param notBefore un reintento que no empieza antes de esta hora, o {@code null} (W6)
 * @param timeoutSeconds el {@code timeout} de la fase en segundos, o {@code null} (W6-B)
 * @param deadline el intento tiene que terminar antes de esta hora, o {@code null}
 * @param timedOutAt cuándo llegó a su límite, o {@code null}
 * @param cancelling se ha pedido parar alguno de sus agentes y el runner aún no lo ha confirmado:
 *     «Cancelando»
 * @param retriedBy quién pidió este intento con «Reintentar fase» (W6-C), o {@code null} si lo
 *     programó el motor
 * @param children los hijos de una fase {@code parallel} en el orden del YAML, para dibujarlos
 *     antes de que tengan agente (W7-B); vacío en las demás
 * @param quotaWait la cuota que espera la fase antes de empezar (CQ-A), o {@code null}
 */
public record StageRunView(
    UUID id,
    String stageKey,
    String name,
    String agent,
    List<Dependency> dependsOn,
    String type,
    String command,
    String condition,
    List<VerificationCheck> checks,
    List<String> reads,
    StageStatus status,
    int attempt,
    Instant startedAt,
    Instant finishedAt,
    List<AgentRunView> agents,
    List<VerificationResult> verifications,
    ApprovalSummary approval,
    RetryView retry,
    Instant notBefore,
    Long timeoutSeconds,
    Instant deadline,
    Instant timedOutAt,
    boolean cancelling,
    String retriedBy,
    List<String> children,
    String phase,
    QuotaWaitView quotaWait,
    JudgeView judge,
    RiskView risk) {

  /** Sin nivel de riesgo (antes de AU-A). */
  public StageRunView(
      UUID id,
      String stageKey,
      String name,
      String agent,
      List<Dependency> dependsOn,
      String type,
      String command,
      String condition,
      List<VerificationCheck> checks,
      List<String> reads,
      StageStatus status,
      int attempt,
      Instant startedAt,
      Instant finishedAt,
      List<AgentRunView> agents,
      List<VerificationResult> verifications,
      ApprovalSummary approval,
      RetryView retry,
      Instant notBefore,
      Long timeoutSeconds,
      Instant deadline,
      Instant timedOutAt,
      boolean cancelling,
      String retriedBy,
      List<String> children,
      String phase,
      QuotaWaitView quotaWait,
      JudgeView judge) {
    this(
        id,
        stageKey,
        name,
        agent,
        dependsOn,
        type,
        command,
        condition,
        checks,
        reads,
        status,
        attempt,
        startedAt,
        finishedAt,
        agents,
        verifications,
        approval,
        retry,
        notBefore,
        timeoutSeconds,
        deadline,
        timedOutAt,
        cancelling,
        retriedBy,
        children,
        phase,
        quotaWait,
        judge,
        null);
  }

  /**
   * La cuota que espera una fase (CQ-A): de qué proveedor, qué ventana ({@code five_hour} o {@code
   * seven_day}, o {@code null}) y cuándo se renueva ({@code null} si no se sabe). La fase sigue en
   * {@code notBefore}.
   */
  public record QuotaWaitView(String provider, String window, Instant resetsAt) {

    static QuotaWaitView of(StageRun stage) {
      return stage.getQuotaProvider() == null
          ? null
          : new QuotaWaitView(
              stage.getQuotaProvider(), stage.getQuotaWindow(), stage.getQuotaResetsAt());
    }
  }

  /**
   * El {@code retry} de una fase: intentos en total, qué fallos ({@code lost}, {@code error}…) y la
   * primera espera en segundos.
   */
  public record RetryView(int maxAttempts, List<String> on, long delaySeconds) {

    static RetryView of(StageDefinition.Retry retry) {
      return retry == null
          ? null
          : new RetryView(
              retry.maxAttempts(),
              retry.on().stream().map(AgentFailure::id).toList(),
              retry.delay().toSeconds());
    }
  }

  /**
   * @param workflow el workflow de la ejecución, o {@code null} si la versión ya no se puede leer
   */
  static StageRunView of(
      StageRun s,
      WorkflowDefinition workflow,
      List<AgentRunView> agents,
      List<VerificationResult> verifications,
      ApprovalSummary approval,
      JudgeView judge,
      RiskView risk) {
    StageDefinition definition =
        workflow == null ? null : workflow.stage(s.getStageKey()).orElse(null);
    return new StageRunView(
        s.getId(),
        s.getStageKey(),
        definition == null ? null : definition.name(),
        definition == null ? null : definition.agent(),
        definition == null ? List.of() : definition.dependsOn(),
        definition == null ? null : definition.type().yaml(),
        definition == null ? null : definition.command(),
        definition == null ? null : definition.condition(),
        definition == null ? List.of() : definition.checks(),
        definition == null ? List.of() : workflow.reads(s.getStageKey()),
        s.getStatus(),
        s.getAttempt(),
        s.getStartedAt(),
        s.getFinishedAt(),
        agents,
        verifications,
        approval,
        definition == null ? null : RetryView.of(definition.retry()),
        s.getNotBefore(),
        definition == null ? null : definition.timeoutSeconds(),
        s.getDeadline(),
        s.getTimedOutAt(),
        agents.stream().anyMatch(a -> a.cancelRequestedAt() != null && !a.status().isTerminal()),
        s.getRetriedBy(),
        definition == null
            ? List.of()
            : definition.children().stream().map(StageDefinition.ChildDefinition::id).toList(),
        definition == null ? null : definition.phase(),
        QuotaWaitView.of(s),
        judge,
        risk);
  }
}
