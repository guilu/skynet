package dev.skynet.runner.transport;

import dev.skynet.protocol.runner.EventBatch;
import dev.skynet.protocol.runner.EventBatchResult;
import dev.skynet.protocol.runner.RunnerCommand;
import dev.skynet.protocol.runner.RunnerHeartbeat;
import dev.skynet.protocol.runner.RunnerRegistered;
import dev.skynet.protocol.runner.RunnerRegistration;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cliente HTTP del protocolo runner ↔ control plane (docs/implementation-plan.md §4.3). El runner
 * siempre inicia la conexión.
 */
public class ControlPlaneClient {

  private static final ObjectMapper JSON = JsonMapper.builder().build();
  private static final Duration TIMEOUT = Duration.ofSeconds(15);

  private final URI base;
  private final HttpClient http;
  private volatile String token;

  public ControlPlaneClient(URI base) {
    this.base = base;
    this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  }

  public void useToken(String token) {
    this.token = token;
  }

  public RunnerRegistered register(RunnerRegistration registration)
      throws IOException, InterruptedException {
    return send(post("/api/runner/register", registration, false), RunnerRegistered.class, TIMEOUT);
  }

  public void heartbeat(RunnerHeartbeat heartbeat) throws IOException, InterruptedException {
    send(post("/api/runner/heartbeat", heartbeat, true), Void.class, TIMEOUT);
  }

  /** Long-poll de órdenes: vuelve en cuanto hay alguna o al agotar {@code wait}. */
  public List<RunnerCommand> commands(Duration wait) throws IOException, InterruptedException {
    HttpRequest request =
        authorized(
                HttpRequest.newBuilder(
                    base.resolve("/api/runner/commands?waitSeconds=" + wait.toSeconds())))
            .GET()
            .build();
    String body = send(request, String.class, wait.plus(TIMEOUT));
    return JSON.readValue(body, new TypeReference<List<RunnerCommand>>() {});
  }

  public void ack(UUID commandId) throws IOException, InterruptedException {
    send(post("/api/runner/commands/" + commandId + "/ack", null, true), Void.class, TIMEOUT);
  }

  public EventBatchResult events(EventBatch batch) throws IOException, InterruptedException {
    return send(post("/api/runner/events", batch, true), EventBatchResult.class, TIMEOUT);
  }

  private HttpRequest post(String path, Object body, boolean auth) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(base.resolve(path))
            .header("Content-Type", "application/json")
            .POST(
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
    return (auth ? authorized(builder) : builder).build();
  }

  private HttpRequest.Builder authorized(HttpRequest.Builder builder) {
    String current = token;
    return current == null ? builder : builder.header("Authorization", "Bearer " + current);
  }

  private <T> T send(HttpRequest request, Class<T> type, Duration timeout)
      throws IOException, InterruptedException {
    HttpRequest timed =
        HttpRequest.newBuilder(request, (name, value) -> true).timeout(timeout).build();
    HttpResponse<String> response = http.send(timed, HttpResponse.BodyHandlers.ofString());
    int status = response.statusCode();
    if (status == 401) {
      throw new UnauthorizedException(request.uri().getPath() + ": " + response.body());
    }
    if (status < 200 || status >= 300) {
      throw new IOException(
          request.method()
              + " "
              + request.uri().getPath()
              + " → "
              + status
              + ": "
              + response.body());
    }
    if (type == Void.class) {
      return null;
    }
    if (type == String.class) {
      return type.cast(response.body());
    }
    return JSON.readValue(response.body(), type);
  }
}
