package dev.skynet.runner.workspace;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lo que ha cambiado en un worktree respecto a un commit base: rama, commits nuevos ({@code
 * base..HEAD}), archivos modificados con sus líneas y el diff completo, incluidos los cambios sin
 * confirmar y los archivos nuevos no ignorados. Usa un índice temporal ({@code GIT_INDEX_FILE})
 * para no tocar el del worktree.
 */
public final class GitIndexer {

  /** Commits que se listan como mucho. */
  static final int MAX_COMMITS = 500;

  private static final String DIFF_HEADER = "diff --git ";

  private GitIndexer() {}

  /**
   * Cambios del worktree.
   *
   * @param changes resumen para el artefacto {@code GIT_CHANGES}: rama, commits y archivos (con la
   *     posición de su diff dentro de {@code diffFile})
   * @param summary datos para listar el artefacto sin descargarlo
   */
  public record Changes(Map<String, Object> changes, Map<String, Object> summary) {}

  /** Indexa {@code worktree} contra {@code baseCommit} y escribe el diff en {@code diffFile}. */
  public static Changes index(Path worktree, String baseCommit, Path diffFile)
      throws IOException, InterruptedException {
    String branch = Git.text(worktree, List.of("rev-parse", "--abbrev-ref", "HEAD")).trim();
    String head = Git.text(worktree, List.of("rev-parse", "HEAD")).trim();
    String base = baseCommit == null || baseCommit.isBlank() ? head : baseCommit;
    List<Map<String, Object>> commits = commits(worktree, base);
    int uncommitted =
        (int)
            Git.text(worktree, List.of("status", "--porcelain"))
                .lines()
                .filter(l -> !l.isBlank())
                .count();

    Path index = Files.createTempFile("skynet-index", null);
    try {
      Map<String, String> env = Map.of("GIT_INDEX_FILE", index.toString());
      Git.run(worktree, List.of("read-tree", "HEAD"), new byte[0], env);
      Git.run(worktree, List.of("add", "-A"), new byte[0], env);
      List<String> diff = List.of("diff", "--cached", "--no-color", "--no-ext-diff", "-M");
      List<FileChange> files =
          nameStatus(Git.run(worktree, with(diff, "-z", "--name-status", base), new byte[0], env));
      addNumstat(files, Git.run(worktree, with(diff, "-z", "--numstat", base), new byte[0], env));
      Git.run(worktree, with(diff, "--output=" + diffFile, base), new byte[0], env);
      addOffsets(files, diffFile);

      long insertions = files.stream().mapToLong(f -> f.insertions).sum();
      long deletions = files.stream().mapToLong(f -> f.deletions).sum();
      Map<String, Object> changes = new LinkedHashMap<>();
      changes.put("branch", branch);
      changes.put("baseCommit", base);
      changes.put("headCommit", head);
      changes.put("commits", commits);
      changes.put("files", files.stream().map(FileChange::toMap).toList());
      changes.put("uncommittedFiles", uncommitted);
      Map<String, Object> summary = new LinkedHashMap<>();
      summary.put("branch", branch);
      summary.put("baseCommit", base);
      summary.put("headCommit", head);
      summary.put("commits", commits.size());
      summary.put("files", files.size());
      summary.put("insertions", insertions);
      summary.put("deletions", deletions);
      summary.put("uncommittedFiles", uncommitted);
      return new Changes(changes, summary);
    } finally {
      Files.deleteIfExists(index);
    }
  }

  private static List<Map<String, Object>> commits(Path worktree, String base)
      throws IOException, InterruptedException {
    String log =
        Git.text(
            worktree,
            List.of(
                "log",
                "--max-count=" + MAX_COMMITS,
                "--format=%H%x1f%an%x1f%ae%x1f%aI%x1f%s%x1e",
                base + "..HEAD"));
    List<Map<String, Object>> commits = new ArrayList<>();
    for (String record : log.split("\u001e")) {
      String[] fields = record.strip().split("\u001f", -1);
      if (fields.length < 5) {
        continue;
      }
      Map<String, Object> commit = new LinkedHashMap<>();
      commit.put("sha", fields[0]);
      commit.put("author", fields[1]);
      commit.put("email", fields[2]);
      commit.put("date", fields[3]);
      commit.put("subject", fields[4]);
      commits.add(commit);
    }
    return commits;
  }

