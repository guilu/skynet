package dev.skynet.protocol;

/** Estados de una fase (§6.1 de la especificación). */
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
}
