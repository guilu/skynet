package dev.skynet.runner.workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Qué ficheros cambiaron en un worktree respecto a un commit base y cómo (VA-A): añadidos,
 * modificados, borrados y renombrados, confirmados o no, más los nuevos sin seguimiento que git no
 * ignora. Sirve para los checks {@code tests-intact} y {@code protected}, que necesitan saber si un
 * fichero se borró o se movió y qué tenía antes.
 */
public final class FileChanges {

  /** Cómo cambió un fichero. */
  public enum Kind {
    ADDED,
    MODIFIED,
    DELETED,
    RENAMED
  }

  /**
   * Un fichero cambiado.
   *
   * @param kind cómo cambió
   * @param path su ruta ahora (la nueva, si se renombró; la que tenía, si se borró)
   * @param oldPath su ruta en la base; {@code null} si es nuevo
   */
  public record Change(Kind kind, String path, String oldPath) {}

  private FileChanges() {}

  /** Los cambios de {@code worktree} respecto a {@code baseCommit}, en el orden de git. */
  public static List<Change> of(Path worktree, String baseCommit)
      throws IOException, InterruptedException {
    String base = Git.commit(baseCommit);
    List<Change> changes = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    // -z: rutas tal cual, sin comillas, aunque lleven espacios o acentos.
    String[] fields =
        Git.text(worktree, List.of("diff", "--name-status", "-z", "-M", base, "--")).split("\0");
    for (int i = 0; i < fields.length; i++) {
      String status = fields[i];
      if (status.isEmpty()) {
        continue;
      }
      char kind = status.charAt(0);
      if (kind == 'R' || kind == 'C') {
        String from = fields[++i];
        String to = fields[++i];
        changes.add(
            kind == 'R' ? new Change(Kind.RENAMED, to, from) : new Change(Kind.ADDED, to, null));
        seen.add(to);
        continue;
      }
      String path = fields[++i];
      seen.add(path);
      changes.add(
          switch (kind) {
            case 'A' -> new Change(Kind.ADDED, path, null);
            case 'D' -> new Change(Kind.DELETED, path, path);
            default -> new Change(Kind.MODIFIED, path, path);
          });
    }
    for (String untracked :
        Git.text(worktree, List.of("ls-files", "-z", "--others", "--exclude-standard"))
            .split("\0")) {
      if (!untracked.isEmpty() && seen.add(untracked)) {
        changes.add(new Change(Kind.ADDED, untracked, null));
      }
    }
    return changes;
  }

  /** El contenido de {@code path} en {@code baseCommit}, o {@code null} si no existía. */
  public static String atBase(Path worktree, String baseCommit, String path)
      throws IOException, InterruptedException {
    String base = Git.commit(baseCommit);
    try {
      return new String(
          Git.run(worktree, List.of("show", base + ":" + path), new byte[0], Map.of()),
          StandardCharsets.UTF_8);
    } catch (IOException e) {
      return null;
    }
  }

  /** El commit base: {@code baseCommit} si es un hash, o el {@code HEAD} actual si no lo hay. */
  public static String base(Path worktree, String baseCommit)
      throws IOException, InterruptedException {
    return baseCommit == null || baseCommit.isBlank()
        ? Git.text(worktree, List.of("rev-parse", "HEAD")).trim()
        : Git.commit(baseCommit);
  }
}
