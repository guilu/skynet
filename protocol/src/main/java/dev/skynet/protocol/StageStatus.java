package dev.skynet.protocol;

import java.util.EnumSet;
import java.util.Set;

/** Estados de una fase (§6.1 de la especificación) y sus transiciones permitidas. */
public enum StageStatus {
  PENDING,
  READY,
  STARTING,
  RUNNING,
  WAITING_FOR_INPUT,
  WAITING_FOR_APPROVAL,
  SUCCEEDED,
  FAILED,
  CANCELLED,
  SKIPPED;

  public boolean isTerminal() {
    return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == SKIPPED;
  }

  public boolean canTransitionTo(StageStatus next) {
    return allowedNext().contains(next);
  }

  public Set<StageStatus> allowedNext() {
    return switch (this) {
      case PENDING -> EnumSet.of(READY, SKIPPED, CANCELLED);
      case READY -> EnumSet.of(STARTING, SKIPPED, CANCELLED);
      case STARTING -> EnumSet.of(RUNNING, FAILED, CANCELLED);
      case RUNNING ->
          EnumSet.of(WAITING_FOR_INPUT, WAITING_FOR_APPROVAL, SUCCEEDED, FAILED, CANCELLED);
      case WAITING_FOR_INPUT -> EnumSet.of(RUNNING, FAILED, CANCELLED);
      case WAITING_FOR_APPROVAL -> EnumSet.of(RUNNING, SUCCEEDED, FAILED, CANCELLED);
      // Un reintento vuelve a preparar la fase con un nuevo intento.
      case FAILED -> EnumSet.of(READY);
      case SUCCEEDED, CANCELLED, SKIPPED -> EnumSet.noneOf(StageStatus.class);
    };
  }
}
