package dev.skynet.protocol.runner;

import java.util.Objects;

/**
 * Worktree existente en el que arranca una sesión nueva: la de una fase que continúa el trabajo de
 * la fase de la que depende (W2). Al contrario que {@link ResumeFrom}, no reanuda ninguna sesión.
 *
 * @param path ruta del worktree en la máquina del runner
 * @param branch su rama
 */
public record WorkspaceRef(String path, String branch) {

  public WorkspaceRef {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(branch, "branch");
  }
}
