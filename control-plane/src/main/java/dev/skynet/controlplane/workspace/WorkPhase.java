package dev.skynet.controlplane.workspace;

/**
 * Fase de un trabajo, derivada de sus ejecuciones, sus aprobaciones y sus PRs (FS-B). No se guarda:
 * se calcula al leer.
 */
public enum WorkPhase {
  /** Sin intentos, o el último se canceló. */
  TODO,
  /** Un intento en marcha o en cola. */
  RUNNING,
  /** Un intento espera una decisión tuya (aprobación pendiente). */
  WAITING,
  /** El último intento falló y no hay ninguno en marcha. */
  STUCK,
  /** Hay una PR abierta. */
  REVIEW,
  /** Cerrado, con la PR fusionada o con el último intento completado. */
  DONE;

  static WorkPhase of(
      boolean closed, boolean waiting, boolean running, String pullRequest, String lastRun) {
    if (closed) {
      return DONE;
    }
    if (waiting) {
      return WAITING;
    }
    if (running) {
      return RUNNING;
    }
    if ("OPEN".equals(pullRequest)) {
      return REVIEW;
    }
    if ("MERGED".equals(pullRequest) || "SUCCEEDED".equals(lastRun)) {
      return DONE;
    }
    if ("FAILED".equals(lastRun)) {
      return STUCK;
    }
    return TODO;
  }
}
