package dev.skynet.runner.workspace;

import java.nio.file.Path;

/**
 * Worktree de una invocación.
 *
 * @param snapshot instantánea de cómo estaba un worktree que ya existía al empezar, a la que vuelve
 *     un reintento (W6); {@code null} en uno nuevo
 */
public record Workspace(Path path, String branch, String baseCommit, String snapshot) {

  public Workspace(Path path, String branch, String baseCommit) {
    this(path, branch, baseCommit, null);
  }
}
