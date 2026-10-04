package dev.skynet.protocol;

/** Estados observables de un agente (§6.2 de la especificación). */
public enum AgentObservableStatus {
  QUEUED,
  STARTING,
  THINKING,
  EXECUTING,
  WAITING_FOR_INPUT,
  WAITING_FOR_APPROVAL,
  UNRESPONSIVE,
  COMPLETED,
  FAILED,
  CANCELLED;

  public boolean isTerminal() {
    return this == COMPLETED || this == FAILED || this == CANCELLED;
  }
}
