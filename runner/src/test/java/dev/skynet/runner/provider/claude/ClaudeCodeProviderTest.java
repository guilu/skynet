package dev.skynet.runner.provider.claude;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skynet.protocol.runner.AgentLimits;
import dev.skynet.protocol.runner.ResumeFrom;
import dev.skynet.protocol.runner.StartAgent;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClaudeCodeProviderTest {

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
            null);

    assertThat(new ClaudeCodeProvider("claude", List.of()).command(start))
        .containsExactly(
            "claude",
            "-p",
            "Arregla add()",
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
            "2.00");
  }

  @Test
  void resumesTheSessionAndForksWithANewId() {
    UUID parent = UUID.randomUUID();
    UUID forked = UUID.randomUUID();
    ClaudeCodeProvider provider = new ClaudeCodeProvider("claude", List.of());

    assertThat(
            provider.command(resume(parent, new ResumeFrom(parent.toString(), false, "/w", "b"))))
        .containsSubsequence("-p", "Añade un test", "--resume", parent.toString())
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
        from);
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
}
