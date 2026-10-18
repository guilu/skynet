package dev.skynet.fakeclaude;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Sustituto determinista de Antigravity CLI ({@code agy}). Responde a {@code --version}, a {@code
 * agy -p /usage --output-format json} (la cuota, grabada con agy 1.4.0 en {@code
 * fixtures/agy/usage.json}, o la de {@code FAKE_AGY_USAGE}, ruta a un fichero) y a {@code agy
 * --output-format stream-json --print=<prompt>}, que reproduce un fixture JSONL grabado con agy
 * 1.4.2 ({@code fixtures/agy}, ver {@code docs/antigravity-stream-json.md}) con el id de la
 * conversación de la invocación y el directorio de trabajo real. Se configura con variables de
 * entorno:
 *
 * <ul>
 *   <li>{@code FAKE_AGY_FIXTURE}: nombre de un fixture empaquetado (p. ej. {@code 02-tools}) o ruta
 *       a un fichero JSONL. Por defecto {@code 01-simple-text}.
 *   <li>{@code FAKE_AGY_RESUME_FIXTURE}: el fixture con {@code --conversation}. Por defecto {@code
 *       03-resume}.
 *   <li>{@code FAKE_AGY_DELAY_MS}: pausa entre líneas. Por defecto 10.
 *   <li>{@code FAKE_AGY_HANG}: si el fixture no tiene {@code result}, el proceso se queda esperando
 *       hasta que lo maten. {@code false} para terminar con código 143.
 *   <li>{@code FAKE_AGY_STRUCTURED_OUTPUT}: con {@code --json-schema}, el JSON que devuelve en
 *       {@code structured_output}, o {@code @ruta} para leerlo de ese fichero del directorio de
 *       trabajo. Sin ella, el del fixture (si lo tiene).
 *   <li>{@code FAKE_AGY_HOME}: si está definida, guarda cada conversación en {@code
 *       <dir>/conversations/<id>.jsonl}; {@code --conversation} falla si no la encuentra.
 * </ul>
 */
public final class FakeAgy {

  static final String VERSION = "1.4.2";
  static final String DEFAULT_FIXTURE = "01-simple-text";
  static final String RESUME_FIXTURE = "03-resume";

  /** Directorio de trabajo con el que se grabaron los fixtures. */
  static final String FIXTURE_CWD = "/tmp/prueba";

  private static final ObjectMapper JSON = JsonMapper.builder().build();

  private FakeAgy() {}

  public static void main(String[] args) throws Exception {
    int code = run(args, System.getenv(), Path.of("").toAbsolutePath(), System.out, System.err);
    FakeCli.exit(code, "FAKE_AGY_HANG");
  }

  static int run(String[] args, Map<String, String> env, Path cwd, PrintStream out, PrintStream err)
      throws IOException, InterruptedException {
    if (args.length == 1 && "--version".equals(args[0])) {
      out.println(VERSION + " (fake-agy)");
      return 0;
    }
    if (args.length >= 2
        && "-p".equals(args[0])
        && "/usage".equals(args[1])
        && Arrays.asList(args).contains("json")) {
      out.println(usage(env));
      return 0;
    }
    Invocation inv = Invocation.parse(args);
    if (!"stream-json".equals(inv.outputFormat()) || inv.prompt() == null) {
      err.println(
          "fake-agy: solo se admite `agy -p /usage --output-format json` y"
              + " `agy --output-format stream-json --print=<prompt>`");
      return 2;
    }
    return replay(inv, env, cwd, out, err);
  }

