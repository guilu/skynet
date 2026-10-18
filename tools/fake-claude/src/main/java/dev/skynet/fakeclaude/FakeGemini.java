package dev.skynet.fakeclaude;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Sustituto determinista de {@code gemini --output-format stream-json}.
 *
 * <p>Reproduce un fixture JSONL (en {@code fixtures/gemini}, escrito a partir del código fuente de
 * Gemini CLI: ver {@code docs/gemini-stream-json.md}), reescribiendo {@code session_id} para que
 * coincida con la invocación. Se configura con variables de entorno:
 *
 * <ul>
 *   <li>{@code FAKE_GEMINI_FIXTURE}: nombre de un fixture empaquetado (p. ej. {@code 02-tools}) o
 *       ruta a un fichero JSONL. Por defecto {@code 01-simple-text}.
 *   <li>{@code FAKE_GEMINI_RESUME_FIXTURE}: el fixture con {@code --resume}. Por defecto {@code
 *       03-resume}.
 *   <li>{@code FAKE_GEMINI_DELAY_MS}: pausa entre líneas. Por defecto 10.
 *   <li>{@code FAKE_GEMINI_HANG}: si el fixture no tiene {@code result}, el proceso se queda
 *       esperando hasta que lo maten. {@code false} para terminar con código 143.
 *   <li>{@code FAKE_GEMINI_STRUCTURED_OUTPUT}: si el prompt pide un bloque JSON (salida
 *       estructurada), el texto que añade como último mensaje del agente, o {@code @ruta} para
 *       leerlo de ese fichero del directorio de trabajo. Sin ella no añade nada.
 *   <li>{@code GEMINI_CLI_HOME}: si está definida, guarda cada sesión en {@code
 *       <dir>/.gemini/tmp/<carpeta>/chats/<sesión>.jsonl} (por directorio de trabajo, como el CLI
 *       real); {@code --resume} falla si no la encuentra y {@code --session-id} si ya existe.
 * </ul>
 */
public final class FakeGemini {

  static final String VERSION = "0.65.0";
  static final String DEFAULT_FIXTURE = "01-simple-text";
  static final String RESUME_FIXTURE = "03-resume";

  /** Códigos de salida de Gemini CLI. */
  static final int EXIT_INPUT_ERROR = 42;

  static final int EXIT_TURN_LIMIT = 53;

  private static final ObjectMapper JSON = JsonMapper.builder().build();

  private FakeGemini() {}

  public static void main(String[] args) throws Exception {
    int code = run(args, System.getenv(), Path.of("").toAbsolutePath(), System.out, System.err);
    FakeCli.exit(code, "FAKE_GEMINI_HANG");
  }

  static int run(String[] args, Map<String, String> env, Path cwd, PrintStream out, PrintStream err)
      throws IOException, InterruptedException {
    Invocation inv = Invocation.parse(args);
    if (inv.version()) {
      out.println(VERSION + " (fake-gemini)");
      return 0;
    }
    if (!"stream-json".equals(inv.outputFormat()) || inv.prompt() == null) {
      err.println("fake-gemini: solo se admite `gemini --output-format stream-json -p <prompt>`");
      return 1;
    }
    if (inv.resume() != null && inv.sessionId() != null) {
      err.println("Cannot use both --resume and --session-id together");
      return EXIT_INPUT_ERROR;
    }
    String session =
        inv.resume() != null
            ? inv.resume()
            : inv.sessionId() != null ? inv.sessionId() : UUID.randomUUID().toString();
    Path sessionFile = sessionFile(env, cwd, session);
    if (sessionFile != null) {
      if (inv.resume() != null && !Files.isRegularFile(sessionFile)) {
        err.println(
            "Error resuming session: Invalid session identifier \""
                + inv.resume()
                + "\". Use --list-sessions to see available sessions.");
        return EXIT_INPUT_ERROR;
      }
      if (inv.resume() == null && Files.isRegularFile(sessionFile)) {
        err.println("Session ID \"" + session + "\" already exists. Use --resume to continue it.");
        return EXIT_INPUT_ERROR;
      }
    }
    String fixture =
        inv.resume() == null
            ? env.getOrDefault("FAKE_GEMINI_FIXTURE", DEFAULT_FIXTURE)
            : env.getOrDefault("FAKE_GEMINI_RESUME_FIXTURE", RESUME_FIXTURE);
    long delayMs = Long.parseLong(env.getOrDefault("FAKE_GEMINI_DELAY_MS", "10"));
    String structured =
        inv.prompt().contains("```json")
            ? FakeCli.structuredOutput(env, "FAKE_GEMINI_STRUCTURED_OUTPUT", cwd)
            : null;
    int code = FakeClaude.EXIT_KILLED;
    for (String line : FakeCli.loadFixture("gemini", fixture)) {
      if (line.isBlank()) {
        continue;
      }
      ObjectNode node = (ObjectNode) JSON.readTree(line);
      String type = node.path("type").asString("");
      switch (type) {
        case "init" -> {
          node.put("session_id", session);
          node.put("model", inv.model() == null ? "auto" : inv.model());
        }
        case "message" -> {
          if ("user".equals(node.path("role").asString(""))) {
            node.put("content", inv.prompt());
          }
        }
        case "result" -> {
          if (structured != null && "success".equals(node.path("status").asString(""))) {
            FakeCli.emit(
                out, sessionFile, assistant(node, "\n```json\n" + structured + "\n```"), delayMs);
          }
          if (inv.model() != null) {
            renameModels(node, inv.model());
          }
          code = resultCode(node);
        }
        default -> {}
      }
      FakeCli.emit(out, sessionFile, JSON.writeValueAsString(node), delayMs);
    }
    return code;
  }

