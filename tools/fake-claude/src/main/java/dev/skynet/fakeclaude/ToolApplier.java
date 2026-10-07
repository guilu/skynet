package dev.skynet.fakeclaude;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;

/**
 * Ejecuta de verdad las herramientas que edita un fixture ({@code Edit}, {@code Write} y {@code
 * Bash}) en el directorio de trabajo. Un fallo se avisa por stderr y no corta la reproducción.
 */
final class ToolApplier {

  private final Path cwd;
  private final PrintStream err;
  private String fixtureCwd = "";

  ToolApplier(Path cwd, PrintStream err) {
    this.cwd = cwd;
    this.err = err;
  }

  /** Directorio de trabajo con el que se grabó el fixture, para traducir sus rutas. */
  void fixtureCwd(String path) {
    this.fixtureCwd = path;
  }

  /** Aplica los bloques {@code tool_use} de un mensaje del asistente. */
  void apply(JsonNode content) throws InterruptedException {
    for (JsonNode block : content) {
      if (!"tool_use".equals(block.path("type").asString(""))) {
        continue;
      }
      JsonNode input = block.path("input");
      try {
        switch (block.path("name").asString("")) {
          case "Edit" -> edit(input);
          case "Write" -> Files.writeString(file(input), input.path("content").asString(""));
          case "Bash" -> bash(input.path("command").asString(""));
          default -> {
            // El resto de herramientas no cambia el worktree.
          }
        }
      } catch (IOException e) {
        err.println("fake-claude: no se pudo aplicar " + block.path("name").asString() + ": " + e);
      }
    }
  }

  private void edit(JsonNode input) throws IOException {
    Path file = file(input);
    String text = Files.readString(file, StandardCharsets.UTF_8);
    String old = input.path("old_string").asString("");
    String replacement = input.path("new_string").asString("");
    if (old.isEmpty() || !text.contains(old)) {
      throw new IOException("no se encuentra el texto a reemplazar en " + file);
    }
    String edited =
        input.path("replace_all").asBoolean(false)
            ? text.replace(old, replacement)
            : text.replaceFirst(
                java.util.regex.Pattern.quote(old),
                java.util.regex.Matcher.quoteReplacement(replacement));
    Files.writeString(file, edited, StandardCharsets.UTF_8);
  }

  private void bash(String command) throws IOException, InterruptedException {
    Process process =
        new ProcessBuilder("/bin/sh", "-c", command)
            .directory(cwd.toFile())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start();
    process.getOutputStream().close(); // Sin entrada: el comando no puede quedarse esperándola.
    if (!process.waitFor(60, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new IOException("el comando no terminó: " + command);
    }
  }

  /** Ruta del fixture traducida al directorio de trabajo; nunca fuera de él. */
  private Path file(JsonNode input) throws IOException {
    String path = input.path("file_path").asString("");
    if (!fixtureCwd.isEmpty() && path.startsWith(fixtureCwd + "/")) {
      path = path.substring(fixtureCwd.length() + 1);
    }
    Path resolved = cwd.resolve(path).normalize();
    if (!resolved.startsWith(cwd)) {
      throw new IOException("ruta fuera del directorio de trabajo: " + path);
    }
    return resolved;
  }
}
