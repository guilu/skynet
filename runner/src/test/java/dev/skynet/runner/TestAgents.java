package dev.skynet.runner;

import dev.skynet.protocol.runner.AgentLimits;
import dev.skynet.protocol.runner.ResumeFrom;
import dev.skynet.protocol.runner.RunnerCommand;
import dev.skynet.protocol.runner.RunnerCommandType;
import dev.skynet.protocol.runner.StartAgent;
import dev.skynet.protocol.runner.WorkspaceRef;
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
        List.of("JAVA_HOME", "FAKE_CLAUDE_FIXTURE", "FAKE_CLAUDE_DELAY_MS", "FAKE_CLAUDE_APPLY"));
  }

  /** Entorno del runner con el que fake-claude reproduce {@code fixture}. */
  public static Map<String, String> fakeClaudeEnv(String fixture) {
    Map<String, String> env = new HashMap<>(System.getenv());
    env.put("JAVA_HOME", System.getProperty("java.home"));
    env.put("FAKE_CLAUDE_FIXTURE", fixture);
    env.put("FAKE_CLAUDE_DELAY_MS", "0");
    return env;
  }

  /**
   * Como {@link #fakeClaudeEnv(String)}, con las sesiones de Claude guardadas en {@code config}.
   */
  public static Map<String, String> fakeClaudeEnv(String fixture, Path config) {
    Map<String, String> env = fakeClaudeEnv(fixture);
    env.put("CLAUDE_CONFIG_DIR", config.toString());
    return env;
  }

  public static RunnerCommand start(UUID agentRunId, String repository, Duration timeout) {
    return startWithin(agentRunId, repository, new AgentLimits(null, null, timeout));
  }

  /** Orden START con esos límites. */
  public static RunnerCommand startWithin(UUID agentRunId, String repository, AgentLimits limits) {
    return new RunnerCommand(
        UUID.randomUUID(),
        RunnerCommandType.START,
        agentRunId,
        Instant.now(),
        invocation(repository, UUID.randomUUID(), "Arregla add", limits, null),
        null,
        null);
  }

  /** Orden RESUME que continúa ({@code fork=false}) o bifurca la sesión de {@code from}. */
  public static RunnerCommand resume(
      UUID agentRunId, String repository, UUID sessionId, ResumeFrom from) {
    return new RunnerCommand(
        UUID.randomUUID(),
        RunnerCommandType.RESUME,
        agentRunId,
        Instant.now(),
        invocation(repository, sessionId, "Añade un test", AgentLimits.none(), from),
        null,
        null);
  }

  /** Orden START con una sesión nueva en el worktree existente {@code workspace}. */
  public static RunnerCommand continueIn(
      UUID agentRunId, String repository, WorkspaceRef workspace) {
    StartAgent base =
        invocation(repository, UUID.randomUUID(), "Corrige lo revisado", AgentLimits.none(), null);
    return new RunnerCommand(
        UUID.randomUUID(),
        RunnerCommandType.START,
        agentRunId,
        Instant.now(),
        new StartAgent(
            base.workflowRunId(),
            base.workItemKey(),
            base.repositoryPath(),
            base.baseBranch(),
            base.sessionId(),
            base.prompt(),
            base.allowedTools(),
            base.permissionMode(),
            base.model(),
            base.limits(),
            null,
            base.environment(),
            workspace),
        null,
        null);
  }

  /**
   * Orden START de una fase {@code command}: ejecuta {@code command} en un worktree nuevo o, con
   * {@code workspace}, en uno existente.
   */
  public static RunnerCommand command(
      UUID agentRunId,
      String repository,
      String command,
      Duration timeout,
      WorkspaceRef workspace) {
    return new RunnerCommand(
        UUID.randomUUID(),
        RunnerCommandType.START,
        agentRunId,
        Instant.now(),
        new StartAgent(
            UUID.randomUUID(),
            "TKM-1",
            repository,
            "main",
            UUID.randomUUID(),
            command,
            List.of(),
            null,
            null,
            new AgentLimits(null, null, timeout),
            null,
            null,
            workspace,
            command),
        null,
        null);
  }

  private static StartAgent invocation(
      String repository, UUID sessionId, String prompt, AgentLimits limits, ResumeFrom resume) {
    return new StartAgent(
        UUID.randomUUID(),
        "TKM-1",
        repository,
        "main",
        sessionId,
        prompt,
        List.of("Read", "Edit"),
        "dontAsk",
        null,
        limits,
        resume,
        null);
  }
}