  private static String assistant(JsonNode like, String content) {
    ObjectNode node = JSON.createObjectNode();
    node.put("type", "message");
    node.put("timestamp", like.path("timestamp").asString(""));
    node.put("role", "assistant");
    node.put("content", content);
    node.put("delta", true);
    return JSON.writeValueAsString(node);
  }

  /** Con {@code --model}, todo el uso es de ese modelo. */
  private static void renameModels(ObjectNode result, String model) {
    if (result.path("stats") instanceof ObjectNode stats
        && stats.path("models") instanceof ObjectNode models
        && !models.isEmpty()) {
      ObjectNode usage = stats.deepCopy();
      usage.remove(List.of("models", "duration_ms", "tool_calls"));
      models.removeAll();
      models.set(model, usage);
    }
  }

  private static int resultCode(JsonNode result) {
    if ("success".equals(result.path("status").asString(""))) {
      return 0;
    }
    return "FatalTurnLimitedError".equals(result.path("error").path("type").asString(""))
        ? EXIT_TURN_LIMIT
        : 1;
  }

  /** Fichero de la sesión; {@code null} sin {@code GEMINI_CLI_HOME}. */
  static Path sessionFile(Map<String, String> env, Path cwd, String session) {
    String home = env.get("GEMINI_CLI_HOME");
    if (home == null || home.isBlank()) {
      return null;
    }
    Path folder = cwd.getFileName() == null ? Path.of("root") : cwd.getFileName();
    return Path.of(home, ".gemini", "tmp")
        .resolve(folder)
        .resolve("chats")
        .resolve(session + ".jsonl");
  }

  /** Lo que importa de {@code gemini [opciones]}. */
  record Invocation(
      boolean version,
      String outputFormat,
      String approvalMode,
      String model,
      String sessionId,
      String resume,
      String prompt) {

    static Invocation parse(String[] args) {
      boolean version = false;
      String outputFormat = null;
      String approvalMode = null;
      String model = null;
      String sessionId = null;
      String resume = null;
      String prompt = null;
      for (int i = 0; i < args.length; i++) {
        String arg = args[i];
        if (arg.startsWith("--prompt=")) {
          prompt = arg.substring("--prompt=".length());
          continue;
        }
        switch (arg) {
          case "--version", "-v" -> version = true;
          case "--output-format", "-o" -> outputFormat = args[++i];
          case "--approval-mode" -> approvalMode = args[++i];
          case "--yolo", "-y" -> approvalMode = "yolo";
          case "--model", "-m" -> model = args[++i];
          case "--session-id" -> sessionId = args[++i];
          case "--resume", "-r" -> resume = args[++i];
          case "--prompt", "-p" -> prompt = args[++i];
          default -> {}
        }
      }
      return new Invocation(version, outputFormat, approvalMode, model, sessionId, resume, prompt);
    }
  }
}
