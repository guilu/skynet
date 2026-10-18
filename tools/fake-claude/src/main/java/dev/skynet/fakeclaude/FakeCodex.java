package dev.skynet.fakeclaude;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
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
 *       comprueba nada. Al final de la sesión añade un {@code token_count} con la cuota de la
 *       cuenta, como el CLI (que no la escribe en su salida).
 *   <li>{@code FAKE_CODEX_QUOTA}: la cuota que da, {@code <% de 5 h>,<% semanal>}. Por defecto
 *       {@code 23,13}, como la grabada en {@code fixtures/quota/codex-rollout-token-count.jsonl}.
 *   <li>{@code FAKE_CODEX_QUOTA_STEP}: con {@code CODEX_HOME}, cuánto sube la cuota con cada
 *       sesión, {@code <puntos de 5 h>,<puntos semanales>}: la cuenta va gastando como la de
 *       verdad. Lo que lleva se guarda en {@code <dir>/fake-quota}, que también lee {@code
 *       app-server}.
 * </ul>
 *
 * <p>{@code fake-codex app-server} responde por JSON-RPC a {@code initialize} y a {@code
 * account/rateLimits/read} con esa cuota.
 */
public final class FakeCodex {

  static final String VERSION = "codex-cli 0.50.0";
  static final String DEFAULT_FIXTURE = "01-simple-text";
  static final String RESUME_FIXTURE = "03-resume";

  private static final ObjectMapper JSON = JsonMapper.builder().build();

  private FakeCodex() {}

  public static void main(String[] args) throws Exception {
    int code =
        run(args, System.getenv(), Path.of("").toAbsolutePath(), System.in, System.out, System.err);
    FakeCli.exit(code, "FAKE_CODEX_HANG");
  }

  static int run(String[] args, Map<String, String> env, Path cwd, PrintStream out, PrintStream err)
      throws IOException, InterruptedException {
    return run(args, env, cwd, InputStream.nullInputStream(), out, err);
  }

  static int run(
      String[] args,
      Map<String, String> env,
      Path cwd,
      InputStream in,
      PrintStream out,
      PrintStream err)
      throws IOException, InterruptedException {
    if (args.length == 1 && "--version".equals(args[0])) {
      out.println(VERSION + " (fake-codex)");
      return 0;
    }
    if (args.length == 1 && "app-server".equals(args[0])) {
      return appServer(env, in, out);
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
    String structured =
        inv.outputSchema() == null
            ? null
            : FakeCli.structuredOutput(env, "FAKE_CODEX_STRUCTURED_OUTPUT", cwd);
    List<String> lines = FakeCli.loadFixture("codex", fixture);
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
      FakeCli.emit(out, sessionFile, JSON.writeValueAsString(node), delayMs);
    }
    if (!finished) {
      return FakeClaude.EXIT_KILLED;
    }
    if (sessionFile != null) {
      spend(env);
      // Solo en el fichero de la sesión, como el CLI.
      Files.writeString(
          sessionFile,
          tokenCount(env) + "\n",
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    }
    return failed ? 1 : 0;
  }

  /**
   * Porcentajes de la cuota ({@code 5h,semana}): lo que lleva gastado la cuenta ({@code
   * <CODEX_HOME>/fake-quota}) o, si aún no ha gastado, {@code FAKE_CODEX_QUOTA}.
   */
  static double[] quota(Map<String, String> env) throws IOException {
    Path spent = spentFile(env);
    if (spent != null && Files.exists(spent)) {
      return percentages(Files.readString(spent, StandardCharsets.UTF_8));
    }
    return percentages(env.getOrDefault("FAKE_CODEX_QUOTA", "23,13"));
  }

  /** Con {@code FAKE_CODEX_QUOTA_STEP}, la sesión gasta cuota de la cuenta. */
  private static void spend(Map<String, String> env) throws IOException {
    Path spent = spentFile(env);
    String step = env.get("FAKE_CODEX_QUOTA_STEP");
    if (spent == null || step == null || step.isBlank()) {
      return;
    }
    double[] now = quota(env);
    double[] add = percentages(step);
    Files.writeString(
        spent,
        Math.min(100, now[0] + add[0]) + "," + Math.min(100, now[1] + add[1]),
        StandardCharsets.UTF_8);
  }

  private static Path spentFile(Map<String, String> env) {
    String home = env.get("CODEX_HOME");
    return home == null || home.isBlank() ? null : Path.of(home, "fake-quota");
  }

  private static double[] percentages(String text) {
    String[] parts = text.trim().split(",");
    return new double[] {Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim())};
  }

  /** El {@code token_count} del fichero de la sesión, con la forma de Codex 0.163. */
  static String tokenCount(Map<String, String> env) throws IOException {
    double[] quota = quota(env);
    long now = Instant.now().getEpochSecond();
    return "{\"timestamp\":\""
        + Instant.now()
        + "\",\"type\":\"event_msg\",\"payload\":{\"type\":\"token_count\",\"info\":null,"
        + "\"rate_limits\":{\"limit_id\":\"codex\",\"primary\":{\"used_percent\":"
        + quota[0]
        + ",\"window_minutes\":300,\"resets_at\":"
        + (now + 3 * 3600)
        + "},\"secondary\":{\"used_percent\":"
        + quota[1]
        + ",\"window_minutes\":10080,\"resets_at\":"
        + (now + 5 * 86400)
        + "},\"plan_type\":\"plus\"}}}";
  }

  /** JSON-RPC por la entrada y la salida, una línea por mensaje, hasta que se cierre la entrada. */
  static int appServer(Map<String, String> env, InputStream in, PrintStream out)
      throws IOException {
    double[] quota = quota(env);
    long now = Instant.now().getEpochSecond();
    BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    String line;
    while ((line = reader.readLine()) != null) {
      if (line.isBlank()) {
        continue;
      }
      JsonNode request = JSON.readTree(line);
      if (!request.has("id")) {
        continue; // Una notificación (initialized).
      }
      ObjectNode response = JSON.createObjectNode();
      response.set("id", request.get("id"));
      switch (request.path("method").asString("")) {
        case "initialize" -> response.putObject("result").put("userAgent", "fake-codex");
        case "account/rateLimits/read" -> {
          ObjectNode limits = response.putObject("result").putObject("rateLimits");
          limits
              .putObject("primary")
              .put("usedPercent", quota[0])
              .put("windowDurationMins", 300)
              .put("resetsAt", now + 3 * 3600);
          limits
              .putObject("secondary")
              .put("usedPercent", quota[1])
              .put("windowDurationMins", 10080)
              .put("resetsAt", now + 5 * 86400);
          limits.put("planType", "plus");
        }
        default ->
            response.putObject("error").put("code", -32601).put("message", "method not found");
      }
      out.println(JSON.writeValueAsString(response));
      out.flush();
    }
    return 0;
  }

  /** Fichero de la sesión; {@code null} sin {@code CODEX_HOME}. */
  static Path sessionFile(Map<String, String> env, String threadId) {
    String home = env.get("CODEX_HOME");
    if (home == null || home.isBlank()) {
      return null;
    }
    return Path.of(home).resolve("sessions").resolve(threadId + ".jsonl");
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
