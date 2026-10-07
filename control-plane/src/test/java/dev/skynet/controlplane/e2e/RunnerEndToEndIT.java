package dev.skynet.controlplane.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.skynet.controlplane.support.IntegrationTest;
import dev.skynet.runner.RunnerConfig;
import dev.skynet.runner.RunnerDaemon;
import dev.skynet.runner.agent.AgentExecutor;
import dev.skynet.runner.agent.ArtifactSender;
import dev.skynet.runner.agent.ArtifactSpool;
import dev.skynet.runner.agent.EventSender;
import dev.skynet.runner.journal.Journal;
import dev.skynet.runner.provider.claude.ClaudeCodeProvider;
import dev.skynet.runner.supervisor.ProcessSupervisor;
import dev.skynet.runner.transport.ControlPlaneClient;
import dev.skynet.runner.workspace.WorkspaceManager;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Control plane real, runner real en el mismo proceso y fake-claude como agente: se lanza desde la
 * API y se comprueba lo que queda en la base de datos y lo que llega por SSE.
 */
class RunnerEndToEndIT extends IntegrationTest {

  private static final ObjectMapper JSON = JsonMapper.builder().build();

  @TempDir Path dir;

  private final List<AutoCloseable> closeables = new ArrayList<>();
  private Path repo;
  private Journal journal;
  private TcpProxy proxy;
  private final Map<String, String> extraEnv = new HashMap<>();

  @BeforeEach
  void setUp() throws Exception {
    repo = createRepository(dir.resolve("repo"));
    proxy = new TcpProxy(port);
    journal = Journal.open(dir.resolve("runner/journal.db"));
  }

  @AfterEach
  void tearDown() throws Exception {
    for (AutoCloseable closeable : closeables.reversed()) {
      closeable.close();
    }
    journal.close();
    proxy.close();
  }

  @Test
  void aLaunchedAgentRunsOnTheRunnerAndEveryEventReachesTheDatabaseAndSse() throws Exception {
    startRunner("02-tools", 0);
    Launched run = launch("Arregla add()");
    List<String> streamed = new CopyOnWriteArrayList<>();
    Thread sse = Thread.ofVirtual().start(() -> readStream(run.runId(), streamed));

    await("agente completado", () -> "COMPLETED".equals(agentStatus(run)));
    await(
        "fin de la ejecución por SSE",
        () -> streamed.contains("workflow.status.changed:SUCCEEDED"));
    sse.interrupt();

    JsonNode agent = agent(run);
    assertThat(agent.path("providerSessionId").asString()).isNotBlank();
    assertThat(agent.path("exitCode").asInt()).isZero();
    assertThat(agent.path("costUsd").decimalValue()).isPositive();
    assertThat(get("/api/workflow-runs/" + run.runId()).path("status").asString())
        .isEqualTo("SUCCEEDED");

    List<String> agentEvents = agentEventTypes(run);
    assertThat(agentEvents)
        .startsWith("agent.workspace.ready", "agent.session.started")
        .contains("agent.tool.started", "agent.tool.completed", "agent.file.changed")
        .endsWith("agent.result", "agent.process.exited");
    assertThat(runnerSeqs(run))
        .containsExactlyElementsOf(LongStream.rangeClosed(1, agentEvents.size()).boxed().toList());
    assertThat(streamed.stream().map(s -> s.split(":")[0]).filter(t -> t.startsWith("agent.")))
        .containsAll(agentEvents);

    JsonNode ready = eventPayloads(run, "agent.workspace.ready").getFirst();
    Path worktree = Path.of(ready.path("path").asString());
    assertThat(worktree.resolve("calc.py")).exists();
    assertThat(git(worktree, "rev-parse", "--abbrev-ref", "HEAD"))
        .isEqualTo(ready.path("branch").asString());
  }

  @Test
  void aNetworkCutLosesNoEvents() throws Exception {
    startRunner("02-tools", 40);
    Launched run = launch("Arregla add()");

    await("agente activo", () -> List.of("THINKING", "EXECUTING").contains(agentStatus(run)));
    proxy.cut();
    Thread.sleep(1500);
    proxy.restore();

    await("agente completado", () -> "COMPLETED".equals(agentStatus(run)));
    await("journal vacío", () -> journal.pending(1).isEmpty());

    List<String> types = agentEventTypes(run);
    assertThat(runnerSeqs(run))
        .containsExactlyElementsOf(LongStream.rangeClosed(1, types.size()).boxed().toList());
    assertThat(types).containsOnlyOnce("agent.session.started", "agent.process.exited");
    assertThat(types.getLast()).isEqualTo("agent.process.exited");
  }

