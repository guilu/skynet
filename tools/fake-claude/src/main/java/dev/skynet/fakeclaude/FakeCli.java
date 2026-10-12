package dev.skynet.fakeclaude;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

/** Lo que comparten fake-codex y fake-gemini: fixtures, salida, sesiones y salida estructurada. */
final class FakeCli {

  private FakeCli() {}

  /**
   * Sale con {@code code}; si es el de "matado", espera a SIGTERM salvo con {@code hangEnv=false}.
   */
  static void exit(int code, String hangEnv) throws InterruptedException {
    if (code == FakeClaude.EXIT_KILLED
        && !"false".equalsIgnoreCase(System.getenv().getOrDefault(hangEnv, "true"))) {
      Thread.currentThread().join(); // Simula una ejecución larga hasta recibir SIGTERM.
    }
    System.exit(code);
  }

  /** Un fichero con ese nombre, o el fixture {@code /<dir>/<fixture>.jsonl} empaquetado. */
  static List<String> loadFixture(String dir, String fixture) throws IOException {
    Path path = Path.of(fixture);
    if (Files.isRegularFile(path)) {
      return Files.readAllLines(path, StandardCharsets.UTF_8);
    }
    String resource = "/" + dir + "/" + fixture + ".jsonl";
    try (InputStream in = FakeCli.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalArgumentException("Fixture no encontrado: " + fixture);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
    }
  }

  /** Escribe una línea, la guarda en la sesión (si hay) y espera {@code delayMs}. */
  static void emit(PrintStream out, Path sessionFile, String json, long delayMs)
      throws IOException, InterruptedException {
    out.println(json);
    out.flush();
    if (sessionFile != null) {
      Files.createDirectories(sessionFile.getParent());
      Files.writeString(
          sessionFile,
          json + "\n",
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    }
    if (delayMs > 0) {
      Thread.sleep(delayMs);
    }
  }

  /** El JSON de la variable {@code name}, o el de un fichero con {@code @ruta} relativa a cwd. */
  static String structuredOutput(Map<String, String> env, String name, Path cwd)
      throws IOException {
    String value = env.get(name);
    if (value == null || !value.startsWith("@")) {
      return value;
    }
    Path file = cwd.resolve(value.substring(1));
    return Files.isRegularFile(file)
        ? Files.readString(file, StandardCharsets.UTF_8).strip()
        : null;
  }
}
