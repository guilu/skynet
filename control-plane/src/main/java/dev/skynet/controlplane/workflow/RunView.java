package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.workitem.WorkItem;
import dev.skynet.protocol.WorkflowRunStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Ejecución de workflow con sus fases y agentes, tal y como la consume la web.
 *
 * @param currentStageRunId fase que no ha terminado, o {@code null} si todas han terminado
 * @param currentAgentRunId agente que no ha terminado (en cola, arrancando o trabajando), o {@code
 *     null}
 * @param archivedAt cuándo se archivó la ejecución; no cuenta el archivado de su trabajo o proyecto
 * @param totals tokens y coste sumados de todos los agentes
 */
public record RunView(
    UUID id,
    UUID workItemId,
    String workItemKey,
    String workItemTitle,
    UUID projectId,
    WorkflowRunStatus status,
    Instant createdAt,
    Instant startedAt,
    Instant finishedAt,
    Instant archivedAt,
    UUID currentStageRunId,
    UUID currentAgentRunId,
    RunTotals totals,
    List<StageRunView> stages) {

  static RunView of(WorkflowRun r, WorkItem workItem, List<StageRunView> stages) {
    List<AgentRunView> agents = stages.stream().flatMap(s -> s.agents().stream()).toList();
    return new RunView(
        r.getId(),
        r.getWorkItemId(),
        workItem == null ? null : workItem.getKey(),
        workItem == null ? null : workItem.getTitle(),
        workItem == null ? null : workItem.getProjectId(),
        r.getStatus(),
        r.getCreatedAt(),
        r.getStartedAt(),
        r.getFinishedAt(),
        r.getArchivedAt(),
        stages.stream()
            .filter(s -> !s.status().isTerminal())
            .map(StageRunView::id)
            .reduce((first, last) -> last)
            .orElse(null),
        agents.stream()
            .filter(a -> !a.status().isTerminal())
            .map(AgentRunView::id)
            .reduce((first, last) -> last)
            .orElse(null),
        RunTotals.of(agents),
        stages);
  }
}
