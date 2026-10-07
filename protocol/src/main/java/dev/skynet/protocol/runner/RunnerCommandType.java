package dev.skynet.protocol.runner;

/** Órdenes que el control plane envía a un runner (docs/implementation-plan.md §4.3). */
public enum RunnerCommandType {
  /** Lanza una invocación nueva del agente. */
  START,
  /** Continúa una sesión existente con un mensaje nuevo (M4). */
  RESUME,
  /** Termina la invocación y todo su árbol de procesos. */
  CANCEL,
  /** Ejecuta la verificación de un worktree, independiente del agente (M5). */
  VERIFY,
  /** Elimina un worktree que ya no se usa, conservando su rama (M6). */
  CLEANUP
}
