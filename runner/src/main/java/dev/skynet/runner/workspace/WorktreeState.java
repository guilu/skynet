package dev.skynet.runner.workspace;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Cuánto ha cambiado un worktree respecto a un commit base, para las comprobaciones de una fase:
 * commits nuevos ({@code base..HEAD}), ficheros cambiados (confirmados o no, y los nuevos no
 * ignorados) y cambios sin confirmar.
 *
 * @param base el commit con el que se compara
 * @param newCommits commits de {@code base..HEAD}
 * @param changedFiles rutas cambiadas respecto a {@code base}, relativas al worktree
 * @param uncommitted entradas de {@code git status --porcelain}
 */
public record WorktreeState(
    String base, int newCommits, List<String> changedFiles, int uncommitted) {

  /**
   * El estado de {@code worktree} respecto a {@code baseCommit} ({@code null}: su {@code HEAD}).
   */
  public static WorktreeState of(Path worktree, String baseCommit)
      throws IOException, InterruptedException {
    String base =
        baseCommit == null || baseCommit.isBlank()
            ? Git.text(worktree, List.of("rev-parse", "HEAD")).trim()
            : baseCommit;
    int commits =
        Integer.parseInt(
            Git.text(worktree, List.of("rev-list", "--count", base + "..HEAD")).trim());
    Set<String> files = new LinkedHashSet<>();
    files.addAll(lines(Git.text(worktree, List.of("diff", "--name-only", "--no-renames", base))));
    files.addAll(lines(Git.text(worktree, List.of("ls-files", "--others", "--exclude-standard"))));
    int uncommitted = lines(Git.text(worktree, List.of("status", "--porcelain"))).size();
    return new WorktreeState(base, commits, List.copyOf(files), uncommitted);
  }

  private static List<String> lines(String output) {
    List<String> lines = new ArrayList<>();
    output.lines().filter(l -> !l.isBlank()).forEach(lines::add);
    return lines;
  }
}
