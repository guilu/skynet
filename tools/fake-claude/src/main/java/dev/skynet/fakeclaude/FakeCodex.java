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
 * Sustituto determinista de {@code codex exec --json}.
 *
 * <p>Reproduce un fixture JSONL (en {@code fixtures/codex}, escrito a partir del código fuente de
 * Codex: ver {@code docs/codex-exec-json.md}), reescribiendo {@code thread_id} para que coincida
 * con la invocación. Se configura con variables de entorno:
 *
 * <ul>
 *   <li>{@code FAKE_CODEX_FIXTURE}: nombre de un fixture empaquetado (p. ej. {@code 02-tools}) o
 *       ruta a un fichero JSONL. Por defecto {@code 01-simple-text}.
 *   <li>{@code FAKE_CODEX_RESUME_FIXTURE}: el fixture con {@code exec resume} y {@code exec fork}.
 *       Por defecto {@code 03-resume}.
 *   <li>{@code FAKE_CODEX_DELAY_MS}: pausa entre líneas. Por defecto 10.
 *   <li>{@code FAKE_CODEX_HANG}: si el fixture no termina el turno, el proceso se queda esperando
 *       hasta que lo maten. {@code false} para terminar con código 143.
 *   <li>{@code FAKE_CODEX_STRUCTURED_OUTPUT}: con {@code --output-schema}, el JSON que devuelve el
 *       último mensaje del agente, o {@code @ruta} para leerlo de ese fichero del directorio de
 *       trabajo. Sin ella el mensaje se queda en texto, como un agente que no cumple el schema.
 *   <li>{@code CODEX_HOME}: si está definida, guarda cada sesión en {@code
 *       <dir>/sessions/<sesión>.jsonl}, y reanudar o bifurcar falla si no la encuentra. Sin ella no
 *       comprueba nada.
 * </ul>
 */
public final class FakeCodex {

  static final String VERSION = "codex-cli 0.50.0";
  static final String DEFAULT_FIXTURE = "01-simple-text";
  static final String RESUME_FIXTURE = "03-resume";

  private static final ObjectMapper JSON = JsonMapper.builder().build();

  private FakeCodex() {}

  public static void main(String[] args) throws Exception {
    int code = run(args, System.getenv(), Path.of("").toAbsolutePath(), System.out, System.err);
    if (code == FakeClaude.EXIT_KILLED
        && !"false".equalsIgnoreCase(System.getenv().getOrDefault("FAKE_CODEX_HANG", "true"))) {
      Thread.currentThread().join(); // Simula una ejecución larga hasta recibir SIGTERM.
    }
    System.exit(code);
  }

