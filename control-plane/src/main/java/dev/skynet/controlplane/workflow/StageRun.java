package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.shared.ConflictException;
import dev.skynet.protocol.StageStatus;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

@Table("stage_run")
public class StageRun {

  @Id private final UUID id;
  private final UUID workflowRunId;
  private final String stageKey;
  private StageStatus status;
  private final int attempt;
  private final Instant createdAt;
  private Instant startedAt;
  private Instant finishedAt;
  @Version private Long version;

  @PersistenceCreator
  StageRun(
      UUID id,
      UUID workflowRunId,
      String stageKey,
      StageStatus status,
      int attempt,
      Instant createdAt,
      Instant startedAt,
      Instant finishedAt,
      Long version) {
    this.id = id;
    this.workflowRunId = workflowRunId;
    this.stageKey = stageKey;
    this.status = status;
    this.attempt = attempt;
    this.createdAt = createdAt;
    this.startedAt = startedAt;
    this.finishedAt = finishedAt;
    this.version = version;
  }

  static StageRun create(UUID workflowRunId, String stageKey, Instant now) {
    return new StageRun(
        UUID.randomUUID(), workflowRunId, stageKey, StageStatus.PENDING, 1, now, null, null, null);
  }

  /** Aplica la transición y devuelve el estado anterior. */
  StageStatus transitionTo(StageStatus next, Instant now) {
    if (!status.canTransitionTo(next)) {
      throw new ConflictException(
          "La fase " + stageKey + " no puede pasar de " + status + " a " + next);
    }
    StageStatus previous = status;
    status = next;
    if (next == StageStatus.STARTING && startedAt == null) {
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

  public UUID getWorkflowRunId() {
    return workflowRunId;
  }

  public String getStageKey() {
    return stageKey;
  }

  public StageStatus getStatus() {
    return status;
  }

  public int getAttempt() {
    return attempt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public Instant getFinishedAt() {
    return finishedAt;
  }

  public Long getVersion() {
    return version;
  }
}
