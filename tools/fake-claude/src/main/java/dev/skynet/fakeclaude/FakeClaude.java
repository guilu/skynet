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
 *   <li>{@code FAKE_CLAUDE_APPLY}: con {@code 1}, ejecuta de verdad las herramientas {@code Edit},
 *       {@code Write} y {@code Bash} del fixture en el directorio de trabajo (las rutas del fixture
 *       se traducen desde su {@code cwd}). Así una ejecución de prueba cambia archivos y hace
 *       commits.
 *   <li>{@code FAKE_CLAUDE_STRUCTURED_OUTPUT}: con {@code --json-schema}, el JSON que la línea
 *       {@code result} devuelve en {@code structured_output}, o {@code @ruta} para leerlo de ese
 *       fichero del directorio de trabajo (así cada ejecución decide el suyo). Sin ella, o sin el
 *       fichero, no devuelve ninguno, como un agente que no consigue cumplir el schema.
 *   <li>{@code FAKE_CLAUDE_FAIL_ONCE}: ruta de un fichero. Si no existe, lo crea y termina con
 *       código 1 sin emitir nada, como un fallo pasajero; si ya existe, se comporta como siempre.
 *       Para probar los reintentos (W6): conviene que esté fuera del worktree, porque el reintento
 *       lo restaura y borraría el fichero.
 *   <li>{@code FAKE_MCP_CALLS} y {@code FAKE_MCP_LOG}: con {@code --mcp-config}, llamadas de guion
 *       al servidor de memoria {@code skynet_memoria} ({@link McpScript}).
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

  /** Versión que anuncia {@code --version}. */
  static final String VERSION = "2.1.0";

  static int run(String[] args, Map<String, String> env, Path cwd, PrintStream out, PrintStream err)
      throws IOException, InterruptedException {
    if (args.length == 1 && "--version".equals(args[0])) {
      // Como el CLI real: el runner lo usa para saber que Claude Code está disponible.
      out.println(VERSION + " (fake-claude)");
      return 0;
    }
    Invocation inv = Invocation.parse(args);
    if (!"stream-json".equals(inv.outputFormat())) {
      err.println("fake-claude: solo se admite --output-format stream-json");
      return 2;
    }
    String failOnce = env.get("FAKE_CLAUDE_FAIL_ONCE");
    if (failOnce != null && !failOnce.isBlank()) {
      Path marker = cwd.resolve(failOnce);
      if (!Files.exists(marker)) {
        Files.createDirectories(marker.toAbsolutePath().getParent());
        Files.writeString(marker, "fake-claude falló aquí una vez\n");
        err.println("fake-claude: fallo pasajero simulado (FAKE_CLAUDE_FAIL_ONCE)");
        return 1;
      }
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
    if (inv.mcpConfig() != null) {
      // Las llamadas de FAKE_MCP_CALLS al servidor de memoria, antes de la sesión grabada.
      McpScript.run(
          McpScript.fromClaudeConfig(Path.of(inv.mcpConfig()), "skynet_memoria"), env, cwd, err);
    }
    long delayMs = Long.parseLong(env.getOrDefault("FAKE_CLAUDE_DELAY_MS", "10"));
    ToolApplier applier =
        "1".equals(env.get("FAKE_CLAUDE_APPLY")) ? new ToolApplier(cwd, err) : null;
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
        if (applier != null) {
          applier.fixtureCwd(node.path("cwd").asString(""));
        }
        node.put("cwd", cwd.toString());
      }
      if ("result".equals(type)) {
        sawResult = true;
        isError = node.path("is_error").asBoolean(false);
        String structured = structuredOutput(env, cwd);
        if (inv.jsonSchema() != null && structured != null && !isError) {
          node.set("structured_output", JSON.readTree(structured));
        }
      }
      String json = JSON.writeValueAsString(node);
      out.println(json);
      out.flush();
      if (applier != null && "assistant".equals(type)) {
        applier.apply(node.path("message").path("content"));
      }
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

  /**
   * El JSON de {@code FAKE_CLAUDE_STRUCTURED_OUTPUT}: el valor tal cual o, con {@code @ruta}, lo
   * que haya en ese fichero del directorio de trabajo; {@code null} si no hay variable o fichero.
   */
  static String structuredOutput(Map<String, String> env, Path cwd) throws IOException {
    String value = env.get("FAKE_CLAUDE_STRUCTURED_OUTPUT");
    if (value == null || !value.startsWith("@")) {
      return value;
    }
    Path file = cwd.resolve(value.substring(1));
    return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
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
      String jsonSchema,
      String mcpConfig,
      List<String> rest) {

    static Invocation parse(String[] args) {
      String outputFormat = "text";
      String sessionId = null;
      String resume = null;
      boolean fork = false;
      String jsonSchema = null;
      String mcpConfig = null;
      List<String> rest = new ArrayList<>();
      for (int i = 0; i < args.length; i++) {
        if (args[i].equals("--")) {
          // Lo que sigue es el prompt, aunque empiece por "-".
          rest.addAll(List.of(args).subList(i + 1, args.length));
          break;
        }
        switch (args[i]) {
          case "--output-format" -> outputFormat = args[++i];
          case "--session-id" -> sessionId = args[++i];
          case "--resume", "-r" -> resume = args[++i];
          case "--fork-session" -> fork = true;
          case "--json-schema" -> jsonSchema = args[++i];
          case "--mcp-config" -> mcpConfig = args[++i];
          default -> rest.add(args[i]);
        }
      }
      return new Invocation(
          outputFormat, sessionId, resume, fork, jsonSchema, mcpConfig, List.copyOf(rest));
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
