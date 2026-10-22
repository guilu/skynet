package dev.skynet.runner.verify;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Los ficheros que declara una fase en {@code artifacts} (#145): los que se suben como artefactos
 * {@code DECLARED_FILE} para abrirlos y descargarlos aunque se limpie el worktree. Solo entran los
 * que casan con un glob declarado, nunca el resto del worktree.
 *
 * <p>Un fichero cuenta si es regular, o un enlace simbólico cuya ruta real es un fichero regular
 * dentro del worktree. Los enlaces que salen del worktree, las FIFOs, los sockets y los
 * dispositivos se omiten con su motivo y no cuentan como existentes. Lo que pasa de los {@link
 * Limits} se omite entero: un Markdown o un PDF a medias engaña.
 */
public final class DeclaredFiles {

  /** Ficheros que se recorren como mucho al buscar los artefactos. */
  static final int MAX_WALK = 100_000;

  private static final long MB = 1024L * 1024;

  /** Tipos MIME por extensión; lo demás es {@code application/octet-stream}. */
  private static final Map<String, String> MEDIA_TYPES =
      Map.ofEntries(
          Map.entry("md", "text/markdown"),
          Map.entry("markdown", "text/markdown"),
          Map.entry("txt", "text/plain"),
          Map.entry("log", "text/plain"),
          Map.entry("csv", "text/csv"),
          Map.entry("json", "application/json"),
          Map.entry("yaml", "application/yaml"),
          Map.entry("yml", "application/yaml"),
          Map.entry("xml", "application/xml"),
          Map.entry("html", "text/plain"),
          Map.entry("png", "image/png"),
          Map.entry("jpg", "image/jpeg"),
          Map.entry("jpeg", "image/jpeg"),
          Map.entry("gif", "image/gif"),
          Map.entry("webp", "image/webp"),
          Map.entry("svg", "image/svg+xml"),
          Map.entry("pdf", "application/pdf"));

  private DeclaredFiles() {}

  /**
   * Límites de lo que se sube de una fase.
   *
   * @param maxFileBytes tamaño máximo de un fichero
   * @param maxStageBytes lo más que se sube entre todos los ficheros de la fase
   * @param maxFiles ficheros que se suben como mucho
   */
  public record Limits(long maxFileBytes, long maxStageBytes, int maxFiles) {

    public static final Limits DEFAULT = new Limits(10 * MB, 50 * MB, 50);

    public Limits {
      if (maxFileBytes <= 0 || maxStageBytes <= 0 || maxFiles <= 0) {
        throw new IllegalArgumentException(
            "Los límites de los artefactos tienen que ser positivos");
      }
    }
  }

  /**
   * Un fichero que se sube.
   *
   * @param path ruta relativa al worktree, con {@code /}
   * @param glob la expresión declarada que lo encontró
   * @param file lo que se lee: el propio fichero o el destino real del enlace
   */
  public record Match(String path, String glob, Path file, long size, String mediaType) {}

  /** Un fichero que casa con lo declarado pero no se sube. */
  public record Omitted(String path, String reason) {}

  /**
   * Lo que se sube y lo que no.
   *
   * @param missing globs que no encontraron ningún fichero válido
   */
  public record Selection(List<Match> files, List<Omitted> omitted, List<String> missing) {}

  /** Los ficheros de {@code worktree} que casan con {@code globs}, dentro de {@code limits}. */
  public static Selection select(Path worktree, List<String> globs, Limits limits)
      throws IOException {
    Path root = worktree.toRealPath();
    List<PathMatcher> matchers = globs.stream().map(DeclaredFiles::matcher).toList();
    List<Match> candidates = new ArrayList<>();
    List<Omitted> omitted = new ArrayList<>();
    for (Path entry : entries(root)) {
      String path = root.relativize(entry).toString().replace('\\', '/');
      if (path.isEmpty() || path.equals(".git") || path.startsWith(".git/")) {
        continue;
      }
      int index = firstMatch(matchers, path);
      if (index < 0) {
        continue;
      }
      BasicFileAttributes attributes =
          Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (attributes.isDirectory()) {
        continue;
      }
      Optional<Path> file = readable(root, entry, attributes);
      if (file.isEmpty()) {
        omitted.add(
            new Omitted(
                path,
                attributes.isSymbolicLink()
                    ? "Es un enlace simbólico que sale del repositorio"
                    : "No es un fichero regular"));
        continue;
      }
      long size =
          Files.readAttributes(file.get(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
              .size();
      candidates.add(new Match(path, globs.get(index), file.get(), size, mediaType(path)));
    }

    List<Match> files = new ArrayList<>();
    long total = 0;
    candidates.sort(Comparator.comparing(Match::path));
    for (Match match : candidates) {
      if (match.size() > limits.maxFileBytes()) {
        omitted.add(
            new Omitted(
                match.path(), "Supera el máximo de " + mb(limits.maxFileBytes()) + " por fichero"));
      } else if (files.size() >= limits.maxFiles()) {
        omitted.add(
            new Omitted(
                match.path(), "Supera el máximo de " + limits.maxFiles() + " ficheros por fase"));
      } else if (total + match.size() > limits.maxStageBytes()) {
        omitted.add(
            new Omitted(
                match.path(), "Supera el máximo de " + mb(limits.maxStageBytes()) + " por fase"));
      } else {
        files.add(match);
        total += match.size();
      }
    }
    omitted.sort(Comparator.comparing(Omitted::path));

    List<String> missing = new ArrayList<>();
    for (int i = 0; i < globs.size(); i++) {
      PathMatcher matcher = matchers.get(i);
      if (candidates.stream().noneMatch(m -> matcher.matches(Path.of(m.path())))) {
        missing.add(globs.get(i));
      }
    }
    return new Selection(List.copyOf(files), List.copyOf(omitted), List.copyOf(missing));
  }

  /** Tipo MIME por la extensión de {@code path}. */
  static String mediaType(String path) {
    String name = path.substring(path.lastIndexOf('/') + 1);
    int dot = name.lastIndexOf('.');
    if (dot <= 0) {
      return "application/octet-stream";
    }
    return MEDIA_TYPES.getOrDefault(
        name.substring(dot + 1).toLowerCase(Locale.ROOT), "application/octet-stream");
  }

  /** Lo que hay en el worktree, sin entrar en los enlaces a directorios. */
  private static List<Path> entries(Path root) throws IOException {
    try (Stream<Path> walk = Files.walk(root)) {
      return walk.limit(MAX_WALK).toList();
    }
  }

  /**
   * Lo que se lee de {@code entry}: el propio fichero si es regular, el destino real si es un
   * enlace a un fichero regular dentro del worktree, o nada.
   */
  private static Optional<Path> readable(Path root, Path entry, BasicFileAttributes attributes)
      throws IOException {
    if (attributes.isRegularFile()) {
      return Optional.of(entry);
    }
    if (!attributes.isSymbolicLink()) {
      return Optional.empty();
    }
    Path real;
    try {
      real = entry.toRealPath();
    } catch (IOException e) {
      // Enlace roto.
      return Optional.empty();
    }
    if (!real.startsWith(root)
        || real.startsWith(root.resolve(".git"))
        || !Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
      return Optional.empty();
    }
    return Optional.of(real);
  }

  private static int firstMatch(List<PathMatcher> matchers, String path) {
    Path p = Path.of(path);
    for (int i = 0; i < matchers.size(); i++) {
      if (matchers.get(i).matches(p)) {
        return i;
      }
    }
    return -1;
  }

  private static PathMatcher matcher(String glob) {
    return FileSystems.getDefault().getPathMatcher("glob:" + glob);
  }

  private static String mb(long bytes) {
    return bytes % MB == 0 ? bytes / MB + " MB" : bytes + " bytes";
  }
}