  @Test
  void cancellingFromTheApiKillsTheWholeProcessTree() throws Exception {
    startRunner("07-cancelled", 0);
    Launched run = launch("Algo largo");

    await("proceso en marcha", () -> !journal.processes().isEmpty());
    long pid = journal.processes().getFirst().pid();
    await("agente activo", () -> List.of("THINKING", "EXECUTING").contains(agentStatus(run)));
    List<ProcessHandle> tree = new ArrayList<>();
    ProcessHandle.of(pid).ifPresent(tree::add);
    tree.addAll(ProcessHandle.of(pid).map(h -> h.descendants().toList()).orElse(List.of()));
    assertThat(tree).isNotEmpty();

    post("/api/agent-runs/" + run.agentId() + "/cancel", Map.of());

    await("agente cancelado", () -> "CANCELLED".equals(agentStatus(run)));
    assertThat(tree).isNotEmpty().noneMatch(ProcessHandle::isAlive);
    assertThat(get("/api/workflow-runs/" + run.runId()).path("status").asString())
        .isEqualTo("CANCELLED");
    assertThat(agentEventTypes(run).getLast()).isEqualTo("agent.process.exited");
  }

  @Test
  void aMessageResumesTheSessionInItsWorktreeAndAForkBranchesIt() throws Exception {
    startRunner("02-tools", 0);
    Launched run = launch("Arregla add()");
    await("agente completado", () -> "COMPLETED".equals(agentStatus(run)));
    JsonNode first = agent(run);

    Launched resumed =
        launched(
            post(
                "/api/agent-runs/" + run.agentId() + "/messages", Map.of("text", "Añade un test")));
    await("reanudación completada", () -> "COMPLETED".equals(agentStatus(resumed)));
    JsonNode second = agent(resumed);
    assertThat(second.path("kind").asString()).isEqualTo("RESUME");
    assertThat(second.path("providerSessionId").asString())
        .isEqualTo(first.path("providerSessionId").asString());
    assertThat(second.path("workspace").path("path").asString())
        .isEqualTo(first.path("workspace").path("path").asString());
    // 03-resume acumula 0,081472 sobre los 0,0573992 de 02-tools.
    assertThat(second.path("costUsd").decimalValue())
        .isCloseTo(new BigDecimal("0.0240728"), within(new BigDecimal("0.000001")));
    assertThat(get("/api/agent-runs/" + resumed.agentId() + "/conversation").path("turns"))
        .hasSize(2);

    Launched forked =
        launched(
            post("/api/agent-runs/" + run.agentId() + "/fork", Map.of("text", "Prueba otra cosa")));
    await("fork completado", () -> "COMPLETED".equals(agentStatus(forked)));
    JsonNode third = agent(forked);
    assertThat(third.path("kind").asString()).isEqualTo("FORK");
    assertThat(third.path("providerSessionId").asString())
        .isNotEqualTo(first.path("providerSessionId").asString());
    Path forkTree = Path.of(third.path("workspace").path("path").asString());
    assertThat(forkTree.toString()).isNotEqualTo(first.path("workspace").path("path").asString());
    assertThat(forkTree.resolve("calc.py")).exists();
    // Bifurca desde la última invocación de la sesión (la reanudación): 0,0866522 − 0,081472.
    assertThat(third.path("costUsd").decimalValue())
        .isCloseTo(new BigDecimal("0.0051802"), within(new BigDecimal("0.000001")));
  }

