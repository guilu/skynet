package dev.skynet.runner;

import dev.skynet.protocol.runner.AgentLimits;
import dev.skynet.protocol.runner.RunnerCommand;
import dev.skynet.protocol.runner.RunnerCommandType;
import dev.skynet.protocol.runner.StartAgent;
import dev.skynet.runner.provider.claude.ClaudeCodeProvider;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** fake-claude como agente y órdenes START para los tests. */
public final class TestAgents {

  private TestAgents() {}

  /** Proveedor que lanza fake-claude (lo instala Gradle antes de los tests). */
  public static ClaudeCodeProvider fakeClaude() {
    return new ClaudeCodeProvider(
        Path.of(System.getProperty("skynet.fakeClaude")).toAbsolutePath().toString(),
        List.of("JAVA_HOME", "FAKE_CLAUDE_FIXTURE", "FAKE_CLAUDE_DELAY_MS"));
  }

  /** Entorno del runner con el que fake-claude reproduce {@code fixture}. */
  public static Map<String, String> fakeClaudeEnv(String fixture) {
    Map<String, String> env = new HashMap<>(System.getenv());
    env.put("JAVA_HOME", System.getProperty("java.home"));
    env.put("FAKE_CLAUDE_FIXTURE", fixture);
    env.put("FAKE_CLAUDE_DELAY_MS", "0");
    return env;
  }

  public static RunnerCommand start(UUID agentRunId, String repository, Duration timeout) {
    StartAgent start =
        new StartAgent(
            UUID.randomUUID(),
            "TKM-1",
            repository,
            "main",
            UUID.randomUUID(),
            "Arregla add",
            List.of("Read", "Edit"),
            "dontAsk",
            null,
            new AgentLimits(null, null, timeout),
            null);
    return new RunnerCommand(
        UUID.randomUUID(), RunnerCommandType.START, agentRunId, Instant.now(), start);
  }
}
