package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.definition.StageDefinition;
import dev.skynet.controlplane.definition.StageDefinition.Dependency;
import dev.skynet.protocol.StageStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Proyección de una fase con sus agentes y lo que dice de ella su definición.
 *
 * @param name nombre de la fase en el YAML, o {@code null}
 * @param agent agente con nombre que la ejecuta, o {@code null} si usa la política del repositorio
 * @param dependsOn fases que tienen que terminar antes, en el orden del YAML
 */
public record StageRunView(
    UUID id,
    String stageKey,
    String name,
    String agent,
    List<Dependency> dependsOn,
    StageStatus status,
    int attempt,
    Instant startedAt,
    Instant finishedAt,
    List<AgentRunView> agents) {

  /**
   * @param definition su definición, o {@code null} si la versión ya no se puede leer
   */
  static StageRunView of(StageRun s, StageDefinition definition, List<AgentRunView> agents) {
    return new StageRunView(
        s.getId(),
        s.getStageKey(),
        definition == null ? null : definition.name(),
        definition == null ? null : definition.agent(),
        definition == null ? List.of() : definition.dependsOn(),
        s.getStatus(),
        s.getAttempt(),
        s.getStartedAt(),
        s.getFinishedAt(),
        agents);
  }
}
