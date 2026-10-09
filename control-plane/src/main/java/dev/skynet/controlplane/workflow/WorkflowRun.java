package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.shared.ConflictException;
import dev.skynet.protocol.WorkflowRunStatus;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

@Table("workflow_run")
public class WorkflowRun {

  @Id private final UUID id;
  private final UUID workItemId;
  private final UUID definitionId;
  private WorkflowRunStatus status;
  private final Instant createdAt;

  /** Lo escribe solo el archivado (UPDATE directo); el resto de guardados no lo toca. */
  @ReadOnlyProperty private Instant archivedAt;

  private Instant startedAt;
  private Instant finishedAt;
  @Version private Long version;

  @PersistenceCreator
  WorkflowRun(
      UUID id,
      UUID workItemId,
      UUID definitionId,
      WorkflowRunStatus status,
      Instant createdAt,
      Instant startedAt,
      Instant finishedAt,
      Long version) {
    this.id = id;
    this.workItemId = workItemId;
    this.definitionId = definitionId;
    this.status = status;
    this.createdAt = createdAt;
    this.startedAt = startedAt;
    this.finishedAt = finishedAt;
    this.version = version;
  }

  static WorkflowRun create(UUID workItemId, UUID definitionId, Instant now) {
    return new WorkflowRun(
        UUID.randomUUID(),
        workItemId,
        definitionId,
        WorkflowRunStatus.PENDING,
        now,
        null,
        null,
        null);
  }

  /** Aplica la transición y devuelve el estado anterior. */
  WorkflowRunStatus transitionTo(WorkflowRunStatus next, Instant now) {
    if (!status.canTransitionTo(next)) {
      throw new ConflictException(
          "La ejecución " + id + " no puede pasar de " + status + " a " + next);
    }
    WorkflowRunStatus previous = status;
    status = next;
    if (next == WorkflowRunStatus.RUNNING && startedAt == null) {
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

  public UUID getWorkItemId() {
    return workItemId;
  }

  public UUID getDefinitionId() {
    return definitionId;
  }

  public WorkflowRunStatus getStatus() {
    return status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getArchivedAt() {
    return archivedAt;
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