  static int run(String[] args, Map<String, String> env, Path cwd, PrintStream out, PrintStream err)
      throws IOException, InterruptedException {
    if (args.length == 1 && "--version".equals(args[0])) {
      out.println(VERSION + " (fake-codex)");
      return 0;
    }
    Invocation inv = Invocation.parse(args);
    if (!inv.exec() || !inv.json()) {
      err.println("fake-codex: solo se admite `codex exec --json`");
      return 2;
    }
    String threadId =
        "fork".equals(inv.subcommand()) || inv.subcommand() == null
            ? UUID.randomUUID().toString()
            : inv.sessionId();
    Path sessionFile = sessionFile(env, threadId);
    if (inv.subcommand() != null && sessionFile != null) {
      Path resumed = sessionFile(env, inv.sessionId());
      if (!Files.isRegularFile(resumed)) {
        err.println("Error: no rollout found for thread id " + inv.sessionId());
        return 1;
      }
      if ("fork".equals(inv.subcommand())) {
        Files.copy(resumed, sessionFile, StandardCopyOption.REPLACE_EXISTING);
      }
    }
    String fixture =
        inv.subcommand() == null
            ? env.getOrDefault("FAKE_CODEX_FIXTURE", DEFAULT_FIXTURE)
            : env.getOrDefault("FAKE_CODEX_RESUME_FIXTURE", RESUME_FIXTURE);
    long delayMs = Long.parseLong(env.getOrDefault("FAKE_CODEX_DELAY_MS", "10"));
    String structured = inv.outputSchema() == null ? null : structuredOutput(env, cwd);
    List<String> lines = loadFixture(fixture);
    int lastMessage = -1;
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).contains("\"agent_message\"")) {
        lastMessage = i;
      }
    }
    boolean finished = false;
    boolean failed = false;
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i);
      if (line.isBlank()) {
        continue;
      }
      ObjectNode node = (ObjectNode) JSON.readTree(line);
      String type = node.path("type").asString("");
      if ("thread.started".equals(type)) {
        node.put("thread_id", threadId);
      }
      if (i == lastMessage && structured != null) {
        ((ObjectNode) node.get("item")).put("text", structured);
      }
      finished |= "turn.completed".equals(type) || "turn.failed".equals(type);
      failed |= "turn.failed".equals(type);
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
    if (!finished) {
      return FakeClaude.EXIT_KILLED;
    }
    return failed ? 1 : 0;
  }

  static List<String> loadFixture(String fixture) throws IOException {
    Path path = Path.of(fixture);
    if (Files.isRegularFile(path)) {
      return Files.readAllLines(path, StandardCharsets.UTF_8);
    }
    try (InputStream in = FakeCodex.class.getResourceAsStream("/codex/" + fixture + ".jsonl")) {
      if (in == null) {
        throw new IllegalArgumentException("Fixture no encontrado: " + fixture);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
    }
  }

  /** Fichero de la sesión; {@code null} sin {@code CODEX_HOME}. */
  static Path sessionFile(Map<String, String> env, String threadId) {
    String home = env.get("CODEX_HOME");
    if (home == null || home.isBlank()) {
      return null;
    }
    return Path.of(home).resolve("sessions").resolve(threadId + ".jsonl");
  }

  static String structuredOutput(Map<String, String> env, Path cwd) throws IOException {
    String value = env.get("FAKE_CODEX_STRUCTURED_OUTPUT");
    if (value == null || !value.startsWith("@")) {
      return value;
    }
    Path file = cwd.resolve(value.substring(1));
    return Files.isRegularFile(file)
        ? Files.readString(file, StandardCharsets.UTF_8).strip()
        : null;
  }

  /** Lo que importa de {@code codex exec [opciones] [resume|fork <sesión>] [--] <prompt>}. */
  record Invocation(
      boolean exec,
      boolean json,
      String subcommand,
      String sessionId,
      String outputSchema,
      String model,
      String sandbox,
      String prompt) {

    static Invocation parse(String[] args) {
      boolean exec = args.length > 0 && "exec".equals(args[0]);
      boolean json = false;
      String subcommand = null;
      String sessionId = null;
      String outputSchema = null;
      String model = null;
      String sandbox = null;
      List<String> positional = new ArrayList<>();
      for (int i = exec ? 1 : 0; i < args.length; i++) {
        switch (args[i]) {
          case "--json", "--experimental-json" -> json = true;
          case "--output-schema" -> outputSchema = args[++i];
          case "--model", "-m" -> model = args[++i];
          case "--sandbox", "-s" -> sandbox = args[++i];
          case "--dangerously-bypass-approvals-and-sandbox", "--yolo" -> sandbox = "none";
          case "--" -> {
            for (i++; i < args.length; i++) {
              positional.add(args[i]);
            }
          }
          case "resume", "fork" -> {
            if (subcommand == null && positional.isEmpty()) {
              subcommand = args[i];
              sessionId = args[++i];
            } else {
              positional.add(args[i]);
            }
          }
          default -> positional.add(args[i]);
        }
      }
      return new Invocation(
          exec,
          json,
          subcommand,
          sessionId,
          outputSchema,
          model,
          sandbox,
          positional.isEmpty() ? null : positional.getLast());
    }
  }
}
