package dev.skynet.runner;

import dev.skynet.protocol.runner.AgentLimits;
import dev.skynet.protocol.runner.PushBranch;
import dev.skynet.protocol.runner.ResumeFrom;
import dev.skynet.protocol.runner.RunnerCommand;
import dev.skynet.protocol.runner.RunnerCommandType;
import dev.skynet.protocol.runner.StartAgent;
import dev.skynet.protocol.runner.WorkspaceRef;
import dev.skynet.runner.provider.ModelPrices;
import dev.skynet.runner.provider.ProviderRegistry;
import dev.skynet.runner.provider.antigravity.AntigravityProvider;
import dev.skynet.runner.provider.claude.ClaudeCodeProvider;
import dev.skynet.runner.provider.claude.ClaudeSessions;
import dev.skynet.runner.provider.codex.CodexProvider;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** fake-claude y fake-codex como agentes y órdenes START para los tests. */
public final class TestAgents {

  private TestAgents() {}

  /** Proveedor que lanza fake-claude (lo instala Gradle antes de los tests). */
  public static ClaudeCodeProvider fakeClaude() {
    return fakeClaude(Map.of());
  }

  /** fake-claude con las sesiones donde las deja según {@code runnerEnv}, para bifurcar. */
  public static ClaudeCodeProvider fakeClaude(Map<String, String> runnerEnv) {
    return new ClaudeCodeProvider(
        Path.of(System.getProperty("skynet.fakeClaude")).toAbsolutePath().toString(),
        List.of(
            "JAVA_HOME",
            "FAKE_CLAUDE_FIXTURE",
            "FAKE_CLAUDE_DELAY_MS",
            "FAKE_CLAUDE_APPLY",
            "FAKE_MCP_CALLS",
            "FAKE_MCP_LOG"),
        ModelPrices.defaults(),
        ClaudeSessions.of(runnerEnv).orElse(null));
  }

  /** Registro con fake-claude como único proveedor. */
  public static ProviderRegistry fakeProviders(Map<String, String> runnerEnv) {
    return ProviderRegistry.of(fakeClaude(runnerEnv));
  }

  /** Proveedor que lanza fake-codex (lo instala Gradle antes de los tests). */
  public static CodexProvider fakeCodex() {
    return new CodexProvider(
        Path.of(System.getProperty("skynet.fakeCodex")).toAbsolutePath().toString(),
        List.of(
            "JAVA_HOME",
            "FAKE_CODEX_FIXTURE",
            "FAKE_CODEX_RESUME_FIXTURE",
            "FAKE_CODEX_DELAY_MS",
            "FAKE_CODEX_HANG",
            "FAKE_CODEX_STRUCTURED_OUTPUT",
            "FAKE_MCP_CALLS",
            "FAKE_MCP_LOG"),
        ModelPrices.defaults());
  }

  /** Proveedor que lanza fake-agy (lo instala Gradle antes de los tests). */
  public static AntigravityProvider fakeAgy() {
    return new AntigravityProvider(
        Path.of(System.getProperty("skynet.fakeAgy")).toAbsolutePath().toString(),
        List.of(
            "JAVA_HOME",
            "FAKE_AGY_FIXTURE",
            "FAKE_AGY_RESUME_FIXTURE",
            "FAKE_AGY_DELAY_MS",
            "FAKE_AGY_HANG",
            "FAKE_AGY_STRUCTURED_OUTPUT",
            "FAKE_AGY_HOME"),
        ModelPrices.defaults());
  }

  /** Registro con fake-claude, fake-codex y fake-agy. */
  public static ProviderRegistry fakeProvidersWithAll(Map<String, String> runnerEnv) {
    return ProviderRegistry.of(fakeClaude(runnerEnv), fakeCodex(), fakeAgy());
  }

  /**
   * Entorno del runner con el que fake-agy reproduce {@code fixture}, con sus conversaciones en
   * {@code home}.
   */
  public static Map<String, String> fakeAgyEnv(String fixture, Path home) {
    Map<String, String> env = new HashMap<>(System.getenv());
    env.put("JAVA_HOME", System.getProperty("java.home"));
    env.put("FAKE_AGY_FIXTURE", fixture);
    env.put("FAKE_AGY_DELAY_MS", "0");
    env.put("FAKE_AGY_HOME", home.toString());
    return env;
  }

  /** Registro con fake-claude y fake-codex. */
  public static ProviderRegistry fakeProvidersWithCodex(Map<String, String> runnerEnv) {
    return ProviderRegistry.of(fakeClaude(runnerEnv), fakeCodex());
  }

  /**
   * Entorno del runner con el que fake-codex reproduce {@code fixture}, con sus sesiones en {@code
   * home}.
   */
  public static Map<String, String> fakeCodexEnv(String fixture, Path home) {
    Map<String, String> env = new HashMap<>(System.getenv());
    env.put("JAVA_HOME", System.getProperty("java.home"));
    env.put("FAKE_CODEX_FIXTURE", fixture);
    env.put("FAKE_CODEX_DELAY_MS", "0");
    env.put("CODEX_HOME", home.toString());
    return env;
  }

  /** La orden con el agente de otro proveedor. */
  public static RunnerCommand withProvider(RunnerCommand command, String provider) {
    return withProvider(command, provider, command.start().outputSchema());
  }

  /** La orden con el agente de otro proveedor y esa salida estructurada. */
  public static RunnerCommand withProvider(
      RunnerCommand command, String provider, String outputSchema) {
    StartAgent s = command.start();
    return new RunnerCommand(
        command.id(),
        command.type(),
        command.agentRunId(),
        command.createdAt(),
        new StartAgent(
            s.workflowRunId(),
            s.workItemKey(),
            s.repositoryPath(),
            s.baseBranch(),
            s.sessionId(),
            s.prompt(),
            s.allowedTools(),
            s.permissionMode(),
            s.model(),
            s.limits(),
            s.resume(),
            s.environment(),
            s.workspace(),
            s.command(),
            outputSchema,
            provider),
        command.verify(),
        command.cleanup());
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

  /** Una fase {@code github-pr}: sube el worktree {@code workspace} a {@code push} (EN-A). */
  public static RunnerCommand push(
      UUID agentRunId, String repository, WorkspaceRef workspace, PushBranch push) {
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
            "git push " + push.branch(),
            List.of(),
            null,
            null,
            AgentLimits.none(),
            null,
            null,
            workspace,
            null,
            null,
            null,
            null,
            push),
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
