package dev.skynet.runner.provider.claude;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Sesiones que Claude Code guarda en la máquina del runner: {@code
 * <config>/projects/<cwd>/<sesión>.jsonl}, donde {@code <config>} es {@code CLAUDE_CONFIG_DIR} o
 * {@code ~/.claude} y {@code <cwd>} es el directorio de trabajo con todo lo que no es letra o
 * dígito cambiado por {@code -}. {@code --resume} solo busca en el directorio del cwd actual.
 */
public final class ClaudeSessions {

  private final Path configDir;

  ClaudeSessions(Path configDir) {
    this.configDir = configDir;
  }

  /** Directorio de configuración de Claude Code según el entorno del runner. */
  public static Optional<ClaudeSessions> of(Map<String, String> runnerEnv) {
    String config = runnerEnv.get("CLAUDE_CONFIG_DIR");
    if (config != null && !config.isBlank()) {
      return Optional.of(new ClaudeSessions(Path.of(config)));
    }
    String home = runnerEnv.get("HOME");
    if (home != null && !home.isBlank()) {
      return Optional.of(new ClaudeSessions(Path.of(home, ".claude")));
    }
    return Optional.empty();
  }

  /**
   * Copia la sesión del worktree {@code from} al worktree {@code to}, para que un fork lanzado en
   * {@code to} la encuentre con {@code --resume}. Copia también el directorio auxiliar de la sesión
   * ({@code <sesión>/}), si existe.
   *
   * @return {@code false} si la sesión no está en {@code from}; el CLI lo dirá al reanudar
   */
  public boolean copy(String sessionId, Path from, Path to) throws IOException {
    Path source = projectDir(from);
    Path transcript = source.resolve(sessionId + ".jsonl");
    if (!Files.isRegularFile(transcript)) {
      return false;
    }
    Path target = projectDir(to);
    Files.createDirectories(target);
    Files.copy(
        transcript, target.resolve(transcript.getFileName()), StandardCopyOption.REPLACE_EXISTING);
    Path extra = source.resolve(sessionId);
    if (Files.isDirectory(extra)) {
      try (Stream<Path> files = Files.walk(extra)) {
        for (Path file : (Iterable<Path>) files::iterator) {
          Path copy = target.resolve(source.relativize(file).toString());
          if (Files.isDirectory(file)) {
            Files.createDirectories(copy);
          } else {
            Files.copy(file, copy, StandardCopyOption.REPLACE_EXISTING);
          }
        }
      }
    }
    return true;
  }

  Path projectDir(Path cwd) throws IOException {
    // El CLI usa el cwd real del proceso, con los enlaces simbólicos resueltos.
    String real = cwd.toRealPath().toString();
    return configDir.resolve("projects").resolve(real.replaceAll("[^A-Za-z0-9]", "-"));
  }
}
