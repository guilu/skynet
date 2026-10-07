package dev.skynet.runner;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.skynet.protocol.AgentEventType;
import dev.skynet.protocol.runner.RunnerCommand;
import dev.skynet.runner.agent.AgentExecutor;
import dev.skynet.runner.agent.ArtifactSender;
import dev.skynet.runner.agent.ArtifactSpool;
import dev.skynet.runner.agent.EventSender;
import dev.skynet.runner.journal.Journal;
import dev.skynet.runner.supervisor.ProcessSupervisor;
import dev.skynet.runner.transport.ControlPlaneClient;
import dev.skynet.runner.workspace.WorkspaceManager;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** El daemon completo contra un control plane simulado y fake-claude como agente. */
class RunnerDaemonTest {

  private static final ObjectMapper JSON = JsonMapper.builder().build();

  @TempDir Path dir;

  private final StubControlPlane controlPlane = new StubControlPlane();
  private final List<AutoCloseable> closeables = new CopyOnWriteArrayList<>();
  private Path repo;
  private Journal journal;

  @BeforeEach
  void setUp() throws Exception {
    controlPlane.start();
    repo = TestRepos.create(dir.resolve("repo"));
    journal = Journal.open(dir.resolve("journal.db"));
  }

  @AfterEach
  void tearDown() throws Exception {
    for (AutoCloseable c : closeables.reversed()) {
      c.close();
    }
    journal.close();
    controlPlane.stop();
  }

  private RunnerDaemon daemon() {
    RunnerConfig config =
        new RunnerConfig(
            controlPlane.uri(),
            "test-runner",
            "registration-secret",
            2,
            dir,
            null,
            List.of(),
            Duration.ofSeconds(1),
            Duration.ofMillis(200),
            Duration.ofSeconds(2));
    ControlPlaneClient client = new ControlPlaneClient(config.controlPlane());
    EventSender sender =
        new EventSender(journal, client, Duration.ofMillis(100), Duration.ofMillis(100));
    ArtifactSender artifactSender =
        new ArtifactSender(journal, client, Duration.ofMillis(100), Duration.ofMillis(100));
    ProcessSupervisor supervisor = new ProcessSupervisor();
    AgentExecutor executor =
        new AgentExecutor(
            journal,
            supervisor,
            new WorkspaceManager(config.workspacesDir()),
            TestAgents.fakeClaude(),
            TestAgents.fakeClaudeEnv("02-tools"),
            config.logsDir(),
            config.cancelGrace(),
            sender::wakeUp,
            new ArtifactSpool(journal, config.artifactsDir(), artifactSender::wakeUp));
    RunnerDaemon daemon =
        new RunnerDaemon(
            config, client, journal, executor, sender, artifactSender, supervisor, "2.1.0");
    closeables.add(sender);
    closeables.add(artifactSender);
    closeables.add(daemon);
    return daemon;
  }

