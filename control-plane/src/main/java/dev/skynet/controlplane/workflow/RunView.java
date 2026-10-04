package dev.skynet.controlplane.workflow;

import dev.skynet.protocol.WorkflowRunStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Ejecución de workflow con sus fases y agentes, tal y como la consume la web. */
record RunView(
    UUID id,
    UUID workItemId,
    WorkflowRunStatus status,
    Instant createdAt,
    Instant startedAt,
    Instant finishedAt,
    List<StageRunView> stages) {

  static RunView of(WorkflowRun r, List<StageRunView> stages) {
    return new RunView(
        r.getId(),
        r.getWorkItemId(),
        r.getStatus(),
        r.getCreatedAt(),
        r.getStartedAt(),
        r.getFinishedAt(),
        stages);
  }
}
