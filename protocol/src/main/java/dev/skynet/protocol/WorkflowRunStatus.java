package dev.skynet.protocol;

import java.util.EnumSet;
import java.util.Set;

/** Estado global de una ejecución de workflow. Las esperas se reflejan en sus fases. */
public enum WorkflowRunStatus {
  PENDING,
  RUNNING,
  SUCCEEDED,
  FAILED,
  CANCELLED;

  public boolean isTerminal() {
    return this == SUCCEEDED || this == FAILED || this == CANCELLED;
  }

  public boolean canTransitionTo(WorkflowRunStatus next) {
    return allowedNext().contains(next);
  }

  public Set<WorkflowRunStatus> allowedNext() {
    return switch (this) {
      case PENDING -> EnumSet.of(RUNNING, CANCELLED);
      case RUNNING -> EnumSet.of(SUCCEEDED, FAILED, CANCELLED);
      case SUCCEEDED, FAILED, CANCELLED -> EnumSet.noneOf(WorkflowRunStatus.class);
    };
  }
}
