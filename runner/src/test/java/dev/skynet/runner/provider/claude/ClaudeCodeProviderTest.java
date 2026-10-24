package dev.skynet.runner.provider.claude;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skynet.protocol.runner.AgentLimits;
import dev.skynet.protocol.runner.ResumeFrom;
import dev.skynet.protocol.runner.StartAgent;
import dev.skynet.runner.memory.MemoryBridge;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ClaudeCodeProviderTest {

  @TempDir Path dir;

  @Test
  void buildsTheStreamJsonInvocation() {
    UUID session = UUID.randomUUID();
    StartAgent start =
        new StartAgent(
            UUID.randomUUID(),
            "TKM-1",
            "/repo",
            "main",
            session,
            "Arregla add()",
            List.of("Read", "Edit"),
            "dontAsk",
            null,
            new AgentLimits(5, new BigDecimal("2.00"), Duration.ofMinutes(30)),
            null,
            null);

    assertThat(new ClaudeCodeProvider("claude", List.of()).command(start))
        .containsExactly(
            "claude",
            "-p",
            "--output-format",
            "stream-json",
            "--verbose",
            "--include-partial-messages",
            "--session-id",
            session.toString(),
            "--permission-mode",
            "dontAsk",
            "--allowedTools",
            "Read,Edit",
            "--max-turns",
            "5",
            "--max-budget-usd",
            "2.00",
            "--",
            "Arregla add()");
  }

  @Test
  void aPromptThatLooksLikeAnOptionIsStillThePrompt() {
    // `claude -p --version` imprime la versión: el CLI lee el prompt como una opción.
    StartAgent start =
        new StartAgent(
            UUID.randomUUID(),
            "TKM-1",
            "/r",
            "main",
            UUID.randomUUID(),
            "--dangerously-skip-permissions",
            List.of(),
            "dontAsk",
            null,
            AgentLimits.none(),
            null,
            null);

    List<String> command = new ClaudeCodeProvider("claude", List.of()).command(start);
    assertThat(command.subList(command.size() - 2, command.size()))
        .containsExactly("--", "--dangerously-skip-permissions");
    assertThat(command.indexOf("--dangerously-skip-permissions")).isEqualTo(command.size() - 1);
  }

  @Test
  void asksForTheStructuredOutputOfItsSchema() {
    String schema = "{\"type\":\"object\",\"properties\":{\"ok\":{\"type\":\"boolean\"}}}";
    StartAgent start =
        new StartAgent(
            UUID.randomUUID(),
            "TKM-1",
            "/r",
            "main",
            UUID.randomUUID(),
            "Revisa",
            List.of(),
            null,
            null,
            AgentLimits.none(),
            null,
            null,
            null,
            null,
            schema);

    assertThat(new ClaudeCodeProvider("claude", List.of()).command(start))
        .containsSubsequence("--json-schema", schema);
  }

  @Test
  void resumesTheSessionAndForksWithANewId() {
    UUID parent = UUID.randomUUID();
    UUID forked = UUID.randomUUID();
    ClaudeCodeProvider provider = new ClaudeCodeProvider("claude", List.of());

    assertThat(
            provider.command(resume(parent, new ResumeFrom(parent.toString(), false, "/w", "b"))))
        .containsSubsequence("-p", "--resume", parent.toString(), "--", "Añade un test")
        .doesNotContain("--session-id", "--fork-session");
    assertThat(provider.command(resume(forked, new ResumeFrom(parent.toString(), true, "/w", "b"))))
        .containsSubsequence(
            "--resume", parent.toString(), "--fork-session", "--session-id", forked.toString());
  }

  private static StartAgent resume(UUID session, ResumeFrom from) {
    return new StartAgent(
        UUID.randomUUID(),
        "TKM-1",
        "/repo",
        "main",
        session,
        "Añade un test",
        List.of(),
        null,
        null,
        null,
        from,
        null);
  }

  @Test
  void passesOnlyAllowedEnvironmentVariables() {
    Map<String, String> env =
        new ClaudeCodeProvider("claude", List.of("EXTRA"))
            .environment(
                Map.of(
                    "PATH", "/bin",
                    "HOME", "/home/dev",
                    "ANTHROPIC_API_KEY", "k",
                    "EXTRA", "x",
                    "SKYNET_RUNNER_REGISTRATION_TOKEN", "secreto",
                    "AWS_SECRET_ACCESS_KEY", "s"));

    assertThat(env).containsOnlyKeys("PATH", "HOME", "ANTHROPIC_API_KEY", "EXTRA");
  }

  @Test
  void theRepositoryPolicyNarrowsTheExtraVariables() {
    ClaudeCodeProvider provider = new ClaudeCodeProvider("claude", List.of("EXTRA", "OTHER"));
    Map<String, String> runner =
        Map.of("PATH", "/bin", "EXTRA", "x", "OTHER", "o", "AWS_SECRET_ACCESS_KEY", "s");

    assertThat(provider.environment(runner, List.of("EXTRA", "AWS_SECRET_ACCESS_KEY")))
        .containsOnlyKeys("PATH", "EXTRA");
    assertThat(provider.environment(runner, List.of())).containsOnlyKeys("PATH");
    assertThat(provider.environment(runner, null)).containsOnlyKeys("PATH", "EXTRA", "OTHER");
  }

  @Test
  void withMemoryTheMcpServerGoesInItsConfigAndItsToolsAreAllowed() throws Exception {
    MemoryBridge memory =
        new MemoryBridge(List.of("/jdk/bin/java", "-cp", "/r/lib/*", "Main", "memory-mcp"));
    ClaudeCodeProvider provider = new ClaudeCodeProvider("claude", List.of());

    List<String> limited = provider.command(memoryStart(List.of("Read")), dir.resolve("a"), memory);
    Path config = dir.resolve("a.mcp.json");
    assertThat(limited)
        .containsSubsequence("--allowedTools", "Read,mcp__skynet_memoria")
        .containsSubsequence("--mcp-config", config.toString(), "--", "Recuerda");
    assertThat(limited.subList(limited.size() - 2, limited.size()))
        .containsExactly("--", "Recuerda");
    JsonNode server =
        JsonMapper.builder()
            .build()
            .readTree(Files.readString(config))
            .path("mcpServers")
            .path("skynet_memoria");
    assertThat(server.path("type").asString()).isEqualTo("stdio");
    assertThat(server.path("command").asString()).isEqualTo("/jdk/bin/java");
    assertThat(server.path("args"))
        .extracting(JsonNode::asString)
        .containsExactly("-cp", "/r/lib/*", "Main", "memory-mcp");

    // Sin herramientas limitadas, solo se añaden las de la memoria.
    assertThat(provider.command(memoryStart(List.of()), dir.resolve("b"), memory))
        .containsSubsequence("--mcp-config", dir.resolve("b.mcp.json").toString())
        .containsSubsequence("--allowedTools", "mcp__skynet_memoria");
    // Sin memoria, la invocación de siempre y ningún fichero.
    assertThat(provider.command(memoryStart(List.of("Read")), dir.resolve("c"), null))
        .isEqualTo(provider.command(memoryStart(List.of("Read"))))
        .doesNotContain("--mcp-config");
    assertThat(dir.resolve("c.mcp.json")).doesNotExist();
  }

  private static StartAgent memoryStart(List<String> tools) {
    return new StartAgent(
        UUID.fromString("00000000-0000-0000-0000-000000000001"),
        "TKM-1",
        "/r",
        "main",
        UUID.fromString("00000000-0000-0000-0000-000000000002"),
        "Recuerda",
        tools,
        "dontAsk",
        null,
        AgentLimits.none(),
        null,
        null);
  }
}
