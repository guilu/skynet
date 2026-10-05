package dev.skynet.runner.workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Crea un {@code git worktree} por invocación en {@code <root>/<workflow-run>/<agent-run>/}, sobre
 * la rama {@code skynet/<work-item>/<agent-run>}. Los worktrees se conservan al terminar; su
 * limpieza llega con la política de retención (M6).
 */
public class WorkspaceManager {

  private static final long GIT_TIMEOUT_SECONDS = 120;

  private final Path root;

  public WorkspaceManager(Path root) {
    this.root = root.toAbsolutePath().normalize();
  }

  public Workspace create(
      Path repository, String baseBranch, UUID workflowRunId, String workItemKey, UUID agentRunId)
      throws IOException, InterruptedException {
    if (!Files.isDirectory(repository)) {
      throw new IOException("El repositorio no existe en este runner: " + repository);
    }
    Path path = root.resolve(workflowRunId.toString()).resolve(agentRunId.toString());
    if (Files.exists(path)) {
      throw new IOException("El worktree ya existe: " + path);
    }
    Files.createDirectories(path.getParent());
    String branch = branchName(workItemKey, agentRunId);
    List<String> add = new ArrayList<>(List.of("worktree", "add", "-b", branch, path.toString()));
    if (baseBranch != null && !baseBranch.isBlank()) {
      add.add(baseBranch);
    }
    git(repository, add);
    String baseCommit = git(path, List.of("rev-parse", "HEAD")).trim();
    return new Workspace(path, branch, baseCommit);
  }

  /**
   * Worktree de una invocación anterior, para reanudar en él su sesión. Tiene que estar bajo la
   * raíz de worktrees del runner: la ruta llega del control plane y no se aceptan otras.
   */
  public Workspace existing(Path path) throws IOException, InterruptedException {
    Path worktree = owned(path);
    String branch = git(worktree, List.of("rev-parse", "--abbrev-ref", "HEAD")).trim();
    String head = git(worktree, List.of("rev-parse", "HEAD")).trim();
    return new Workspace(worktree, branch, head);
  }

  /**
   * Worktree nuevo para un fork: parte del {@code HEAD} del worktree {@code parent} en una rama
   * propia e incluye sus cambios sin confirmar (los de ficheros versionados y los ficheros nuevos
   * no ignorados), así el fork ve el código tal como lo dejó la sesión que bifurca.
   */
  public Workspace fork(Path parent, UUID workflowRunId, String workItemKey, UUID agentRunId)
      throws IOException, InterruptedException {
    Path source = owned(parent);
    Path path = root.resolve(workflowRunId.toString()).resolve(agentRunId.toString());
    if (Files.exists(path)) {
      throw new IOException("El worktree ya existe: " + path);
    }
    Files.createDirectories(path.getParent());
    String branch = branchName(workItemKey, agentRunId);
    String head = git(source, List.of("rev-parse", "HEAD")).trim();
    git(source, List.of("worktree", "add", "-b", branch, path.toString(), head));
    String diff = git(source, List.of("diff", "--binary", "HEAD"));
    if (!diff.isEmpty()) {
      git(path, List.of("apply", "--binary", "-"), diff);
    }
    for (String file :
        git(source, List.of("ls-files", "--others", "--exclude-standard", "-z")).split("\0")) {
      if (!file.isEmpty()) {
        Path target = path.resolve(file);
        Files.createDirectories(target.getParent());
        Files.copy(source.resolve(file), target, StandardCopyOption.COPY_ATTRIBUTES);
      }
    }
    return new Workspace(path, branch, head);
  }

  private Path owned(Path path) throws IOException {
    Path normalized = path.toAbsolutePath().normalize();
    if (!normalized.startsWith(root) || !Files.isDirectory(normalized)) {
      throw new IOException("El worktree no existe en este runner: " + path);
    }
    return normalized;
  }

  static String branchName(String workItemKey, UUID agentRunId) {
    String item =
        workItemKey == null || workItemKey.isBlank()
            ? "adhoc"
            : workItemKey.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");
    return "skynet/" + item + "/" + agentRunId.toString().substring(0, 8);
  }

  private static String git(Path directory, List<String> args)
      throws IOException, InterruptedException {
    return git(directory, args, "");
  }

  private static String git(Path directory, List<String> args, String input)
      throws IOException, InterruptedException {
    List<String> command = new ArrayList<>(List.of("git", "-C", directory.toString()));
    command.addAll(args);
    // Sin combinar stderr: la salida de diff y ls-files se usa tal cual.
    Process process = new ProcessBuilder(command).start();
    StderrCollector stderr = new StderrCollector(process);
    try (var stdin = process.getOutputStream()) {
      stdin.write(input.getBytes(StandardCharsets.UTF_8));
    }
    byte[] output = process.getInputStream().readAllBytes();
    if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new IOException("git " + String.join(" ", args) + ": tiempo agotado");
    }
    String text = new String(output, StandardCharsets.UTF_8);
    if (process.exitValue() != 0) {
      throw new IOException("git " + String.join(" ", args) + " falló: " + stderr.text().trim());
    }
    return text;
  }

  /** Lee stderr en paralelo para que git no se bloquee si escribe mucho. */
  private static final class StderrCollector {
    private final Thread reader;
    private volatile String text = "";

    StderrCollector(Process process) {
      reader =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      text =
                          new String(
                              process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                    } catch (IOException e) {
                      // El proceso terminó.
                    }
                  });
    }

    String text() throws InterruptedException {
      reader.join();
      return text;
    }
  }
}
