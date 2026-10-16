package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.definition.StageDefinition;
import dev.skynet.controlplane.definition.StageDefinition.Dependency;
import dev.skynet.controlplane.definition.WorkflowDefinition;
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
    ApprovalSummary approval) {

  /**
   * @param workflow el workflow de la ejecución, o {@code null} si la versión ya no se puede leer
   */
  static StageRunView of(
      StageRun s,
      WorkflowDefinition workflow,
      List<AgentRunView> agents,
      List<VerificationResult> verifications,
      ApprovalSummary approval) {
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
        approval);
  }
}
