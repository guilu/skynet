package dev.skynet.controlplane.workflow;

/** Cómo se originó una invocación del agente (§9.2). */
public enum AgentRunKind {
  START,
  RESUME,
  RETRY,
  FORK
}
