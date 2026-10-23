package dev.skynet.controlplane.memory;

/** Tipo de observación. {@link #RUN_LOG} lo escribe solo Skynet, al terminar cada ejecución. */
public enum MemoryKind {
  DECISION,
  BUGFIX,
  DISCOVERY,
  PATTERN,
  CONFIG,
  PITFALL,
  CONSTRAINT,
  SESSION_SUMMARY,
  RUN_LOG
}
