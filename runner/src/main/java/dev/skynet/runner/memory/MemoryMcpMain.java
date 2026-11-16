package dev.skynet.runner.memory;

import dev.skynet.runner.mcp.McpLaunch;
import dev.skynet.runner.mcp.StdioMcpServer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code skynet-runner memory-mcp --url <control plane> --token-file <fichero> [--mode
 * read|write]}: el servidor MCP de memoria que lanza el CLI del agente. Solo habla JSON-RPC por
 * stdout; lo demás va a stderr.
 */
public final class MemoryMcpMain {

  private static final Duration TIMEOUT = Duration.ofSeconds(15);
  private static final ObjectMapper JSON = JsonMapper.builder().build();

  private MemoryMcpMain() {}

  public static void run(String[] args, String version) throws IOException, InterruptedException {
    McpLaunch launch = McpLaunch.parse(args, "memory-mcp", Set.of("mode"), " [--mode read|write]");
    boolean writable = "write".equals(launch.options().getOrDefault("mode", "write"));
    McpLaunch.serve(
        new MemoryMcpServer(
            new HttpApi(URI.create(launch.url()), launch.token()), writable, version));
  }

  /** Las llamadas a {@code /api/runner/memory/*} con el token de la invocación. */
  static final class HttpApi implements MemoryMcpServer.Api {

    private final URI base;
    private final String token;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    HttpApi(URI base, String token) {
      this.base = base;
      this.token = token;
    }

    @Override
    public JsonNode call(String operation, ObjectNode body)
        throws IOException, InterruptedException {
      HttpRequest request =
          HttpRequest.newBuilder(base.resolve("/api/runner/memory/" + operation))
              .timeout(TIMEOUT)
              .header("Content-Type", "application/json")
              .header("Authorization", "Bearer " + token)
              .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
              .build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new MemoryMcpServer.ApiException(
            "La memoria respondió "
                + response.statusCode()
                + ": "
                + StdioMcpServer.problemDetail(response.body()));
      }
      return JSON.readTree(response.body());
    }
  }
}