  @Test
  void theRunnerUploadsTheArtifactsAndVerifiesTheWorktreeIndependently() throws Exception {
    extraEnv.put("FAKE_CLAUDE_APPLY", "1");
    startRunner("02-tools", 0);
    // Pasa solo si el agente arregló add() de verdad, y deja un informe JUnit.
    String command =
        """
        mkdir -p build/test-results/test
        if grep -q 'a + b' calc.py; then r='<testcase name="add"/>'
        else r='<testcase name="add"><failure message="resta"/></testcase>'; fi
        echo "<testsuite name='calc'>$r</testsuite>" >build/test-results/test/TEST-calc.xml
        echo verificado
        """;
    Launched run = launch("Arregla add()", command);

    await("agente completado", () -> "COMPLETED".equals(agentStatus(run)));
    await(
        "verificación automática",
        () -> "PASSED".equals(verifications(run).path(0).path("status").asString()));
    JsonNode auto = verifications(run).get(0);
    assertThat(auto.path("trigger").asString()).isEqualTo("AUTO");
    assertThat(auto.path("exitCode").asInt()).isZero();
    assertThat(auto.path("tests").path("total").asInt()).isEqualTo(1);
    assertThat(auto.path("tests").path("failed").asInt()).isZero();

    List<String> expected =
        List.of(
            "PROMPT", "LOG", "RESULT", "GIT_CHANGES", "DIFF", "VERIFICATION_LOG", "TEST_REPORT");
    await("artefactos", () -> artifactTypes(run).containsAll(expected));
    assertThat(artifactTypes(run)).hasSameSizeAs(expected);
    JsonNode changes = artifact(run, "GIT_CHANGES");
    assertThat(changes.path("metadata").path("commits").asInt()).isEqualTo(1);
    assertThat(content(artifact(run, "DIFF"))).contains("+    return a + b");
    assertThat(content(artifact(run, "VERIFICATION_LOG"))).contains("verificado");
    assertThat(artifact(run, "VERIFICATION_LOG").path("verificationRunId").asString())
        .isEqualTo(auto.path("id").asString());

    JsonNode manual = post("/api/agent-runs/" + run.agentId() + "/verifications", Map.of());
    assertThat(manual.path("trigger").asString()).isEqualTo("MANUAL");
    await(
        "verificación manual",
        () -> "PASSED".equals(verifications(run).path(0).path("status").asString()));
    assertThat(verifications(run)).hasSize(2);
    assertThat(agentStatus(run)).isEqualTo("COMPLETED");
    assertThat(get("/api/workflow-runs/" + run.runId()).path("status").asString())
        .isEqualTo("SUCCEEDED");
  }

  private JsonNode verifications(Launched run) {
    return get("/api/agent-runs/" + run.agentId() + "/verifications");
  }

  private List<String> artifactTypes(Launched run) {
    List<String> types = new ArrayList<>();
    get("/api/agent-runs/" + run.agentId() + "/artifacts")
        .forEach(a -> types.add(a.path("type").asString()));
    return types;
  }

  private JsonNode artifact(Launched run, String type) {
    for (JsonNode a : get("/api/agent-runs/" + run.agentId() + "/artifacts")) {
      if (a.path("type").asString().equals(type)) {
        return a;
      }
    }
    throw new AssertionError("Sin artefacto " + type);
  }

  private String content(JsonNode artifact) {
    return http.get()
        .uri("/api/artifacts/" + artifact.path("id").asString() + "/content")
        .retrieve()
        .body(String.class);
  }

  // --- runner ---

  private void startRunner(String fixture, long delayMs) throws Exception {
    RunnerConfig config =
        new RunnerConfig(
            URI.create("http://127.0.0.1:" + proxy.port()),
            "e2e",
            RUNNER_REGISTRATION_TOKEN,
            2,
            dir.resolve("runner"),
            null,
            List.of(),
            Duration.ofSeconds(2),
            Duration.ofMillis(500),
            Duration.ofSeconds(2),
            null);
    Map<String, String> env = new HashMap<>(System.getenv());
    env.put("JAVA_HOME", System.getProperty("java.home"));
    env.put("FAKE_CLAUDE_FIXTURE", fixture);
    env.put("FAKE_CLAUDE_DELAY_MS", Long.toString(delayMs));
    // fake-claude guarda las sesiones como el CLI y exige encontrarlas al reanudar.
    env.put("CLAUDE_CONFIG_DIR", dir.resolve("claude").toString());
    env.putAll(extraEnv);
    ClaudeCodeProvider provider =
        new ClaudeCodeProvider(
            Path.of(System.getProperty("skynet.fakeClaude")).toAbsolutePath().toString(),
            List.of(
                "JAVA_HOME", "FAKE_CLAUDE_FIXTURE", "FAKE_CLAUDE_DELAY_MS", "FAKE_CLAUDE_APPLY"));
    ControlPlaneClient client = new ControlPlaneClient(config.controlPlane());
    EventSender sender =
        new EventSender(journal, client, Duration.ofMillis(100), Duration.ofMillis(200));
    ArtifactSender artifactSender =
        new ArtifactSender(journal, client, Duration.ofMillis(100), Duration.ofMillis(200));
    ProcessSupervisor supervisor = new ProcessSupervisor();
    AgentExecutor executor =
        new AgentExecutor(
            journal,
            supervisor,
            new WorkspaceManager(config.workspacesDir()),
            provider,
            env,
            config.logsDir(),
            config.cancelGrace(),
            sender::wakeUp,
            new ArtifactSpool(journal, config.artifactsDir(), artifactSender::wakeUp));
    RunnerDaemon daemon =
        new RunnerDaemon(
            config, client, journal, executor, sender, artifactSender, supervisor, "fake");
    closeables.add(sender);
    closeables.add(artifactSender);
    closeables.add(executor);
    closeables.add(daemon);
    daemon.start();
  }

