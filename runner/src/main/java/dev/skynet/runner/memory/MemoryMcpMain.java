package dev.skynet.runner.memory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import tools.jackson.core.JacksonException;
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
    String url = null;
    Path tokenFile = null;
    String mode = "write";
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--url" -> url = value(args, ++i);
        case "--token-file" -> tokenFile = Path.of(value(args, ++i));
        case "--mode" -> mode = value(args, ++i);
        default -> usage("argumento desconocido: " + args[i]);
      }
    }
    if (url == null || tokenFile == null) {
      usage("faltan --url y --token-file");
    }
    String token = Files.readString(tokenFile, StandardCharsets.UTF_8).strip();
    PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
    // Nada más que JSON-RPC por stdout: lo que escriba otro código va a stderr.
    System.setOut(System.err);
    new MemoryMcpServer(new HttpApi(URI.create(url), token), !"read".equals(mode), version)
        .serve(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), out);
  }

  private static String value(String[] args, int i) {
    if (i >= args.length) {
      usage("falta el valor de " + args[i - 1]);
    }
    return args[i];
  }

  private static void usage(String problem) {
    System.err.println(
        "skynet-runner memory-mcp: "
            + problem
            + "\nUso: skynet-runner memory-mcp --url <control plane> --token-file <fichero>"
            + " [--mode read|write]");
    System.exit(2);
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
            "La memoria respondió " + response.statusCode() + ": " + detail(response.body()));
      }
      return JSON.readTree(response.body());
    }

    /** El {@code detail} de un ProblemDetail, o el cuerpo tal cual. */
    private static String detail(String body) {
      try {
        String detail = JSON.readTree(body).path("detail").asString("");
        return detail.isBlank() ? body : detail;
      } catch (JacksonException e) {
        return body;
      }
    }
  }
}
