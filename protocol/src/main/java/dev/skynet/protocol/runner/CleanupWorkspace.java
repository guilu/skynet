package dev.skynet.protocol.runner;

import java.util.Objects;
import java.util.UUID;

/**
 * Eliminación de un worktree: el runner lo quita con {@code git worktree remove} y conserva su
 * rama, así que los commits no se pierden. Después ya no se puede reanudar, bifurcar ni verificar.
 *
 * @param workspaceId worktree en el control plane
 * @param workspacePath worktree en la máquina del runner
 */
public record CleanupWorkspace(UUID workspaceId, String workspacePath) {

  public CleanupWorkspace {
    Objects.requireNonNull(workspaceId, "workspaceId");
    Objects.requireNonNull(workspacePath, "workspacePath");
  }
}
