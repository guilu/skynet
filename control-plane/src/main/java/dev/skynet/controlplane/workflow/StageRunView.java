package dev.skynet.controlplane.workflow;

import dev.skynet.protocol.StageStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Proyección de una fase con sus agentes. */
public record StageRunView(
    UUID id,
    String stageKey,
    StageStatus status,
    int attempt,
    Instant startedAt,
    Instant finishedAt,
    List<AgentRunView> agents) {

  static StageRunView of(StageRun s, List<AgentRunView> agents) {
    return new StageRunView(
        s.getId(),
        s.getStageKey(),
        s.getStatus(),
        s.getAttempt(),
        s.getStartedAt(),
        s.getFinishedAt(),
        agents);
  }
}