  private void await(String what, BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError(
            "Sin llegar a: "
                + what
                + " acks="
                + controlPlane.acks
                + " recibidos="
                + controlPlane.order.size()
                + " pendientes="
                + journal.pending(1000).stream().map(e -> e.type() + " " + e.payload()).toList());
      }
      Thread.sleep(50);
    }
  }

  @Test
  void runsAStartCommandAndDeliversEveryEventDespiteFailedSends() throws Exception {
    controlPlane.failEventsTimes.set(3);
    controlPlane.failArtifactsTimes.set(2);
    UUID agentRunId = UUID.randomUUID();
    RunnerCommand start = TestAgents.start(agentRunId, repo.toString(), null);
    controlPlane.commands.add(start);

    daemon().start();
    await(
        "agent.process.exited",
        () -> controlPlane.typesOf(agentRunId).contains("agent.process.exited"));

    assertThat(controlPlane.registrations.get()).isEqualTo(1);
    assertThat(controlPlane.acks).containsExactly(start.id());
    List<JsonNode> events = controlPlane.eventsOf(agentRunId);
    assertThat(events).extracting(e -> e.get("seq").asLong()).doesNotHaveDuplicates();
    assertThat(events.stream().mapToLong(e -> e.get("seq").asLong()).max().orElseThrow())
        .isEqualTo(events.size());
    assertThat(controlPlane.typesOf(agentRunId))
        .startsWith("agent.workspace.ready", "agent.session.started")
        .contains("agent.result")
        .endsWith("agent.process.exited");
    assertThat(events.getLast().get("payload").get("exitCode").asInt()).isZero();
    await("journal vacío", () -> journal.pending(1).isEmpty());
    await("latido", () -> !controlPlane.heartbeats.isEmpty());
    await("artefactos subidos", () -> journal.pendingArtifacts(1).isEmpty());
    assertThat(controlPlane.artifactUploads)
        .hasSize(4)
        .anySatisfy(b -> assertThat(b).contains("\"type\":\"PROMPT\"").contains("Arregla add"))
        .anySatisfy(b -> assertThat(b).contains("\"type\":\"LOG\""))
        .anySatisfy(b -> assertThat(b).contains("\"type\":\"RESULT\""))
        .anySatisfy(b -> assertThat(b).contains("\"type\":\"GIT_CHANGES\""));
    try (var spooled = java.nio.file.Files.list(dir.resolve("artifacts"))) {
      assertThat(spooled).isEmpty();
    }
  }

  @Test
  void reusesItsTokenAndRegistersAgainWhenItIsRejected() throws Exception {
    RunnerDaemon first = daemon();
    first.start();
    await("latido", () -> !controlPlane.heartbeats.isEmpty());
    first.close();
    assertThat(controlPlane.registrations.get()).isEqualTo(1);

    daemon().start();
    Thread.sleep(500);
    assertThat(controlPlane.registrations.get()).isEqualTo(1);

    controlPlane.validTokens.clear();
    await("nuevo registro", () -> controlPlane.registrations.get() == 2);
    UUID agentRunId = UUID.randomUUID();
    controlPlane.commands.add(TestAgents.start(agentRunId, repo.toString(), null));
    await(
        "agent.process.exited",
        () -> controlPlane.typesOf(agentRunId).contains("agent.process.exited"));
  }

  @Test
  void aRedeliveredCommandRunsOnce() throws Exception {
    controlPlane.dropAcks.set(true);
    UUID agentRunId = UUID.randomUUID();
    RunnerCommand start = TestAgents.start(agentRunId, repo.toString(), null);
    controlPlane.commands.add(start);
    controlPlane.commands.add(start);

    daemon().start();
    await(
        "agent.process.exited",
        () -> controlPlane.typesOf(agentRunId).contains("agent.process.exited"));
    Thread.sleep(500);

    assertThat(controlPlane.typesOf(agentRunId))
        .filteredOn("agent.workspace.ready"::equals)
        .hasSize(1);
  }

  @Test
  void closesInvocationsLeftRunningByAPreviousRunnerProcess() throws Exception {
    UUID agentRunId = UUID.randomUUID();
    Process orphan = new ProcessBuilder("sleep", "60").start();
    ProcessHandle handle = orphan.toHandle();
    journal.firstDelivery(UUID.randomUUID(), agentRunId, "START");
    journal.processStarted(
        agentRunId, handle.pid(), handle.info().startInstant().orElse(Instant.now()));

    daemon().start();
    await(
        "agent.process.exited",
        () -> controlPlane.typesOf(agentRunId).contains("agent.process.exited"));

    assertThat(orphan.waitFor(5, TimeUnit.SECONDS)).isTrue();
    assertThat(controlPlane.eventsOf(agentRunId).getLast().get("payload").get("error").asString())
        .contains("reinició");
  }

  @Test
  void closesVerificationsLeftRunningByAPreviousRunnerProcess() throws Exception {
    UUID agentRunId = UUID.randomUUID();
    UUID verificationRunId = UUID.randomUUID();
    Process orphan = new ProcessBuilder("sleep", "60").start();
    ProcessHandle handle = orphan.toHandle();
    journal.firstVerification(verificationRunId, agentRunId);
    journal.processStarted(
        verificationRunId, handle.pid(), handle.info().startInstant().orElse(Instant.now()));

    daemon().start();
    await(
        "agent.verification.completed",
        () -> controlPlane.typesOf(agentRunId).contains("agent.verification.completed"));

    assertThat(orphan.waitFor(5, TimeUnit.SECONDS)).isTrue();
    JsonNode payload = controlPlane.eventsOf(agentRunId).getLast().get("payload");
    assertThat(payload.get("verificationRunId").asString()).isEqualTo(verificationRunId.toString());
    assertThat(payload.get("error").asString()).contains("reinició");
    assertThat(journal.unfinishedVerifications()).isEmpty();
    assertThat(journal.processes()).isEmpty();
  }

  /** Control plane mínimo: implementa el protocolo del runner en memoria. */
  static final class StubControlPlane {

    final AtomicInteger registrations = new AtomicInteger();
    final AtomicInteger failEventsTimes = new AtomicInteger();
    final AtomicInteger failArtifactsTimes = new AtomicInteger();
    final List<String> artifactUploads = new CopyOnWriteArrayList<>();
    final java.util.concurrent.atomic.AtomicBoolean dropAcks =
        new java.util.concurrent.atomic.AtomicBoolean();
    final Set<String> validTokens = ConcurrentHashMap.newKeySet();
    final BlockingQueue<RunnerCommand> commands = new LinkedBlockingQueue<>();
    final List<UUID> acks = new CopyOnWriteArrayList<>();
    final List<JsonNode> heartbeats = new CopyOnWriteArrayList<>();
    private final Map<UUID, JsonNode> events = new ConcurrentHashMap<>();
    private final List<UUID> order = new CopyOnWriteArrayList<>();
    private final ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor();
    private HttpServer server;

    void start() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(handlers);
      server.createContext("/api/runner/", this::handle);
      server.start();
    }

    void stop() {
      server.stop(0);
      handlers.close();
    }

    URI uri() {
      return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    List<JsonNode> eventsOf(UUID agentRunId) {
      return order.stream()
          .map(events::get)
          .filter(e -> e.get("agentRunId").asString().equals(agentRunId.toString()))
          .sorted(java.util.Comparator.comparingLong(e -> e.get("seq").asLong()))
          .toList();
    }

    List<String> typesOf(UUID agentRunId) {
      return eventsOf(agentRunId).stream()
          .map(e -> AgentEventType.valueOf(e.get("type").asString()).wireName())
          .toList();
    }

    private void handle(HttpExchange exchange) throws IOException {
      try (exchange) {
        String path = exchange.getRequestURI().getPath();
        byte[] body = exchange.getRequestBody().readAllBytes();
        if (path.equals("/api/runner/register")) {
          registrations.incrementAndGet();
          String token = "token-" + UUID.randomUUID();
          validTokens.add(token);
          respond(exchange, 200, Map.of("runnerId", UUID.randomUUID().toString(), "token", token));
          return;
        }
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !validTokens.contains(auth.substring("Bearer ".length()))) {
          respond(exchange, 401, Map.of("detail", "token inválido"));
          return;
        }
        if (path.equals("/api/runner/heartbeat")) {
          heartbeats.add(JSON.readTree(body));
          respond(exchange, 204, null);
        } else if (path.equals("/api/runner/commands")) {
          List<RunnerCommand> batch = new java.util.ArrayList<>();
          RunnerCommand first = commands.poll(200, TimeUnit.MILLISECONDS);
          if (first != null) {
            batch.add(first);
            commands.drainTo(batch);
          }
          respond(exchange, 200, batch);
        } else if (path.endsWith("/ack")) {
          if (!dropAcks.get()) {
            acks.add(UUID.fromString(path.split("/")[4]));
          }
          respond(exchange, 204, null);
        } else if (path.equals("/api/runner/events")) {
          if (failEventsTimes.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            respond(exchange, 503, Map.of("detail", "no disponible"));
            return;
          }
          int accepted = 0;
          for (JsonNode event : JSON.readTree(body).get("events")) {
            UUID id = UUID.fromString(event.get("eventId").asString());
            if (events.putIfAbsent(id, event) == null) {
              order.add(id);
              accepted++;
            }
          }
          respond(
              exchange, 200, Map.of("accepted", accepted, "duplicates", 0, "rejected", List.of()));
        } else if (path.equals("/api/runner/artifacts")) {
          if (failArtifactsTimes.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            respond(exchange, 503, Map.of("detail", "no disponible"));
            return;
          }
          String metadata = exchange.getRequestHeaders().getFirst("X-Artifact-Metadata");
          artifactUploads.add(
              new String(java.util.Base64.getUrlDecoder().decode(metadata), StandardCharsets.UTF_8)
                  + "\n"
                  + new String(body, StandardCharsets.UTF_8));
          respond(exchange, 200, Map.of("id", UUID.randomUUID().toString(), "duplicate", false));
        } else {
          respond(exchange, 404, Map.of("detail", path));
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    private static void respond(HttpExchange exchange, int status, Object body) throws IOException {
      if (body == null) {
        exchange.sendResponseHeaders(status, -1);
        return;
      }
      byte[] bytes = JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
    }
  }
}
