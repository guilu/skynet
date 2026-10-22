package dev.skynet.runner.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.skynet.protocol.AgentEventType;
import dev.skynet.runner.journal.Journal;
import dev.skynet.runner.transport.ControlPlaneClient;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class EventSenderTest {

  @TempDir Path dir;

  private HttpServer server;
  private Journal journal;
  private EventSender sender;
  private final List<String> received = new ArrayList<>();
  private int status5xx;

  @BeforeEach
  void setUp() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    // Un control plane que no puede guardar los eventos marcados como `veneno`: rechaza el lote
    // entero con 400, como al no poder leer o guardar su JSON.
    server.createContext(
        "/api/runner/events",
        exchange -> {
          JsonNode batch = JsonMapper.shared().readTree(exchange.getRequestBody());
          boolean poisoned = false;
          for (JsonNode event : batch.get("events")) {
            poisoned |= event.path("payload").has("veneno");
          }
          String body;
          int status;
          if (status5xx > 0) {
            status5xx--;
            status = 503;
            body = "{}";
          } else if (poisoned) {
            status = 400;
            body = "{\"detail\":\"no se puede guardar\"}";
          } else {
            for (JsonNode event : batch.get("events")) {
              received.add(event.path("payload").path("n").asString());
            }
            status = 200;
            body = "{\"accepted\":" + batch.get("events").size() + ",\"duplicates\":0}";
          }
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    journal = Journal.open(dir.resolve("journal.db"));
    ControlPlaneClient client =
        new ControlPlaneClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    client.useToken("token");
    sender = new EventSender(journal, client, Duration.ofSeconds(1), Duration.ofMillis(50));
  }

  @AfterEach
  void tearDown() {
    sender.close();
    journal.close();
    server.stop(0);
  }

  @Test
  void anEventTheControlPlaneCannotStoreDoesNotBlockTheOnesAfterIt() {
    UUID agent = UUID.randomUUID();
    append(agent, Map.of("n", "1"));
    append(agent, Map.of("n", "2", "veneno", true));
    append(agent, Map.of("n", "3"));

    assertThat(sender.flush()).isTrue();

    assertThat(received).containsExactly("1", "3");
    assertThat(journal.pending(10)).isEmpty();
  }

  @Test
  void aFailureThatMayChangeKeepsTheEventsToRetry() {
    UUID agent = UUID.randomUUID();
    append(agent, Map.of("n", "1"));
    status5xx = 1;

    assertThat(sender.flush()).isFalse();
    assertThat(journal.pending(10)).hasSize(1);

    assertThat(sender.flush()).isTrue();
    assertThat(received).containsExactly("1");
  }

  private void append(UUID agent, Map<String, Object> payload) {
    journal.append(agent, AgentEventType.MESSAGE_RECEIVED, payload, Instant.now());
  }
}
