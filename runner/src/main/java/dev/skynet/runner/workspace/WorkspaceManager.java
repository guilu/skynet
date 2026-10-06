package dev.skynet.runner.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Crea un {@code git worktree} por invocación en {@code <root>/<workflow-run>/<agent-run>/}, sobre
 * la rama {@code skynet/<work-item>/<agent-run>}. Los worktrees se conservan al terminar; su
 * limpieza llega con la política de retención (M6).
 */
public class WorkspaceManager {

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
    try {
      copyUncommitted(source, path);
    } catch (IOException e) {
      // Sin worktree a medias: se quita junto con su rama.
      git(source, List.of("worktree", "remove", "--force", path.toString()));
      git(source, List.of("branch", "-D", branch));
      throw e;
    }
    return new Workspace(path, branch, head);
  }

  /** Lleva a {@code target} los cambios sin confirmar de {@code source}. */
  private static void copyUncommitted(Path source, Path target)
      throws IOException, InterruptedException {
    byte[] diff = git(source, List.of("diff", "--binary", "HEAD"), new byte[0]);
    if (diff.length > 0) {
      git(target, List.of("apply", "--binary", "-"), diff);
    }
    for (String file :
        git(source, List.of("ls-files", "--others", "--exclude-standard", "-z")).split("\0")) {
      if (!file.isEmpty()) {
        Path copy = target.resolve(file);
        Files.createDirectories(copy.getParent());
        // Un enlace simbólico se copia como enlace: seguirlo podría copiar ficheros de fuera.
        Files.copy(
            source.resolve(file),
            copy,
            StandardCopyOption.COPY_ATTRIBUTES,
            LinkOption.NOFOLLOW_LINKS);
      }
    }
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
    return Git.text(directory, args);
  }

  private static byte[] git(Path directory, List<String> args, byte[] input)
      throws IOException, InterruptedException {
    return Git.run(directory, args, input, Map.of());
  }
}
