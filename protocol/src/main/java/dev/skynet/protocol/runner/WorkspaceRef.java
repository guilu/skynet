package dev.skynet.protocol.runner;

import java.util.Objects;

/**
 * Worktree existente en el que arranca una sesión nueva: la de una fase que continúa el trabajo de
 * la fase de la que depende (W2). Al contrario que {@link ResumeFrom}, no reanuda ninguna sesión.
 *
 * @param path ruta del worktree en la máquina del runner
 * @param branch su rama
 * @param copy si la sesión arranca en un worktree nuevo creado desde una instantánea de este (con
 *     sus cambios sin confirmar y sus ficheros nuevos), sin tocarlo: los hijos de una fase {@code
 *     parallel} (W3)
 */
public record WorkspaceRef(String path, String branch, boolean copy) {

  public WorkspaceRef {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(branch, "branch");
  }

  /** La sesión arranca en el propio worktree. */
  public WorkspaceRef(String path, String branch) {
    this(path, branch, false);
  }
}
