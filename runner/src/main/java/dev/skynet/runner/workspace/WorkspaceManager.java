package dev.skynet.runner.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Crea un {@code git worktree} por invocación en {@code <root>/<workflow-run>/<agent-run>/}, sobre
 * la rama {@code skynet/<work-item>/<agent-run>}. Los worktrees se conservan al terminar, hasta que
 * el control plane pide eliminarlos ({@link #remove}).
 */
public class WorkspaceManager {

  private static final Logger LOG = Logger.getLogger(WorkspaceManager.class.getName());

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
   * Worktree de una invocación anterior, para reanudar en él su sesión o arrancar otra (una fase
   * que continúa el trabajo de la anterior). Tiene que estar bajo la raíz de worktrees del runner:
   * la ruta llega del control plane y no se aceptan otras.
   */
  public Workspace existing(Path path) throws IOException, InterruptedException {
    Path worktree = owned(path);
    String branch = git(worktree, List.of("rev-parse", "--abbrev-ref", "HEAD")).trim();
    String head = git(worktree, List.of("rev-parse", "HEAD")).trim();
    return new Workspace(worktree, branch, head);
  }

  /**
   * Worktree de una invocación anterior en el que arranca otra sesión (una fase que continúa el
   * trabajo de la anterior), con una instantánea de cómo estaba ({@link #snapshot}). Con {@code
   * restore}, antes vuelve a esa instantánea de un intento anterior ({@link #restore}).
   */
  public Workspace continued(Path path, String restore, UUID agentRunId)
      throws IOException, InterruptedException {
    Path worktree = owned(path);
    if (restore != null) {
      restore(worktree, restore);
    }
    Workspace workspace = existing(worktree);
    return new Workspace(
        workspace.path(),
        workspace.branch(),
        workspace.baseCommit(),
        snapshot(worktree, agentRunId));
  }

  /**
   * Guarda cómo está el worktree (lo confirmado, los cambios sin confirmar y los ficheros nuevos no
   * ignorados) en un commit sin rama, hijo de {@code HEAD}, y lo protege del recolector de git con
   * la referencia {@code refs/skynet/snapshots/<invocación>}. Devuelve su hash.
   */
  static String snapshot(Path worktree, UUID agentRunId) throws IOException, InterruptedException {
    // Índice temporal dentro del directorio git del worktree, como en GitIndexer.
    Path gitDir = Path.of(git(worktree, List.of("rev-parse", "--absolute-git-dir")).trim());
    Path index = Files.createTempFile(gitDir, "skynet-snapshot", null);
    try {
      Map<String, String> env =
          Map.of(
              "GIT_INDEX_FILE", index.toString(),
              "GIT_AUTHOR_NAME", "skynet",
              "GIT_AUTHOR_EMAIL", "skynet@localhost",
              "GIT_COMMITTER_NAME", "skynet",
              "GIT_COMMITTER_EMAIL", "skynet@localhost");
      Git.run(worktree, List.of("read-tree", "HEAD"), new byte[0], env);
      Git.run(worktree, List.of("add", "-A"), new byte[0], env);
      String tree = text(Git.run(worktree, List.of("write-tree"), new byte[0], env));
      String commit =
          text(
              Git.run(
                  worktree,
                  List.of(
                      "commit-tree",
                      tree,
                      "-p",
                      "HEAD",
                      "-m",
                      "skynet: estado al empezar " + agentRunId),
                  new byte[0],
                  env));
      git(worktree, List.of("update-ref", "refs/skynet/snapshots/" + agentRunId, commit));
      return commit;
    } finally {
      Files.deleteIfExists(index);
    }
  }

  /**
   * Devuelve el worktree a una instantánea de {@link #snapshot}: {@code HEAD} al commit del que
   * salió y los ficheros como estaban, con los cambios sin confirmar otra vez sin confirmar. Lo que
   * hubiera de más (ficheros nuevos no ignorados) se borra; los ignorados se quedan.
   */
  static void restore(Path worktree, String snapshot) throws IOException, InterruptedException {
    String parent = git(worktree, List.of("rev-parse", snapshot + "^")).trim();
    git(worktree, List.of("reset", "--hard", "-q", parent));
    git(worktree, List.of("clean", "-fdq"));
    git(worktree, List.of("read-tree", "-u", "--reset", snapshot));
    git(worktree, List.of("reset", "-q"));
  }

  private static String text(byte[] output) {
    return new String(output, java.nio.charset.StandardCharsets.UTF_8).trim();
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

  /**
   * Elimina un worktree con {@code git worktree remove --force}; la rama se conserva en el
   * repositorio. Un worktree que ya no existe no es un error: eliminarlo otra vez no hace nada.
   * Devuelve {@code false} en ese caso.
   */
  public boolean remove(Path path) throws IOException, InterruptedException {
    Path normalized = path.toAbsolutePath().normalize();
    if (!normalized.startsWith(root) || normalized.equals(root)) {
      throw new IOException("El worktree no es de este runner: " + path);
    }
    if (!Files.exists(normalized)) {
      return false;
    }
    if (Files.isRegularFile(normalized.resolve(".git"))) {
      try {
        git(normalized, List.of("worktree", "remove", "--force", normalized.toString()));
      } catch (IOException e) {
        // Sin el repositorio principal git no puede quitarlo; se borra el directorio igualmente
        // y el registro que quede en el repositorio lo limpia git worktree prune.
        LOG.warning(() -> "git worktree remove falló en " + normalized + ": " + e.getMessage());
      }
    }
    // Lo que quede (un worktree roto, sin .git) se borra a mano.
    deleteTree(normalized);
    return true;
  }

  private static void deleteTree(Path path) throws IOException {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    try (var files = Files.walk(path)) {
      for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(file);
      }
    }
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
