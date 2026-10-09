package dev.skynet.controlplane.purge;

import java.util.List;

/**
 * Lo que se borraría al eliminar algo y lo que lo impide.
 *
 * @param deletable si se puede eliminar ya: sin {@code blockers}
 * @param blockers motivos por los que no se puede eliminar, para enseñarlos tal cual
 * @param warnings efectos que no impiden eliminar, pero conviene saber
 * @param liveWorkspaces worktrees que siguen en su runner y solo usa lo que se elimina; se pueden
 *     eliminar con {@code POST …/workspaces/cleanup}
 */
public record DeletionPreview(
    boolean deletable,
    List<String> blockers,
    List<String> warnings,
    int liveWorkspaces,
    Counts counts) {

  /**
   * Cuántas filas se borrarían.
   *
   * @param artifactBytes tamaño de los artefactos que se borran (los blobs compartidos con otros
   *     artefactos se conservan)
   */
  public record Counts(
      long repositories,
      long workItems,
      long runs,
      long agents,
      long artifacts,
      long artifactBytes,
      long events) {}
}
