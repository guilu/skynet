package dev.skynet.fakeclaude;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Llamadas de guion a un servidor MCP por stdio, como las haría el agente: así los fakes prueban el
 * servidor de memoria del runner de verdad.
 *
 * <ul>
 *   <li>{@code FAKE_MCP_CALLS}: array JSON de {@code {"tool": "...", "arguments": {...}}}, o
 *       {@code @ruta} para leerlo de ese fichero del directorio de trabajo. Sin ella no se lanza el
 *       servidor.
 *   <li>{@code FAKE_MCP_LOG}: fichero (relativo al directorio de trabajo) donde se añade, por cada
 *       llamada, una línea con la respuesta de {@code tools/call}; la primera es la de {@code
 *       tools/list}.
 * </ul>
 */
final class McpScript {

  private static final ObjectMapper JSON = JsonMapper.builder().build();

  private McpScript() {}

  /** Lanza el servidor, hace las llamadas del guion y lo cierra. No hace nada sin guion. */
  static void run(List<String> server, Map<String, String> env, Path cwd, PrintStream err)
      throws IOException, InterruptedException {
    String calls = FakeCli.structuredOutput(env, "FAKE_MCP_CALLS", cwd);
    if (calls == null || calls.isBlank() || server == null || server.isEmpty()) {
      return;
    }
    String logName = env.get("FAKE_MCP_LOG");
    Path log = logName == null || logName.isBlank() ? null : cwd.resolve(logName);
    Process process =
        new ProcessBuilder(server)
            .directory(cwd.toFile())
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start();
    try (PrintStream to = new PrintStream(process.getOutputStream(), true, StandardCharsets.UTF_8);
        BufferedReader from =
            new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      int id = 0;
      request(to, from, ++id, "initialize", initialize());
      to.println("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
      append(log, request(to, from, ++id, "tools/list", JSON.createObjectNode()));
      for (JsonNode call : JSON.readTree(calls)) {
        ObjectNode params = JSON.createObjectNode();
        params.put("name", call.path("tool").asString());
        params.set("arguments", call.path("arguments"));
        append(log, request(to, from, ++id, "tools/call", params));
      }
    } catch (IOException e) {
      err.println("fake: el servidor MCP falló: " + e.getMessage());
    } finally {
      if (!process.waitFor(10, TimeUnit.SECONDS)) {
        process.destroyForcibly();
      }
    }
  }

  private static ObjectNode initialize() {
    ObjectNode params = JSON.createObjectNode();
    params.put("protocolVersion", "2025-06-18");
    params.putObject("capabilities");
    params.putObject("clientInfo").put("name", "fake").put("version", "1");
    return params;
  }

  private static JsonNode request(
      PrintStream to, BufferedReader from, int id, String method, JsonNode params)
      throws IOException {
    ObjectNode message = JSON.createObjectNode();
    message.put("jsonrpc", "2.0");
    message.put("id", id);
    message.put("method", method);
    message.set("params", params);
    to.println(JSON.writeValueAsString(message));
    String line = from.readLine();
    if (line == null) {
      throw new IOException("se cerró sin responder a " + method);
    }
    return JSON.readTree(line);
  }

  private static void append(Path log, JsonNode response) throws IOException {
    if (log == null) {
      return;
    }
    Files.createDirectories(log.toAbsolutePath().getParent());
    Files.writeString(
        log,
        JSON.writeValueAsString(response) + "\n",
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);
  }

  /** El servidor {@code name} de un fichero {@code --mcp-config} de Claude Code. */
  static List<String> fromClaudeConfig(Path config, String name) throws IOException {
    JsonNode server = JSON.readTree(config.toFile()).path("mcpServers").path(name);
    if (server.isMissingNode()) {
      return List.of();
    }
    java.util.ArrayList<String> command = new java.util.ArrayList<>();
    command.add(server.path("command").asString());
    server.path("args").forEach(a -> command.add(a.asString()));
    return command;
  }

  /**
   * El servidor {@code name} de los {@code -c mcp_servers.<name>.command=…} y {@code …args=[…]} de
   * Codex; sus valores son cadenas y arrays TOML que también son JSON.
   */
  static List<String> fromCodexConfig(List<String> overrides, String name) {
    String prefix = "mcp_servers." + name + ".";
    String command = null;
    java.util.ArrayList<String> args = new java.util.ArrayList<>();
    for (String override : overrides) {
      if (override.startsWith(prefix + "command=")) {
        command = JSON.readTree(override.substring((prefix + "command=").length())).asString();
      } else if (override.startsWith(prefix + "args=")) {
        JSON.readTree(override.substring((prefix + "args=").length()))
            .forEach(a -> args.add(a.asString()));
      }
    }
    if (command == null) {
      return List.of();
    }
    args.addFirst(command);
    return args;
  }
}
