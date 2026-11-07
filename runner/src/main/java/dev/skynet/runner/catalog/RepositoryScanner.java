package dev.skynet.runner.catalog;

import dev.skynet.protocol.runner.DiscoveredRepository;
import dev.skynet.protocol.runner.RepositoryCatalog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Busca repositorios git en las carpetas raíz: la propia raíz y hasta {@link #MAX_DEPTH} niveles
 * por debajo. No entra en carpetas ocultas, de dependencias o de compilación, ni dentro de un
 * repositorio, ni sigue enlaces simbólicos. Un repositorio es una carpeta con un directorio {@code
 * .git}: los worktrees (y por tanto los del propio runner) y los submódulos tienen un fichero.
 */
public final class RepositoryScanner {

  static final int MAX_DEPTH = 2;
  static final int MAX_REPOSITORIES = 200;

  private static final Logger LOG = Logger.getLogger(RepositoryScanner.class.getName());
  private static final Set<String> SKIPPED =
      Set.of("node_modules", "build", "target", "dist", "out", "vendor", "venv", "__pycache__");

  private final List<Path> roots;
  private final Path excluded;
  private final int maxRepositories;

  /**
   * @param roots carpetas raíz
   * @param excluded carpeta en la que nunca se busca (el directorio de datos del runner), o {@code
   *     null}
   */
  public RepositoryScanner(List<Path> roots, Path excluded) {
    this(roots, excluded, MAX_REPOSITORIES);
  }

  RepositoryScanner(List<Path> roots, Path excluded, int maxRepositories) {
    this.roots = List.copyOf(roots);
    this.excluded = excluded == null ? null : excluded.toAbsolutePath().normalize();
    this.maxRepositories = maxRepositories;
  }

  public List<Path> roots() {
    return roots;
  }

  public RepositoryCatalog scan() throws InterruptedException {
    List<Path> found = new ArrayList<>();
    boolean truncated = false;
    for (Path root : roots) {
      Path real = realPath(root);
      if (real == null) {
        LOG.fine(() -> "La carpeta raíz " + root + " no existe");
        continue;
      }
      truncated |= walk(real, 0, found);
    }
    List<DiscoveredRepository> repositories = new ArrayList<>();
    for (Path path :
        found.stream().distinct().sorted(Comparator.comparing(Path::toString)).toList()) {
      repositories.add(inspect(path));
    }
    return new RepositoryCatalog(
        Instant.now(), roots.stream().map(Path::toString).toList(), repositories, truncated);
  }

  /** Añade los repositorios de {@code dir}; {@code true} si paró al llegar al máximo. */
  private boolean walk(Path dir, int depth, List<Path> found) {
    if (excluded != null && dir.startsWith(excluded)) {
      return false;
    }
    if (Files.isDirectory(dir.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
      if (found.size() >= maxRepositories) {
        return true;
      }
      if (!found.contains(dir)) {
        found.add(dir);
      }
      return false;
    }
    if (depth >= MAX_DEPTH) {
      return false;
    }
    List<Path> children;
    try (Stream<Path> entries = Files.list(dir)) {
      children =
          entries
              .filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
              .filter(p -> !p.getFileName().toString().startsWith("."))
              .filter(p -> !SKIPPED.contains(p.getFileName().toString()))
              .sorted()
              .toList();
    } catch (IOException e) {
      return false;
    }
    for (Path child : children) {
      if (walk(child, depth + 1, found)) {
        return true;
      }
    }
    return false;
  }

  /** Lo que se puede deducir de un repositorio. */
  static DiscoveredRepository inspect(Path path) throws InterruptedException {
    GitProbe git = new GitProbe(path);
    String remote = remote(git);
    String remoteUrl =
        remote == null
            ? null
            : RemoteUrls.withoutCredentials(git.text("remote", "get-url", remote));

    String branch = null;
    String branchSource = null;
    if (remote != null) {
      String head =
          git.text("symbolic-ref", "--quiet", "--short", "refs/remotes/" + remote + "/HEAD");
      if (head != null && head.startsWith(remote + "/") && head.length() > remote.length() + 1) {
        branch = head.substring(remote.length() + 1);
        branchSource = "origin/HEAD";
      }
    }
    if (branch == null) {
      String current = git.text("symbolic-ref", "--quiet", "--short", "HEAD");
      if (current != null && !current.isBlank()) {
        branch = current;
        branchSource = "current";
      } else {
        branch = "main";
        branchSource = "default";
      }
    }

    String commit = null;
    String subject = null;
    Instant committedAt = null;
    String log = git.text("log", "-1", "--format=%H%x1f%s%x1f%cI");
    if (log != null) {
      String[] parts = log.split("\u001f", 3);
      if (parts.length == 3) {
        commit = parts[0];
        subject = parts[1].length() > 200 ? parts[1].substring(0, 199) + "…" : parts[1];
        committedAt = instant(parts[2]);
      }
    }
    String status = git.text("status", "--porcelain");
    boolean dirty = status != null && !status.isBlank();

    Manifests.Facts facts = Manifests.read(path);
    String folder = path.getFileName() == null ? path.toString() : path.getFileName().toString();
    List<String> protectedPaths =
        Files.isDirectory(path.resolve(".github/workflows"), LinkOption.NOFOLLOW_LINKS)
            ? List.of(".github/workflows/**")
            : List.of();
    boolean instructions =
        Manifests.isFile(path.resolve("CLAUDE.md")) || Manifests.isFile(path.resolve("AGENTS.md"));
    return new DiscoveredRepository(
        path.toString(),
        folder,
        facts.name() == null ? folder : facts.name(),
        facts.name() == null ? "directory" : facts.nameSource(),
        facts.description(),
        facts.descriptionSource(),
        remoteUrl,
        branch,
        branchSource,
        facts.tool(),
        facts.validationCommand(),
        facts.testReportPaths(),
        protectedPaths,
        commit,
        subject,
        committedAt,
        dirty,
        instructions);
  }

  /** {@code origin} si existe; si no, el primer remoto; {@code null} sin remotos. */
  private static String remote(GitProbe git) throws InterruptedException {
    String remotes = git.text("remote");
    if (remotes == null || remotes.isBlank()) {
      return null;
    }
    List<String> names = remotes.lines().map(String::strip).filter(s -> !s.isEmpty()).toList();
    return names.contains("origin") ? "origin" : names.getFirst();
  }

  private static Instant instant(String iso) {
    try {
      return OffsetDateTime.parse(iso.strip()).toInstant();
    } catch (DateTimeParseException e) {
      LOG.log(Level.FINE, "Fecha de commit no reconocida: " + iso, e);
      return null;
    }
  }

  private static Path realPath(Path root) {
    try {
      return root.toRealPath();
    } catch (IOException e) {
      return null;
    }
  }
}
