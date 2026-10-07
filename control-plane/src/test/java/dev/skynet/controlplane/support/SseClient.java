package dev.skynet.controlplane.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Cliente SSE mínimo para tests: acumula los mensajes recibidos ({@code id} + {@code data}). */
public final class SseClient implements AutoCloseable {

  public record Message(long id, JsonNode data) {}

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final BlockingQueue<Message> messages = new LinkedBlockingQueue<>();
  private final CompletableFuture<HttpResponse<Stream<String>>> response;
  private final HttpClient client =
      HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

  public SseClient(String url, Long lastEventId) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(url))
            .header("Accept", "text/event-stream")
            .header("Authorization", IntegrationTest.ADMIN_BASIC_AUTH);
    if (lastEventId != null) {
      request.header("Last-Event-ID", Long.toString(lastEventId));
    }
    response = client.sendAsync(request.build(), HttpResponse.BodyHandlers.ofLines());
    response.thenAccept(r -> CompletableFuture.runAsync(() -> consume(r.body())));
  }

  private void consume(Stream<String> lines) {
    long[] id = {-1};
    StringBuilder data = new StringBuilder();
    try {
      lines.forEach(
          line -> {
            if (line.startsWith("id:")) {
              id[0] = Long.parseLong(line.substring(3).trim());
            } else if (line.startsWith("data:")) {
              data.append(line.substring(5));
            } else if (line.isEmpty() && !data.isEmpty()) {
              messages.add(new Message(id[0], JSON.readTree(data.toString())));
              data.setLength(0);
            }
          });
    } catch (RuntimeException closed) {
      // El stream se cierra al terminar el test.
    }
  }

  /** Espera hasta recibir {@code count} mensajes o falla. */
  public List<Message> await(int count, Duration timeout) throws InterruptedException {
    List<Message> received = new ArrayList<>();
    long deadline = System.nanoTime() + timeout.toNanos();
    while (received.size() < count) {
      long left = deadline - System.nanoTime();
      Message next = left > 0 ? messages.poll(left, TimeUnit.NANOSECONDS) : null;
      if (next == null) {
        throw new AssertionError(
            "Recibidos " + received.size() + " de " + count + " mensajes SSE: " + received);
      }
      received.add(next);
    }
    return received;
  }

  /** Comprueba que no llega ningún mensaje más durante {@code quiet}. */
  public Message poll(Duration quiet) throws InterruptedException {
    return messages.poll(quiet.toMillis(), TimeUnit.MILLISECONDS);
  }

  @Override
  public void close() {
    response.cancel(true);
    response.thenAccept(r -> r.body().close());
    // close() esperaría a que terminase el stream, que nunca termina.
    client.shutdownNow();
  }
}