  private static int replay(
      Invocation inv, Map<String, String> env, Path cwd, PrintStream out, PrintStream err)
      throws IOException, InterruptedException {
    String conversation =
        inv.conversation() != null ? inv.conversation() : UUID.randomUUID().toString();
    Path sessionFile = sessionFile(env, conversation);
    if (inv.conversation() != null && sessionFile != null && !Files.isRegularFile(sessionFile)) {
      err.println("Error: conversation " + inv.conversation() + " not found");
      return 1;
    }
    String fixture =
        inv.conversation() == null
            ? env.getOrDefault("FAKE_AGY_FIXTURE", DEFAULT_FIXTURE)
            : env.getOrDefault("FAKE_AGY_RESUME_FIXTURE", RESUME_FIXTURE);
    long delayMs = Long.parseLong(env.getOrDefault("FAKE_AGY_DELAY_MS", "10"));
    String structured =
        inv.jsonSchema() == null
            ? null
            : FakeCli.structuredOutput(env, "FAKE_AGY_STRUCTURED_OUTPUT", cwd);
    // Las rutas del fixture, en el directorio de trabajo de esta invocación.
    String recorded = JSON.writeValueAsString(FIXTURE_CWD);
    String actual = JSON.writeValueAsString(cwd.toString());
    String from = recorded.substring(1, recorded.length() - 1);
    String to = actual.substring(1, actual.length() - 1);
    int code = FakeClaude.EXIT_KILLED;
    for (String line : FakeCli.loadFixture("agy", fixture)) {
      if (line.isBlank()) {
        continue;
      }
      ObjectNode node = (ObjectNode) JSON.readTree(line.replace(from, to));
      switch (node.path("event").asString("")) {
        case "init" -> node.put("conversation_id", conversation);
        case "step_update" ->
            ((ObjectNode) node.get("step_update")).put("conversation_id", conversation);
        case "result" -> {
          ObjectNode result = (ObjectNode) node.get("result");
          result.put("conversation_id", conversation);
          if (inv.jsonSchema() == null) {
            result.remove("structured_output");
            result.remove("json_schema");
          } else if (structured != null) {
            result.set("structured_output", JSON.readTree(structured));
          }
          code = "SUCCESS".equals(result.path("status").asString("")) ? 0 : 1;
        }
        default -> {}
      }
      FakeCli.emit(out, sessionFile, JSON.writeValueAsString(node), delayMs);
    }
    return code;
  }

  /** Fichero de la conversación; {@code null} sin {@code FAKE_AGY_HOME}. */
  static Path sessionFile(Map<String, String> env, String conversation) {
    String home = env.get("FAKE_AGY_HOME");
    if (home == null || home.isBlank()) {
      return null;
    }
    return Path.of(home, "conversations", conversation + ".jsonl");
  }

  private static String usage(Map<String, String> env) throws IOException {
    String file = env.get("FAKE_AGY_USAGE");
    if (file != null && !file.isBlank()) {
      return Files.readString(Path.of(file), StandardCharsets.UTF_8).strip();
    }
    try (InputStream in = FakeAgy.class.getResourceAsStream("/agy/usage.json")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
    }
  }

  /** Lo que importa de {@code agy [opciones]}. */
  record Invocation(
      String outputFormat,
      String mode,
      String model,
      String jsonSchema,
      String conversation,
      String prompt) {

    static Invocation parse(String[] args) {
      String outputFormat = null;
      String mode = null;
      String model = null;
      String jsonSchema = null;
      String conversation = null;
      String prompt = null;
      for (int i = 0; i < args.length; i++) {
        String arg = args[i];
        if (arg.startsWith("--print=")) {
          prompt = arg.substring("--print=".length());
          continue;
        }
        switch (arg) {
          case "--output-format" -> outputFormat = args[++i];
          case "--mode" -> mode = args[++i];
          case "--dangerously-skip-permissions" -> mode = "skip-permissions";
          case "--model" -> model = args[++i];
          case "--json-schema" -> jsonSchema = args[++i];
          case "--conversation" -> conversation = args[++i];
          case "--print", "-p", "--prompt" -> prompt = args[++i];
          default -> {}
        }
      }
      return new Invocation(outputFormat, mode, model, jsonSchema, conversation, prompt);
    }
  }
}
