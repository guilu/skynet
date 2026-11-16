package dev.skynet.runner.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.skynet.protocol.NormalizedEvent;
import dev.skynet.protocol.runner.AgentLimits;
import dev.skynet.protocol.runner.ChatToken;
import dev.skynet.protocol.runner.ChatTurn;
import dev.skynet.protocol.runner.ResumeFrom;
import dev.skynet.protocol.runner.RunnerCommand;
import dev.skynet.protocol.runner.StartAgent;
import dev.skynet.runner.TestAgents;
import dev.skynet.runner.chat.ChatSessions;
import dev.skynet.runner.journal.Journal;
import dev.skynet.runner.supervisor.ProcessSupervisor;
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
 * Un turno del chat con Skyny de punta a punta (CH-C): sin worktree, en la carpeta de su
 * conversación, y fake-claude consulta el MCP de Skynet con el token del turno contra un control
 * plane de mentira.
 */
class AgentChatTest {

  private static final ObjectMapper JSON = JsonMapper.builder().build();
  private static final String TOKEN = "skc_prueba";

  @TempDir Path dir;

  private Journal journal;
  private AgentExecutor executor;
  private HttpServer controlPlane;
  private final List<String> requests = new CopyOnWriteArrayList<>();
  private final List<UUID> issued = new CopyOnWriteArrayList<>();

  @BeforeEach
  void setUp() throws Exception {
    journal = Journal.open(dir.resolve("journal.db"));
    controlPlane = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    controlPlane.createContext(
        "/api/",
        exchange -> {
          requests.add(
              exchange.getRequestURI().getPath()
                  + " "
                  + exchange.getRequestHeaders().getFirst("Authorization"));
          byte[] out = "{\"enCurso\":2}".getBytes(StandardCharsets.UTF_8);
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

  private AgentExecutor executor(Map<String, String> extraEnv, ChatSessions.TokenSource tokens) {
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
            new ArtifactSpool(journal, dir.resolve("artifacts"), () -> {}));
    if (tokens != null) {
      executor.chats(new ChatSessions(tokens, url, runner, dir.resolve("chat")));
    }
    return executor;
  }

  private ChatToken issue(UUID agentRunId) {
    issued.add(agentRunId);
    return new ChatToken(TOKEN, Instant.now().plusSeconds(1800));
  }

  @Test
  void aTurnAsksSkynetThroughItsMcpInTheFolderOfItsConversationAndTheNextResumesThere()
      throws Exception {
    Path log = dir.resolve("mcp.log");
    UUID conversation = UUID.randomUUID();
    UUID session = UUID.randomUUID();
    UUID first = UUID.randomUUID();
    executor(
            Map.of(
                "FAKE_MCP_CALLS",
                "[{\"tool\": \"estado\", \"arguments\": {}}]",
                "FAKE_MCP_LOG",
                log.toString(),
                "FAKE_MCP_SERVER",
                ChatSessions.SERVER),
            this::issue)
        .start(turn(first, conversation, session, null));

    assertThat(awaitExit(first).payload()).containsEntry("exitCode", 0);
    assertThat(issued).containsExactly(first);
    assertThat(requests).containsExactly("/api/dashboard Bearer " + TOKEN);
    List<JsonNode> responses = Files.readAllLines(log).stream().map(JSON::readTree).toList();
    assertThat(responses.getLast().path("result").path("isError").asBoolean(true)).isFalse();

    // Sin worktree, sin artefactos y sin evento de worktree: solo la carpeta de la conversación.
    assertThat(dir.resolve("chat").resolve(conversation.toString())).isDirectory();
    assertThat(dir.resolve("workspaces")).doesNotExist();
    assertThat(events(first)).doesNotContain("agent.workspace.ready");
    assertThat(journal.pendingArtifacts(100)).isEmpty();

    // El token solo estuvo en su fichero, que se borra al terminar.
    Path logs = dir.resolve("logs");
    assertThat(logs.resolve(first + ".memory-token")).doesNotExist();
    assertThat(Files.readString(logs.resolve(first + ".mcp.json")))
        .contains("skynet-mcp")
        .contains("\"" + ChatSessions.SERVER + "\"")
        .doesNotContain(TOKEN);

    // fake-claude, como el CLI real, solo encuentra la sesión desde la misma carpeta.
    UUID second = UUID.randomUUID();
    executor.start(
        turn(
            second,
            conversation,
            UUID.randomUUID(),
            new ResumeFrom(session.toString(), false, "chat", "chat")));
    assertThat(awaitExit(second).payload()).containsEntry("exitCode", 0);
    assertThat(issued).containsExactly(first, second);
  }

  @Test
  void withoutChatOrWithoutItsTokenTheTurnDoesNotStart() throws Exception {
    UUID unattended = UUID.randomUUID();
    executor(Map.of(), null).start(turn(unattended, UUID.randomUUID(), UUID.randomUUID(), null));
    assertThat(awaitExit(unattended).payload())
        .containsEntry("failure", "start")
        .containsEntry("error", "Este runner no atiende el chat con Skyny");
    executor.close();

    UUID failing = UUID.randomUUID();
    executor(
            Map.of(),
            agentRunId -> {
              issued.add(agentRunId);
              throw new IOException("control plane caído");
            })
        .start(turn(failing, UUID.randomUUID(), UUID.randomUUID(), null));
    assertThat(awaitExit(failing).payload())
        .containsEntry("failure", "start")
        .containsEntry("error", "No se pudo pedir el token del chat: control plane caído");
    assertThat(dir.resolve("logs").resolve(failing + ".memory-token")).doesNotExist();
    assertThat(requests).isEmpty();
  }

  private RunnerCommand turn(UUID agentRunId, UUID conversation, UUID session, ResumeFrom resume) {
    StartAgent start =
        withFakeMcp(
            StartAgent.chatTurn(
                new ChatTurn(conversation),
                session,
                "¿Qué hay en curso?",
                List.of("mcp__" + ChatSessions.SERVER),
                "haiku",
                AgentLimits.none(),
                resume));
    return resume == null
        ? RunnerCommand.start(UUID.randomUUID(), agentRunId, Instant.now(), start)
        : RunnerCommand.resume(UUID.randomUUID(), agentRunId, Instant.now(), start);
  }

  /**
   * El turno con las variables del guion de fake-claude: el control plane no le pide ninguna
   * adicional y el agente solo recibe las básicas y las de su proveedor.
   */
  private static StartAgent withFakeMcp(StartAgent t) {
    return new StartAgent(
        t.workflowRunId(),
        t.workItemKey(),
        t.repositoryPath(),
        t.baseBranch(),
        t.sessionId(),
        t.prompt(),
        t.allowedTools(),
        t.permissionMode(),
        t.model(),
        t.limits(),
        t.resume(),
        List.of("FAKE_CLAUDE_FIXTURE", "FAKE_MCP_CALLS", "FAKE_MCP_LOG", "FAKE_MCP_SERVER"),
        t.workspace(),
        t.command(),
        t.outputSchema(),
        t.provider(),
        t.memory(),
        t.push(),
        t.protectedPaths(),
        t.risk(),
        t.chat());
  }

  private List<String> events(UUID agentRunId) {
    return journal.pending(1000).stream()
        .filter(e -> e.agentRunId().equals(agentRunId))
        .map(e -> e.type().wireName())
        .toList();
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
