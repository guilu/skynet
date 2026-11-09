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

  /**
   * Las líneas que añade y cuántas quita el cambio de {@code worktree} respecto a {@code
   * baseCommit} (AU-A), por fichero: lo confirmado y lo que no, más los nuevos sin seguimiento. Los
   * binarios no salen. De cada fichero se leen como mucho {@code maxBytes} del contenido añadido.
   *
   * @param added las líneas añadidas, sin el {@code +} (las que caben en {@code maxBytes})
   * @param addedCount cuántas líneas añade, todas
   * @param removed cuántas líneas quita
   */
  public record Lines(List<String> added, int addedCount, int removed) {}

  public static Map<String, Lines> lines(Path worktree, String baseCommit, int maxBytes)
      throws IOException, InterruptedException {
    String base = Git.commit(baseCommit);
    Map<String, Lines> result = new java.util.LinkedHashMap<>();
    String diff =
        Git.text(
            worktree,
            List.of(
                "-c",
                "core.quotePath=false",
                "diff",
                "--no-color",
                "--no-ext-diff",
                "--no-renames",
                "-U0",
                base,
                "--"));
    String path = null;
    List<String> added = new ArrayList<>();
    int addedCount = 0;
    int removed = 0;
    int bytes = 0;
    // Las cabeceras (---, +++) solo van entre «diff --git» y el primer «@@»: una línea quitada que
    // empieza por «-- » (un comentario SQL) también sale como «--- ».
    boolean header = false;
    for (String line : diff.split("\n", -1)) {
      if (line.startsWith("@@")) {
        header = false;
      } else if (line.startsWith("diff --git ")) {
        header = true;
        if (path != null) {
          result.put(path, new Lines(List.copyOf(added), addedCount, removed));
        }
        path = null;
        added = new ArrayList<>();
        addedCount = 0;
        removed = 0;
        bytes = 0;
      } else if (header && line.startsWith("+++ ")) {
        String target = line.substring(4);
        if (!target.equals("/dev/null")) {
          path = target.startsWith("b/") ? target.substring(2) : target;
        }
      } else if (header && line.startsWith("--- ")) {
        String source = line.substring(4);
        if (path == null && !source.equals("/dev/null")) {
          path = source.startsWith("a/") ? source.substring(2) : source;
        }
      } else if (header) {
        continue;
      } else if (line.startsWith("+")) {
        addedCount++;
        if (bytes < maxBytes) {
          added.add(line.substring(1));
          bytes += line.length();
        }
      } else if (line.startsWith("-")) {
        removed++;
      }
    }
    if (path != null) {
      result.put(path, new Lines(List.copyOf(added), addedCount, removed));
    }
    for (String untracked :
        Git.text(worktree, List.of("ls-files", "-z", "--others", "--exclude-standard"))
            .split("\0")) {
      if (untracked.isEmpty() || result.containsKey(untracked)) {
        continue;
      }
      Path file = worktree.resolve(untracked);
      if (!java.nio.file.Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
        continue;
      }
      byte[] content;
      try (var in = java.nio.file.Files.newInputStream(file)) {
        content = in.readNBytes(maxBytes);
      }
      if (binary(content)) {
        continue;
      }
      List<String> lines =
          List.of(new String(content, StandardCharsets.UTF_8).split("\n", -1)).stream()
              .filter(l -> !l.isEmpty())
              .toList();
      result.put(untracked, new Lines(lines, lines.size(), 0));
    }
    return result;
  }

  private static boolean binary(byte[] content) {
    for (byte b : content) {
      if (b == 0) {
        return true;
      }
    }
    return false;
  }
}
