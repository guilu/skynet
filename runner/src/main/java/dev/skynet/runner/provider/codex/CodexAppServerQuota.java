package dev.skynet.runner.provider.codex;

import dev.skynet.protocol.runner.QuotaSample;
import dev.skynet.runner.quota.QuotaSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.databind.JsonNode;

/**
 * La cuota de Codex sin ejecutar nada: {@code codex app-server} habla JSON-RPC por la entrada y la
 * salida estándar, una línea por mensaje. Se inicia ({@code initialize} e {@code initialized}), se
 * pide {@code account/rateLimits/read} y se cierra. No gasta cuota.
 */
public final class CodexAppServerQuota implements QuotaSource {

  private static final Duration TIMEOUT = Duration.ofSeconds(20);
  private static final int READ_ID = 2;

  private final String executable;
  private final Map<String, String> env;
  private final String clientVersion;

  public CodexAppServerQuota(String executable, Map<String, String> env, String clientVersion) {
    this.executable = executable;
    this.env = Map.copyOf(env);
    this.clientVersion = clientVersion == null ? "dev" : clientVersion;
  }

  @Override
  public String provider() {
    return CodexQuota.PROVIDER;
  }

  @Override
  public List<QuotaSample> read() throws IOException, InterruptedException {
    ProcessBuilder builder =
        new ProcessBuilder(executable, "app-server").redirectError(ProcessBuilder.Redirect.DISCARD);
    builder.environment().clear();
    builder.environment().putAll(env);
    Process process = builder.start();
    try {
      CompletableFuture<JsonNode> response =
          CompletableFuture.supplyAsync(() -> awaitResponse(process));
      try (Writer in = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
        in.write(
            "{\"id\":1,\"method\":\"initialize\",\"params\":{\"clientInfo\":"
                + "{\"name\":\"skynet\",\"title\":\"Skynet\",\"version\":\""
                + clientVersion.replace("\"", "")
                + "\"}}}\n");
        in.write("{\"method\":\"initialized\"}\n");
        in.write("{\"id\":" + READ_ID + ",\"method\":\"account/rateLimits/read\"}\n");
        in.flush();
        JsonNode result = response.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        if (result == null) {
          return List.of();
        }
        JsonNode limits =
            result.has("rateLimits") ? result.get("rateLimits") : result.get("rate_limits");
        return CodexQuota.fromRateLimits(limits, Instant.now());
      }
    } catch (ExecutionException | TimeoutException e) {
      throw new IOException("codex app-server no dio la cuota", e);
    } finally {
      process.destroyForcibly();
      process.waitFor(5, TimeUnit.SECONDS);
    }
  }

  /** El {@code result} de la respuesta a {@code account/rateLimits/read}, o {@code null}. */
  private static JsonNode awaitResponse(Process process) {
    try (BufferedReader out =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = out.readLine()) != null) {
        JsonNode node = CodexQuota.read(line);
        if (node != null && node.path("id").asInt(-1) == READ_ID) {
          return node.get("result");
        }
      }
      return null;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
