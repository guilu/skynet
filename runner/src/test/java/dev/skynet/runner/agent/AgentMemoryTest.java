package dev.skynet.runner.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.skynet.protocol.NormalizedEvent;
import dev.skynet.protocol.runner.AgentLimits;
import dev.skynet.protocol.runner.MemoryToken;
import dev.skynet.protocol.runner.RunnerCommand;
import dev.skynet.protocol.runner.StartAgent;
import dev.skynet.runner.TestAgents;
import dev.skynet.runner.TestRepos;
import dev.skynet.runner.journal.Journal;
import dev.skynet.runner.memory.MemorySessions;
import dev.skynet.runner.supervisor.ProcessSupervisor;
import dev.skynet.runner.verify.DeclaredFiles;
import dev.skynet.runner.workspace.WorkspaceManager;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * La memoria de una invocación de punta a punta (ME-B): fake-claude lanza el puente MCP del runner,
 * que llama a un control plane de mentira con el token de la invocación.
 */
class AgentMemoryTest {

  private static final ObjectMapper JSON = JsonMapper.builder().build();
  private static final String TOKEN = "skm_prueba";

  @TempDir Path dir;

  private Path repo;
  private Journal journal;
  private AgentExecutor executor;
  private HttpServer controlPlane;
  private final List<Request> requests = new CopyOnWriteArrayList<>();
  private final List<UUID> issued = new CopyOnWriteArrayList<>();

  private record Request(String path, String authorization, JsonNode body) {}

