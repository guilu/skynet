package dev.skynet.runner.catalog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Preguntas de solo lectura a git sobre un repositorio. Nunca pide credenciales ni toma bloqueos:
 * el repositorio es del usuario y puede estar usándolo a la vez.
 */
final class GitProbe {

  private static final long TIMEOUT_SECONDS = 20;

  private final Path directory;

  GitProbe(Path directory) {
    this.directory = directory;
  }

  /** La salida sin el salto de línea final, o {@code null} si git falla o tarda demasiado. */
  String text(String... args) throws InterruptedException {
    List<String> command =
        new ArrayList<>(List.of("git", "--no-optional-locks", "-C", directory.toString()));
    command.addAll(List.of(args));
    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(false);
    builder.environment().put("GIT_TERMINAL_PROMPT", "0");
    builder.redirectError(ProcessBuilder.Redirect.DISCARD);
    Process process;
    try {
      process = builder.start();
    } catch (IOException e) {
      return null;
    }
    try {
      process.getOutputStream().close();
      byte[] output = process.getInputStream().readNBytes(64 * 1024);
      if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        return null;
      }
      if (process.exitValue() != 0) {
        return null;
      }
      return new String(output, StandardCharsets.UTF_8).strip();
    } catch (IOException e) {
      process.destroyForcibly();
      return null;
    }
  }
}
