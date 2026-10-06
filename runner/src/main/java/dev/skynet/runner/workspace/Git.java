package dev.skynet.runner.workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Ejecuta git en un directorio. */
final class Git {

  private static final long TIMEOUT_SECONDS = 120;

  private Git() {}

  static String text(Path directory, List<String> args) throws IOException, InterruptedException {
    return new String(run(directory, args, new byte[0], Map.of()), StandardCharsets.UTF_8);
  }

  /**
   * Entrada y salida en bytes: un diff lleva el contenido de los ficheros tal cual. {@code env}
   * añade variables (p. ej. {@code GIT_INDEX_FILE}).
   */
  static byte[] run(Path directory, List<String> args, byte[] input, Map<String, String> env)
      throws IOException, InterruptedException {
    List<String> command = new ArrayList<>(List.of("git", "-C", directory.toString()));
    command.addAll(args);
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.environment().putAll(env);
    // Sin combinar stderr: la salida de diff y ls-files se usa tal cual.
    Process process = builder.start();
    StderrCollector stderr = new StderrCollector(process);
    try (var stdin = process.getOutputStream()) {
      stdin.write(input);
    }
    byte[] output = process.getInputStream().readAllBytes();
    if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new IOException("git " + String.join(" ", args) + ": tiempo agotado");
    }
    if (process.exitValue() != 0) {
      throw new IOException("git " + String.join(" ", args) + " falló: " + stderr.text().trim());
    }
    return output;
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
