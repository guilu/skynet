package dev.skynet.protocol.runner;

import java.util.Objects;

/**
 * Sesión y worktree de los que parte una orden {@link RunnerCommandType#RESUME}.
 *
 * <p>Claude Code guarda la sesión en la máquina del runner, ligada al directorio de trabajo, así
 * que la orden va siempre al runner que ejecutó la invocación anterior.
 *
 * @param sessionId sesión que se reanuda ({@code --resume})
 * @param fork si es {@code true}, la sesión se bifurca ({@code --fork-session}) con el {@code
 *     sessionId} de la orden, en un worktree nuevo que parte de la rama de {@code workspacePath}
 * @param workspacePath worktree de la invocación anterior en la máquina del runner
 * @param workspaceBranch rama de ese worktree
 */
public record ResumeFrom(
    String sessionId, boolean fork, String workspacePath, String workspaceBranch) {

  public ResumeFrom {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(workspacePath, "workspacePath");
    Objects.requireNonNull(workspaceBranch, "workspaceBranch");
  }
}
