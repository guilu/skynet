package dev.skynet.controlplane.workflow;

import java.time.Instant;
import java.util.UUID;

/**
 * Worktree de una invocación en la máquina de su runner.
 *
 * @param cleanupRequestedAt cuándo se pidió eliminarlo, mientras el runner no responde
 * @param cleanupError por qué falló el último intento de eliminarlo
 * @param removedAt cuándo lo eliminó el runner; desde entonces no se puede reanudar, bifurcar ni
 *     verificar
 */
public record WorkspaceView(
    UUID id,
    UUID runnerId,
    String path,
    String branch,
    String baseCommit,
    Instant cleanupRequestedAt,
    String cleanupError,
    Instant removedAt) {

  /** No se puede escribir en él: eliminado o a punto de eliminarse. */
  boolean unusable() {
    return removedAt != null || cleanupRequestedAt != null;
  }
}
