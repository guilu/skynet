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

  /** Un reintento no empieza antes de esta hora (la espera de {@code retry.delay}, W6). */
  private Instant notBefore;

  /** Con {@code timeout}, el intento tiene que terminar antes de esta hora (W6-B). */
  private Instant deadline;

  /** Llegó a su {@code deadline} y se le pararon los agentes. */
  private Instant timedOutAt;

  /**
   * Quién pidió este intento con «Reintentar fase» (W6-C); {@code null} si lo programó el motor.
   */
  private String retriedBy;

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

  /**
   * El intento siguiente de una fase que ha fallado: pendiente, sin empezar antes de {@code
   * notBefore}.
   */
  static StageRun retry(StageRun failed, Instant notBefore, Instant now) {
    StageRun next =
        new StageRun(
            UUID.randomUUID(),
            failed.workflowRunId,
            failed.stageKey,
            StageStatus.PENDING,
            failed.attempt + 1,
            now,
            null,
            null,
            null);
    next.notBefore = notBefore;
    return next;
  }

  /**
   * El intento siguiente de una fase que ha terminado, pedido por {@code actor} con «Reintentar
   * fase»: pendiente y sin espera.
   */
  static StageRun retriedBy(StageRun previous, String actor, Instant now) {
    StageRun next = retry(previous, null, now);
    next.retriedBy = actor;
    return next;
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

  public Instant getNotBefore() {
    return notBefore;
  }

  public Instant getDeadline() {
    return deadline;
  }

  public Instant getTimedOutAt() {
    return timedOutAt;
  }

  public String getRetriedBy() {
    return retriedBy;
  }

  /** Fija hasta cuándo puede durar el intento, al pasar a lista. */
  void limitUntil(Instant deadline) {
    this.deadline = deadline;
  }

  /** Marca que llegó a su límite. Devuelve {@code false} si ya estaba marcado. */
  boolean timeOut(Instant now) {
    if (timedOutAt != null) {
      return false;
    }
    timedOutAt = now;
    return true;
  }

  public Long getVersion() {
    return version;
  }
}
