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
 * @param restore instantánea ({@code snapshot} de {@code workspace.ready}) a la que se devuelve el
 *     worktree antes de arrancar: el reintento de una fase parte de cómo lo dejaron sus
 *     dependencias, no de lo que dejó el intento fallido (W6); {@code null} para usarlo tal cual
 */
public record WorkspaceRef(String path, String branch, boolean copy, String restore) {

  public WorkspaceRef {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(branch, "branch");
    if (copy && restore != null) {
      throw new IllegalArgumentException("Una copia no restaura el worktree del que parte");
    }
  }

  public WorkspaceRef(String path, String branch, boolean copy) {
    this(path, branch, copy, null);
  }

  /** La sesión arranca en el propio worktree. */
  public WorkspaceRef(String path, String branch) {
    this(path, branch, false, null);
  }
}
