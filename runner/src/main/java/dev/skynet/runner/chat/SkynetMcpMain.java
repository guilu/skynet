package dev.skynet.runner.chat;

import dev.skynet.runner.mcp.McpLaunch;
import dev.skynet.runner.mcp.StdioMcpServer;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code skynet-runner skynet-mcp --url <control plane> --token-file <fichero>}: el MCP de Skynet
 * que lanza el CLI del chat con Skyny (CH-B). Solo habla JSON-RPC por stdout; lo demás va a stderr.
 */
public final class SkynetMcpMain {

  private static final Duration TIMEOUT = Duration.ofSeconds(15);
  private static final ObjectMapper JSON = JsonMapper.builder().build();

  private SkynetMcpMain() {}

  public static void run(String[] args, String version) throws IOException, InterruptedException {
    McpLaunch launch = McpLaunch.parse(args, "skynet-mcp", Set.of(), "");
    McpLaunch.serve(
        new SkynetMcpServer(new HttpApi(URI.create(launch.url()), launch.token()), version));
  }

  /** Las llamadas a la API con el token de la conversación. */
  static final class HttpApi implements SkynetMcpServer.Api {

    private final URI base;
    private final String token;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    HttpApi(URI base, String token) {
      this.base = base;
      this.token = token;
    }

    @Override
    public JsonNode get(String path, Map<String, String> query)
        throws IOException, InterruptedException {
      return send(request(path + queryString(query)).GET().build());
    }

    @Override
    public JsonNode post(String path, JsonNode body) throws IOException, InterruptedException {
      return send(
          request(path)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
              .build());
    }

    private HttpRequest.Builder request(String pathAndQuery) {
      return HttpRequest.newBuilder(base.resolve(pathAndQuery))
          .timeout(TIMEOUT)
          .header("Accept", "application/json")
          .header("Authorization", "Bearer " + token);
    }

    private JsonNode send(HttpRequest request) throws IOException, InterruptedException {
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new SkynetMcpServer.ToolException(
            "Skynet respondió "
                + response.statusCode()
                + ": "
                + StdioMcpServer.problemDetail(response.body()));
      }
      return JSON.readTree(response.body());
    }

    static String queryString(Map<String, String> query) {
      if (query.isEmpty()) {
        return "";
      }
      return "?"
          + query.entrySet().stream()
              .map(
                  e ->
                      URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                          + "="
                          + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
              .collect(Collectors.joining("&"));
    }
  }
}
