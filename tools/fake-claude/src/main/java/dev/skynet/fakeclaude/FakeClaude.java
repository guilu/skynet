package dev.skynet.fakeclaude;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Sustituto determinista de {@code claude -p --output-format stream-json}.
 *
 * <p>Reproduce un fixture NDJSON grabado de una sesión real, reescribiendo {@code session_id} y
 * {@code cwd} para que coincidan con la invocación. Se configura mediante variables de entorno:
 *
 * <ul>
 *   <li>{@code FAKE_CLAUDE_FIXTURE}: nombre de un fixture empaquetado (p. ej. {@code 02-tools}) o
 *       ruta a un fichero NDJSON. Por defecto {@code 01-simple-text}.
 *   <li>{@code FAKE_CLAUDE_RESUME_FIXTURE} y {@code FAKE_CLAUDE_FORK_FIXTURE}: el fixture con
 *       {@code --resume} y con {@code --resume --fork-session}. Por defecto {@code 03-resume} y
 *       {@code 04-fork}.
 *   <li>{@code FAKE_CLAUDE_DELAY_MS}: pausa entre líneas. Por defecto 10.
 *   <li>{@code FAKE_CLAUDE_HANG}: si el fixture no tiene línea {@code result} (sesión cortada), el
 *       proceso se queda esperando hasta que lo maten. {@code false} para terminar con código 143.
 *   <li>{@code CLAUDE_CONFIG_DIR}: si está definida, guarda cada sesión en {@code
 *       <dir>/projects/<cwd>/<sesión>.jsonl}, como el CLI real, y {@code --resume} falla si no
 *       encuentra la sesión en el directorio de trabajo actual. Sin ella no comprueba nada.
 * </ul>
 */
public final class FakeClaude {

  static final String DEFAULT_FIXTURE = "01-simple-text";
  static final int EXIT_KILLED = 143;

  private static final ObjectMapper JSON = JsonMapper.builder().build();

  private FakeClaude() {}

  public static void main(String[] args) throws Exception {
    int code = run(args, System.getenv(), Path.of("").toAbsolutePath(), System.out, System.err);
    if (code == EXIT_KILLED && hang(System.getenv())) {
      Thread.currentThread().join(); // Simula una ejecución larga hasta recibir SIGTERM.
    }
    System.exit(code);
  }

  static int run(String[] args, Map<String, String> env, Path cwd, PrintStream out, PrintStream err)
      throws IOException, InterruptedException {
    Invocation inv = Invocation.parse(args);
    if (!"stream-json".equals(inv.outputFormat())) {
      err.println("fake-claude: solo se admite --output-format stream-json");
      return 2;
    }
    String sessionId = inv.effectiveSessionId();
    Path sessionFile = sessionFile(env, cwd, sessionId);
    if (inv.resume() != null && sessionFile != null) {
      Path resumed = sessionFile(env, cwd, inv.resume());
      if (!Files.isRegularFile(resumed)) {
        err.println("No conversation found with session ID: " + inv.resume());
        return 1;
      }
      if (inv.forkSession()) {
        Files.createDirectories(sessionFile.getParent());
        Files.copy(resumed, sessionFile, StandardCopyOption.REPLACE_EXISTING);
      }
    }
    long delayMs = Long.parseLong(env.getOrDefault("FAKE_CLAUDE_DELAY_MS", "10"));
    boolean sawResult = false;
    boolean isError = false;

    for (String line : loadFixture(inv.fixture(env))) {
      if (line.isBlank()) {
        continue;
      }
      ObjectNode node = (ObjectNode) JSON.readTree(line);
      if (node.has("session_id")) {
        node.put("session_id", sessionId);
      }
      String type = node.path("type").asString("");
      if ("system".equals(type) && "init".equals(node.path("subtype").asString(""))) {
        node.put("cwd", cwd.toString());
      }
      if ("result".equals(type)) {
        sawResult = true;
        isError = node.path("is_error").asBoolean(false);
      }
      String json = JSON.writeValueAsString(node);
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
    if (!sawResult) {
      return EXIT_KILLED;
    }
    return isError ? 1 : 0;
  }

  static List<String> loadFixture(String fixture) throws IOException {
    Path path = Path.of(fixture);
    if (Files.isRegularFile(path)) {
      return Files.readAllLines(path, StandardCharsets.UTF_8);
    }
    String resource = "/claude/" + fixture + ".ndjson";
    try (InputStream in = FakeClaude.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalArgumentException("Fixture no encontrado: " + fixture);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
    }
  }

  /**
   * Fichero de la sesión, como lo guarda el CLI real; {@code null} sin {@code CLAUDE_CONFIG_DIR}.
   */
  static Path sessionFile(Map<String, String> env, Path cwd, String sessionId) {
    String configDir = env.get("CLAUDE_CONFIG_DIR");
    if (configDir == null || configDir.isBlank()) {
      return null;
    }
    return Path.of(configDir)
        .resolve("projects")
        .resolve(cwd.toString().replaceAll("[^A-Za-z0-9]", "-"))
        .resolve(sessionId + ".jsonl");
  }

  private static boolean hang(Map<String, String> env) {
    return !"false".equalsIgnoreCase(env.getOrDefault("FAKE_CLAUDE_HANG", "true"));
  }

  /** Subconjunto de flags del CLI real que afectan a la salida reproducida. */
  record Invocation(
      String outputFormat,
      String sessionId,
      String resume,
      boolean forkSession,
      List<String> rest) {

    static Invocation parse(String[] args) {
      String outputFormat = "text";
      String sessionId = null;
      String resume = null;
      boolean fork = false;
      List<String> rest = new ArrayList<>();
      for (int i = 0; i < args.length; i++) {
        switch (args[i]) {
          case "--output-format" -> outputFormat = args[++i];
          case "--session-id" -> sessionId = args[++i];
          case "--resume", "-r" -> resume = args[++i];
          case "--fork-session" -> fork = true;
          default -> rest.add(args[i]);
        }
      }
      return new Invocation(outputFormat, sessionId, resume, fork, List.copyOf(rest));
    }

    String fixture(Map<String, String> env) {
      if (resume == null) {
        return env.getOrDefault("FAKE_CLAUDE_FIXTURE", DEFAULT_FIXTURE);
      }
      return forkSession
          ? env.getOrDefault("FAKE_CLAUDE_FORK_FIXTURE", "04-fork")
          : env.getOrDefault("FAKE_CLAUDE_RESUME_FIXTURE", "03-resume");
    }

    String effectiveSessionId() {
      if (resume != null && !forkSession) {
        return resume;
      }
      if (sessionId != null) {
        return sessionId;
      }
      return UUID.randomUUID().toString();
    }
  }
}
