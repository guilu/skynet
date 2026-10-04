package dev.skynet.protocol;

import java.util.EnumSet;
import java.util.Set;

/**
 * Estados observables de un agente (§6.2 de la especificación) y sus transiciones permitidas.
 *
 * <p>Mientras el proceso está vivo, el agente puede alternar libremente entre los estados "en
 * ejecución" ({@link #isActive()}); los estados terminales no admiten salida.
 */
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

  private static final Set<AgentObservableStatus> ACTIVE =
      EnumSet.of(THINKING, EXECUTING, WAITING_FOR_INPUT, WAITING_FOR_APPROVAL, UNRESPONSIVE);

  public boolean isTerminal() {
    return this == COMPLETED || this == FAILED || this == CANCELLED;
  }

  /** El proceso del agente está vivo y ha empezado a trabajar. */
  public boolean isActive() {
    return ACTIVE.contains(this);
  }

  public boolean canTransitionTo(AgentObservableStatus next) {
    return allowedNext().contains(next);
  }

  public Set<AgentObservableStatus> allowedNext() {
    if (isTerminal()) {
      return EnumSet.noneOf(AgentObservableStatus.class);
    }
    return switch (this) {
      case QUEUED -> EnumSet.of(STARTING, FAILED, CANCELLED);
      case STARTING -> EnumSet.of(THINKING, EXECUTING, FAILED, CANCELLED);
      default -> {
        Set<AgentObservableStatus> next = EnumSet.copyOf(ACTIVE);
        next.remove(this);
        next.addAll(EnumSet.of(COMPLETED, FAILED, CANCELLED));
        yield next;
      }
    };
  }
}