  // --- API ---

  private record Launched(UUID runId, UUID agentId) {}

  private Launched launch(String prompt) {
    return launch(prompt, null);
  }

  private Launched launch(String prompt, String validationCommand) {
    Map<String, Object> repository = new HashMap<>();
    repository.put("name", "demo");
    repository.put("localPath", repo.toString());
    repository.put("validationCommand", validationCommand);
    String projectId =
        post("/api/projects", Map.of("key", "E2E", "name", "E2E")).path("id").asString();
    String repositoryId =
        post("/api/projects/" + projectId + "/repositories", repository).path("id").asString();
    String workItemId =
        post("/api/projects/" + projectId + "/work-items", Map.of("title", "T", "type", "BUG"))
            .path("id")
            .asString();
    return launched(
        post(
            "/api/work-items/" + workItemId + "/runs",
            Map.of("repositoryId", repositoryId, "prompt", prompt)));
  }

  private static Launched launched(JsonNode run) {
    return new Launched(
        UUID.fromString(run.path("id").asString()),
        UUID.fromString(run.path("stages").get(0).path("agents").get(0).path("id").asString()));
  }

  private JsonNode agent(Launched run) {
    return get("/api/agent-runs/" + run.agentId()).path("agent");
  }

  private String agentStatus(Launched run) {
    return agent(run).path("status").asString();
  }

  private List<JsonNode> agentEvents(Launched run) {
    List<JsonNode> events = new ArrayList<>();
    get("/api/events?aggregateId=" + run.agentId() + "&limit=500")
        .forEach(
            e -> {
              if (e.path("type").asString().startsWith("agent.")
                  && e.path("payload").has("runnerSeq")) {
                events.add(e);
              }
            });
    return events;
  }

  private List<String> agentEventTypes(Launched run) {
    return agentEvents(run).stream().map(e -> e.path("type").asString()).toList();
  }

  private List<Long> runnerSeqs(Launched run) {
    return agentEvents(run).stream()
        .map(e -> e.path("payload").path("runnerSeq").asLong())
        .toList();
  }

  private List<JsonNode> eventPayloads(Launched run, String type) {
    return agentEvents(run).stream()
        .filter(e -> e.path("type").asString().equals(type))
        .map(e -> e.path("payload"))
        .toList();
  }

  private JsonNode post(String path, Object body) {
    return http.post().uri(path).body(body).retrieve().body(JsonNode.class);
  }

  private JsonNode get(String path) {
    return http.get().uri(path).retrieve().body(JsonNode.class);
  }

  /** Lee el stream SSE de una ejecución y apunta {@code tipo:estado} de cada evento. */
  private void readStream(UUID runId, List<String> out) {
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://localhost:" + port + "/api/events/stream?workflowRunId=" + runId))
            .header("Accept", "text/event-stream")
            .header("Authorization", ADMIN_BASIC_AUTH)
            .build();
    try (HttpClient client = HttpClient.newHttpClient()) {
      HttpResponse<java.io.InputStream> response =
          client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (line.startsWith("data:")) {
            JsonNode event = JSON.readTree(line.substring(5));
            out.add(
                event.path("type").asString()
                    + ":"
                    + event.path("payload").path("status").asString(""));
          }
        }
      }
    } catch (IOException e) {
      // Stream cerrado al terminar el test.
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  // --- utilidades ---

  private static void await(String what, BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("Sin llegar a: " + what);
      }
      Thread.sleep(100);
    }
  }

  private static Path createRepository(Path dir) throws IOException, InterruptedException {
    Files.createDirectories(dir);
    git(dir, "init", "-q", "-b", "main");
    Files.writeString(dir.resolve("calc.py"), "def add(a, b):\n    return a - b\n");
    git(dir, "add", ".");
    git(dir, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "init");
    return dir;
  }

  private static String git(Path dir, String... args) throws IOException, InterruptedException {
    List<String> command = new ArrayList<>(List.of("git", "-C", dir.toString()));
    command.addAll(List.of(args));
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (process.waitFor() != 0) {
      throw new IOException(String.join(" ", command) + ": " + output);
    }
    return output.trim();
  }
}