  /**
   * {@code --name-status -z}: {@code M\0ruta\0} o, en un renombrado, {@code R100\0vieja\0nueva\0}.
   */
  static List<FileChange> nameStatus(byte[] output) {
    String[] parts = new String(output, StandardCharsets.UTF_8).split("\0");
    List<FileChange> files = new ArrayList<>();
    for (int i = 0; i + 1 < parts.length; ) {
      String status = parts[i++];
      FileChange file = new FileChange();
      file.status = status.substring(0, 1);
      if (file.status.equals("R") || file.status.equals("C")) {
        file.oldPath = parts[i++];
      }
      if (i < parts.length) {
        file.path = parts[i++];
        files.add(file);
      }
    }
    return files;
  }

  /**
   * {@code --numstat -z}: {@code añadidas\tborradas\truta\0} o, en un renombrado, {@code
   * añadidas\tborradas\t\0vieja\0nueva\0}. Un binario lleva {@code -} en vez de números. Va en el
   * mismo orden que {@code --name-status}.
   */
  static void addNumstat(List<FileChange> files, byte[] output) {
    String[] parts = new String(output, StandardCharsets.UTF_8).split("\0");
    int file = 0;
    for (int i = 0; i < parts.length && file < files.size(); i++) {
      String[] fields = parts[i].split("\t", 3);
      if (fields.length < 3) {
        continue;
      }
      if (fields[2].isEmpty()) {
        i += 2; // Renombrado: siguen la ruta vieja y la nueva.
      }
      FileChange change = files.get(file++);
      change.binary = fields[0].equals("-");
      change.insertions = change.binary ? 0 : Long.parseLong(fields[0]);
      change.deletions = change.binary ? 0 : Long.parseLong(fields[1]);
    }
  }

  /** Posición del diff de cada archivo dentro del diff completo, por orden de aparición. */
  static void addOffsets(List<FileChange> files, Path diffFile) throws IOException {
    List<Long> starts = new ArrayList<>();
    try (InputStream in = new BufferedInputStream(Files.newInputStream(diffFile))) {
      byte[] header = DIFF_HEADER.getBytes(StandardCharsets.US_ASCII);
      long position = 0;
      int matched = 0;
      boolean lineStart = true;
      int b;
      while ((b = in.read()) != -1) {
        if (lineStart || matched > 0) {
          if (b == header[matched]) {
            matched++;
            if (matched == header.length) {
              starts.add(position - header.length + 1);
              matched = 0;
            }
          } else {
            matched = 0;
          }
        }
        lineStart = b == '\n';
        position++;
      }
      starts.add(position);
    }
    // Si no cuadran (no debería), mejor sin posiciones que con posiciones equivocadas.
    if (starts.size() - 1 != files.size()) {
      return;
    }
    for (int i = 0; i < files.size(); i++) {
      files.get(i).diffOffset = starts.get(i);
      files.get(i).diffLength = starts.get(i + 1) - starts.get(i);
    }
  }

  private static List<String> with(List<String> base, String... more) {
    List<String> args = new ArrayList<>(base);
    args.addAll(List.of(more));
    return args;
  }

  /** Un archivo cambiado. */
  static final class FileChange {
    String path;
    String oldPath;
    String status;
    long insertions;
    long deletions;
    boolean binary;
    Long diffOffset;
    Long diffLength;

    Map<String, Object> toMap() {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("path", path);
      if (oldPath != null) {
        map.put("oldPath", oldPath);
      }
      map.put("status", status);
      map.put("insertions", insertions);
      map.put("deletions", deletions);
      map.put("binary", binary);
      if (diffOffset != null) {
        map.put("diffOffset", diffOffset);
        map.put("diffLength", diffLength);
      }
      return map;
    }
  }
}