  @BeforeEach
  void setUp() throws Exception {
    repo = TestRepos.create(dir.resolve("repo"));
    journal = Journal.open(dir.resolve("journal.db"));
    controlPlane = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    controlPlane.createContext(
        "/api/runner/memory/",
        exchange -> {
          String path = exchange.getRequestURI().getPath();
          byte[] in = exchange.getRequestBody().readAllBytes();
          requests.add(
              new Request(
                  path,
                  exchange.getRequestHeaders().getFirst("Authorization"),
                  JSON.readTree(new String(in, StandardCharsets.UTF_8))));
          String body =
              switch (path) {
                case "/api/runner/memory/context" -> "{\"text\":\"Memoria de prueba\"}";
                case "/api/runner/memory/save" -> "{\"id\":\"obs-1\",\"created\":true}";
                default -> "{\"results\":[],\"total\":0}";
              };
          byte[] out = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, out.length);
          exchange.getResponseBody().write(out);
          exchange.close();
        });
    controlPlane.start();
  }

  @AfterEach
  void tearDown() {
    if (executor != null) {
      executor.close();
    }
    journal.close();
    controlPlane.stop(0);
  }

  private AgentExecutor executor(Map<String, String> extraEnv, MemorySessions.TokenSource tokens) {
    Map<String, String> env =
        new HashMap<>(TestAgents.fakeClaudeEnv("02-tools", dir.resolve("claude")));
    env.putAll(extraEnv);
    URI url = URI.create("http://127.0.0.1:" + controlPlane.getAddress().getPort());
    List<String> runner =
        List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp",
            System.getProperty("skynet.runnerClasspath"),
            "dev.skynet.runner.RunnerMain");
    executor =
        new AgentExecutor(
            journal,
            new ProcessSupervisor(),
            new WorkspaceManager(dir.resolve("workspaces")),
            TestAgents.fakeProviders(env),
            env,
            dir.resolve("logs"),
            Duration.ofSeconds(2),
            () -> {},
            new ArtifactSpool(journal, dir.resolve("artifacts"), () -> {}),
            DeclaredFiles.Limits.DEFAULT,
            new MemorySessions(tokens, url, runner));
    return executor;
  }

  private MemoryToken issue(UUID agentRunId) {
    issued.add(agentRunId);
    return new MemoryToken(TOKEN, "WRITE");
  }

  @Test
  void theAgentUsesTheMemoryToolsWithTheTokenOfItsInvocation() throws Exception {
    Path log = dir.resolve("mcp.log");
    String calls =
        """
        [{"tool": "mem_context", "arguments": {}},
         {"tool": "mem_save", "arguments": {"kind": "DECISION", "title": "Squash",
          "what": "Las PRs van con squash", "topic_key": "git/merge"}}]
        """;
    UUID id = UUID.randomUUID();
    executor(Map.of("FAKE_MCP_CALLS", calls, "FAKE_MCP_LOG", log.toString()), this::issue)
        .start(start(id, StartAgent.MEMORY_WRITE));

    assertThat(awaitExit(id).payload()).containsEntry("exitCode", 0);
    assertThat(issued).containsExactly(id);

    List<JsonNode> responses = Files.readAllLines(log).stream().map(JSON::readTree).toList();
    assertThat(responses).hasSize(3);
    assertThat(responses.get(0).path("result").path("tools"))
        .extracting(t -> t.path("name").asString())
        .containsExactly("mem_context", "mem_search", "mem_get", "mem_save", "mem_session_summary");
    assertThat(responses.get(1).path("result").path("content").get(0).path("text").asString())
        .isEqualTo("Memoria de prueba");
    assertThat(responses.get(2).path("result").path("isError").asBoolean(true)).isFalse();

    assertThat(requests)
        .extracting(Request::path)
        .containsExactly("/api/runner/memory/context", "/api/runner/memory/save");
    assertThat(requests).extracting(Request::authorization).containsOnly("Bearer " + TOKEN);
    assertThat(requests.get(1).body().path("topicKey").asString()).isEqualTo("git/merge");

    // El token solo estuvo en su fichero, que se borra al terminar; nunca en la configuración.
    Path logs = dir.resolve("logs");
    assertThat(logs.resolve(id + ".memory-token")).doesNotExist();
    assertThat(Files.readString(logs.resolve(id + ".mcp.json")))
        .contains("memory-mcp")
        .doesNotContain(TOKEN);
  }

  @Test
  void withoutATokenOrWithoutMemoryTheAgentRunsWithoutIt() throws Exception {
    UUID failing = UUID.randomUUID();
    executor(
            Map.of(),
            agentRunId -> {
              issued.add(agentRunId);
              throw new IOException("control plane caído");
            })
        .start(start(failing, StartAgent.MEMORY_WRITE));
    assertThat(awaitExit(failing).payload()).containsEntry("exitCode", 0);
    assertThat(issued).containsExactly(failing);
    assertThat(dir.resolve("logs").resolve(failing + ".mcp.json")).doesNotExist();
    assertThat(dir.resolve("logs").resolve(failing + ".memory-token")).doesNotExist();

    UUID without = UUID.randomUUID();
    executor.start(start(without, null));
    assertThat(awaitExit(without).payload()).containsEntry("exitCode", 0);
    assertThat(issued).containsExactly(failing);
    assertThat(dir.resolve("logs").resolve(without + ".mcp.json")).doesNotExist();
  }

  private RunnerCommand start(UUID agentRunId, String memory) {
    return RunnerCommand.start(
        UUID.randomUUID(),
        agentRunId,
        Instant.now(),
        new StartAgent(
            UUID.randomUUID(),
            "TKM-1",
            repo.toString(),
            "main",
            UUID.randomUUID(),
            "Arregla add",
            List.of("Read", "Edit"),
            "dontAsk",
            null,
            AgentLimits.none(),
            null,
            null,
            null,
            null,
            null,
            null,
            memory));
  }

  private NormalizedEvent awaitExit(UUID agentRunId) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    while (System.nanoTime() < deadline) {
      if (journal.isFinished(agentRunId)) {
        return journal.pending(1000).stream()
            .filter(e -> e.agentRunId().equals(agentRunId))
            .toList()
            .getLast();
      }
      Thread.sleep(50);
    }
    throw new AssertionError("La invocación no terminó");
  }
}
