package dev.skynet.runner.agent;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skynet.protocol.AgentEventType;
import dev.skynet.protocol.NormalizedEvent;
import dev.skynet.protocol.runner.AgentLimits;
import dev.skynet.protocol.runner.ArtifactType;
import dev.skynet.protocol.runner.CleanupWorkspace;
import dev.skynet.protocol.runner.PushBranch;
import dev.skynet.protocol.runner.ResumeFrom;
import dev.skynet.protocol.runner.RunVerification;
import dev.skynet.protocol.runner.RunnerCommand;
import dev.skynet.protocol.runner.RunnerCommandType;
import dev.skynet.protocol.runner.StartAgent;
import dev.skynet.protocol.runner.VerificationCheck;
import dev.skynet.protocol.runner.WorkspaceRef;
import dev.skynet.runner.TestAgents;
import dev.skynet.runner.TestRepos;
import dev.skynet.runner.journal.Journal;
import dev.skynet.runner.supervisor.ProcessSupervisor;
import dev.skynet.runner.workspace.WorkspaceManager;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Invocaciones completas con fake-claude como agente y repositorios git temporales. */
class AgentExecutorTest {

  @TempDir Path dir;

  private Path repo;
  private Journal journal;
  private AgentExecutor executor;

  @BeforeEach
  void setUp() throws Exception {
    repo = TestRepos.create(dir.resolve("repo"));
    journal = Journal.open(dir.resolve("journal.db"));
  }

  @AfterEach
  void tearDown() {
    if (executor != null) {
      executor.close();
    }
    journal.close();
  }

  private AgentExecutor executor(String fixture) {
    return executor(fixture, Map.of());
  }

  private AgentExecutor executor(String fixture, Map<String, String> extraEnv) {
    Map<String, String> env =
        new HashMap<>(TestAgents.fakeClaudeEnv(fixture, dir.resolve("claude")));
    env.putAll(extraEnv);
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
    return executor;
  }

  /** Ejecutor con fake-claude y fake-codex, que reproduce el fixture de Codex {@code fixture}. */
  private AgentExecutor codexExecutor(String fixture) {
    Map<String, String> env = TestAgents.fakeCodexEnv(fixture, dir.resolve("codex"));
    executor =
        new AgentExecutor(
            journal,
            new ProcessSupervisor(),
            new WorkspaceManager(dir.resolve("workspaces")),
            TestAgents.fakeProvidersWithCodex(env),
            env,
            dir.resolve("logs"),
            Duration.ofSeconds(2),
            () -> {},
            new ArtifactSpool(journal, dir.resolve("artifacts"), () -> {}));
    return executor;
  }

  /** Ejecutor con los tres proveedores, que reproduce el fixture de agy {@code fixture}. */
  private AgentExecutor agyExecutor(String fixture, Map<String, String> extraEnv) {
    Map<String, String> env = new HashMap<>(TestAgents.fakeAgyEnv(fixture, dir.resolve("agy")));
    env.putAll(extraEnv);
    executor =
        new AgentExecutor(
            journal,
            new ProcessSupervisor(),
            new WorkspaceManager(dir.resolve("workspaces")),
            TestAgents.fakeProvidersWithAll(env),
            env,
            dir.resolve("logs"),
            Duration.ofSeconds(2),
            () -> {},
            new ArtifactSpool(journal, dir.resolve("artifacts"), () -> {}));
    return executor;
  }

  private List<NormalizedEvent> eventsOf(UUID agentRunId) {
    return journal.pending(1000).stream().filter(e -> e.agentRunId().equals(agentRunId)).toList();
  }

  private NormalizedEvent awaitExit(UUID agentRunId) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      if (journal.isFinished(agentRunId)) {
        return eventsOf(agentRunId).getLast();
      }
      Thread.sleep(50);
    }
    throw new AssertionError("La invocación no terminó: " + eventsOf(agentRunId));
  }

  private void awaitRunning(UUID agentRunId) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (journal.processes().stream().noneMatch(p -> p.agentRunId().equals(agentRunId))) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("El proceso no arrancó");
      }
      Thread.sleep(20);
    }
  }

  @Test
  void anAgentOfAProviderTheRunnerDoesNotHaveFailsWithoutAWorktree() throws Exception {
    UUID id = UUID.randomUUID();
    StartAgent base = TestAgents.start(id, repo.toString(), null).start();
    StartAgent codex =
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
            null,
            null,
            null,
            null,
            "codex");
    executor("01-simple-text")
        .start(
            new RunnerCommand(
                UUID.randomUUID(), RunnerCommandType.START, id, Instant.now(), codex, null, null));

    NormalizedEvent exit = awaitExit(id);
    assertThat(exit.type()).isEqualTo(AgentEventType.PROCESS_EXITED);
    assertThat(exit.payload().get("error")).isEqualTo("Este runner no tiene Codex disponible");
    assertThat(eventsOf(id)).hasSize(1);
  }

  @Test
  void aCodexAgentRunsInAWorktreeAndItsSessionCanBeResumed() throws Exception {
    UUID first = UUID.randomUUID();
    codexExecutor("02-tools")
        .start(TestAgents.withProvider(TestAgents.start(first, repo.toString(), null), "codex"));

    NormalizedEvent exit = awaitExit(first);
    assertThat(exit.payload()).containsEntry("exitCode", 0).doesNotContainKey("error");
    List<NormalizedEvent> events = eventsOf(first);
    assertThat(events.getFirst().type()).isEqualTo(AgentEventType.WORKSPACE_READY);
    NormalizedEvent session = events.get(1);
    assertThat(session.type()).isEqualTo(AgentEventType.SESSION_STARTED);
    assertThat(session.payload()).containsEntry("provider", "codex");
    String thread = (String) session.payload().get("sessionId");
    assertThat(events)
        .filteredOn(e -> e.type() == AgentEventType.TOOL_STARTED)
        .extracting(e -> e.payload().get("name"))
        .contains("Bash", "apply_patch", "mcp__docs__search", "WebSearch");
    assertThat(events)
        .filteredOn(e -> e.type() == AgentEventType.FILE_CHANGED)
        .extracting(e -> e.payload().get("path"))
        .containsExactly("calc.py");
    assertThat(events)
        .filteredOn(e -> e.type() == AgentEventType.RESULT)
        .singleElement()
        .satisfies(r -> assertThat(r.payload()).containsEntry("isError", false));
    assertThat(dir.resolve("codex/sessions").resolve(thread + ".jsonl")).exists();
    // CU-A: la cuota no viaja en la salida de exec; el runner la lee del fichero de la sesión al
    // terminar, antes del fin.
    assertThat(events.get(events.size() - 2).type()).isEqualTo(AgentEventType.QUOTA);
    Map<String, Object> quota = events.get(events.size() - 2).payload();
    assertThat(quota).containsEntry("provider", "codex");
    assertThat((List<?>) quota.get("windows"))
        .extracting(w -> ((Map<?, ?>) w).get("window") + "=" + ((Map<?, ?>) w).get("usedFraction"))
        .containsExactly("five_hour=0.23", "seven_day=0.13");

    Map<String, Object> ready = events.getFirst().payload();
    UUID resumed = UUID.randomUUID();
    executor.start(
        TestAgents.withProvider(
            TestAgents.resume(
                resumed,
                repo.toString(),
                UUID.randomUUID(),
                new ResumeFrom(
                    thread, false, (String) ready.get("path"), (String) ready.get("branch"))),
            "codex"));
    exit = awaitExit(resumed);

    assertThat(exit.payload()).containsEntry("exitCode", 0).doesNotContainKey("error");
    List<NormalizedEvent> again = eventsOf(resumed);
    assertThat(again.getFirst().payload()).containsEntry("path", ready.get("path"));
    assertThat(again.get(1).payload()).containsEntry("sessionId", thread);
  }

  @Test
  void anAntigravityAgentKeepsItsConversationAndReturnsItsStructuredOutput() throws Exception {
    String schema = "{\"type\":\"object\",\"properties\":{\"fixed\":{\"type\":\"boolean\"}}}";
    UUID first = UUID.randomUUID();
    RunnerCommand start =
        TestAgents.withProvider(
            TestAgents.start(first, repo.toString(), null), "antigravity", schema);
    agyExecutor("02-tools", Map.of("FAKE_AGY_STRUCTURED_OUTPUT", "{\"fixed\": true}")).start(start);

    NormalizedEvent exit = awaitExit(first);
    assertThat(exit.payload()).containsEntry("exitCode", 0).doesNotContainKey("error");
    List<NormalizedEvent> events = eventsOf(first);
    assertThat(events.get(1).payload()).containsEntry("provider", "antigravity");
    String conversation = (String) events.get(1).payload().get("sessionId");
    assertThat(dir.resolve("agy/conversations").resolve(conversation + ".jsonl")).exists();
    assertThat(events)
        .filteredOn(e -> e.type() == AgentEventType.TOOL_STARTED)
        .extracting(e -> e.payload().get("name"))
        .containsExactly("view_file", "replace_file_content");
    Map<String, Object> ready = events.getFirst().payload();
    assertThat(events)
        .filteredOn(e -> e.type() == AgentEventType.FILE_CHANGED)
        .extracting(e -> e.payload().get("path"))
        .containsExactly(Path.of((String) ready.get("path"), "calc.py").toString());
    assertThat(events)
        .filteredOn(e -> e.type() == AgentEventType.RESULT)
        .singleElement()
        .satisfies(
            r ->
                assertThat(r.payload())
                    .containsEntry("isError", false)
                    .containsEntry("sessionId", conversation)
                    .containsEntry("structuredOutput", Map.of("fixed", true)));

    UUID resumed = UUID.randomUUID();
    executor.start(
        TestAgents.withProvider(
            TestAgents.resume(
                resumed,
                repo.toString(),
                UUID.randomUUID(),
                new ResumeFrom(
                    conversation, false, (String) ready.get("path"), (String) ready.get("branch"))),
            "antigravity"));
    exit = awaitExit(resumed);

    assertThat(exit.payload()).containsEntry("exitCode", 0).doesNotContainKey("error");
    assertThat(eventsOf(resumed).get(1).payload()).containsEntry("sessionId", conversation);
  }

  @Test
  void aStageSchemaInTheRepositoryIsReadFromTheWorktree() throws Exception {
    Files.createDirectories(repo.resolve("schemas"));
    Files.writeString(
        repo.resolve("schemas/spec.json"),
        "{\"type\":\"object\",\"properties\":{\"summary\":{\"type\":\"string\"}}}");
    TestRepos.git(repo, "add", ".");
    TestRepos.git(repo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "s");
    UUID id = UUID.randomUUID();
    agyExecutor("04-schema", Map.of("FAKE_AGY_STRUCTURED_OUTPUT", "{\"summary\": \"hecho\"}"))
        .start(
            TestAgents.withProvider(
                TestAgents.start(id, repo.toString(), null),
                "antigravity",
                "{\"$ref\":\"schemas/spec.json\"}"));

    NormalizedEvent exit = awaitExit(id);
    assertThat(exit.payload()).containsEntry("exitCode", 0).doesNotContainKey("error");
    assertThat(eventsOf(id))
        .filteredOn(e -> e.type() == AgentEventType.RESULT)
        .singleElement()
        .satisfies(
            r ->
                assertThat(r.payload())
                    .containsEntry("structuredOutput", Map.of("summary", "hecho")));
  }

  @Test
  void aStageSchemaMissingFromTheWorktreeFailsTheInvocation() throws Exception {
    UUID id = UUID.randomUUID();
    executor("01-simple-text")
        .start(
            TestAgents.withProvider(
                TestAgents.start(id, repo.toString(), null),
                "claude-code",
                "{\"$ref\":\"schemas/spec.json\"}"));

    NormalizedEvent exit = awaitExit(id);
    assertThat(eventsOf(id).getFirst().type()).isEqualTo(AgentEventType.WORKSPACE_READY);
    assertThat(exit.payload())
        .containsEntry(
            "error",
            "Salida estructurada de la fase: No existe `schemas/spec.json` en el worktree");
    assertThat(eventsOf(id)).hasSize(2);
  }

  @Test
  void anAntigravityCommandWithoutPermissionIsReportedAsDenied() throws Exception {
    UUID id = UUID.randomUUID();
    agyExecutor("05-denied", Map.of())
        .start(TestAgents.withProvider(TestAgents.start(id, repo.toString(), null), "antigravity"));

    NormalizedEvent exit = awaitExit(id);
    assertThat(exit.payload()).containsEntry("exitCode", 0);
    assertThat(eventsOf(id))
        .filteredOn(e -> e.type() == AgentEventType.PERMISSION_DENIED)
        .singleElement()
        .satisfies(d -> assertThat(d.payload()).containsEntry("toolName", "RunCommand"));
  }

  @Test
  void aFailedCodexTurnEndsWithAnErrorResult() throws Exception {
    UUID id = UUID.randomUUID();
    codexExecutor("04-failed")
        .start(TestAgents.withProvider(TestAgents.start(id, repo.toString(), null), "codex"));

    NormalizedEvent exit = awaitExit(id);
    assertThat(exit.payload()).containsEntry("exitCode", 1);
    assertThat(eventsOf(id))
        .filteredOn(e -> e.type() == AgentEventType.RESULT)
        .singleElement()
        .satisfies(
            r ->
                assertThat(r.payload())
                    .containsEntry("isError", true)
                    .containsEntry(
                        "errors",
                        List.of("stream disconnected before completion: rate limit reached")));
  }

  @Test
  void runsTheAgentInAWorktreeAndJournalsItsEvents() throws Exception {
    UUID id = UUID.randomUUID();
    executor("02-tools").start(TestAgents.start(id, repo.toString(), null));

    NormalizedEvent exit = awaitExit(id);
    List<NormalizedEvent> events = eventsOf(id);

    assertThat(events.getFirst().type()).isEqualTo(AgentEventType.WORKSPACE_READY);
    assertThat(events.get(1).type()).isEqualTo(AgentEventType.SESSION_STARTED);
    assertThat(events).extracting(NormalizedEvent::type).contains(AgentEventType.RESULT);
    assertThat(exit.type()).isEqualTo(AgentEventType.PROCESS_EXITED);
    assertThat(exit.payload()).containsEntry("exitCode", 0).doesNotContainKey("error");
    assertThat(events)
        .extracting(NormalizedEvent::seq)
        .containsExactlyElementsOf(LongStream.rangeClosed(1, events.size()).boxed().toList());

    Map<String, Object> ready = events.getFirst().payload();
    Path worktree = Path.of((String) ready.get("path"));
    assertThat(worktree.resolve("calc.py")).exists();
    assertThat(TestRepos.git(worktree, "rev-parse", "--abbrev-ref", "HEAD"))
        .isEqualTo(ready.get("branch"));
    assertThat(dir.resolve("logs").resolve(id + ".ndjson")).isNotEmptyFile();
    assertThat(journal.processes()).isEmpty();
    assertThat(executor.running()).isEmpty();
  }

  @Test
  void binaryToolOutputReachesTheJournalWithoutNulAndTheLogKeepsItsBytes() throws Exception {
    // El agente hace `head -c 40 app.jar`: su tool_result trae los bytes del ZIP escapados.
    String jar = "PK\\u0003\\u0004\\u0014\\u0000\\b\\b\\b\\u0000\\u0000\\u0000META-INF/\\u0000";
    String fixture;
    try (var in = getClass().getResourceAsStream("/claude/02-tools.ndjson")) {
      fixture =
          new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
              .replace(
                  "\"content\":\"1\\tdef add(a, b):\\n2\\t    return a - b\\n3\\t\"",
                  "\"content\":\"" + jar + "\"");
    }
    assertThat(fixture).contains(jar);
    Path file = Files.writeString(dir.resolve("binario.ndjson"), fixture);
    UUID id = UUID.randomUUID();
    executor(file.toString()).start(TestAgents.start(id, repo.toString(), null));

    NormalizedEvent exit = awaitExit(id);

    assertThat(exit.payload()).containsEntry("exitCode", 0);
    String journaled =
        tools.jackson.databind.json.JsonMapper.shared().writeValueAsString(eventsOf(id));
    // Un NUL real sale escapado como barra, u y 0000; el saneado lleva además la barra escapada.
    assertThat(journaled.replace("\\\\", "")).doesNotContain("\\u0000");
    assertThat(journaled).contains("PK\\\\u0003\\\\u0004");
    // El log NDJSON es una copia de la salida del proceso, sin tocar.
    Journal.PendingArtifact log = artifactsOf(id).get(ArtifactType.LOG);
    assertThat(Files.readString(log.file())).contains(jar);
  }

  @Test
  void cleanupRemovesTheWorktreeAndKeepsItsBranch() throws Exception {
    UUID id = UUID.randomUUID();
    executor("01-simple-text").start(TestAgents.start(id, repo.toString(), null));
    awaitExit(id);
    Map<String, Object> ready = eventsOf(id).getFirst().payload();
    Path worktree = Path.of((String) ready.get("path"));
    UUID workspaceId = UUID.randomUUID();

    executor.cleanup(
        RunnerCommand.cleanup(
            UUID.randomUUID(),
            id,
            Instant.now(),
            new CleanupWorkspace(workspaceId, worktree.toString())));
    NormalizedEvent removed = awaitEvent(id, AgentEventType.WORKSPACE_REMOVED);

    assertThat(removed.payload())
        .containsEntry("workspaceId", workspaceId.toString())
        .containsEntry("path", worktree.toString())
        .doesNotContainKey("error");
    assertThat(worktree).doesNotExist();
    assertThat(TestRepos.git(repo, "branch", "--list", (String) ready.get("branch")))
        .contains((String) ready.get("branch"));
  }

  @Test
  void cleanupOutsideTheWorkspacesRootReportsAnError() throws Exception {
    UUID id = UUID.randomUUID();
    executor("01-simple-text")
        .cleanup(
            RunnerCommand.cleanup(
                UUID.randomUUID(),
                id,
                Instant.now(),
                new CleanupWorkspace(UUID.randomUUID(), repo.toString())));

    assertThat(awaitEvent(id, AgentEventType.WORKSPACE_REMOVED).payload())
        .hasEntrySatisfying("error", e -> assertThat((String) e).contains("no es de este runner"));
    assertThat(repo.resolve("calc.py")).exists();
  }

  @Test
  void purgeLogsDeletesOnlyOldLogs() throws Exception {
    Path logs = Files.createDirectories(dir.resolve("logs"));
    Path old = Files.writeString(logs.resolve(UUID.randomUUID() + ".ndjson"), "{}");
    Files.setLastModifiedTime(
        old, java.nio.file.attribute.FileTime.from(Instant.now().minus(Duration.ofDays(8))));
    Path recent = Files.writeString(logs.resolve(UUID.randomUUID() + ".ndjson"), "{}");

    assertThat(executor("01-simple-text").purgeLogs(Duration.ofDays(7))).isEqualTo(1);
    assertThat(old).doesNotExist();
    assertThat(recent).exists();
  }

  private NormalizedEvent awaitEvent(UUID agentRunId, AgentEventType type)
      throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      var found = eventsOf(agentRunId).stream().filter(e -> e.type() == type).findFirst();
      if (found.isPresent()) {
        return found.get();
      }
      Thread.sleep(50);
    }
    throw new AssertionError("No llegó " + type + ": " + eventsOf(agentRunId));
  }

  @Test
  void aRedeliveredStartDoesNotRunTwice() throws Exception {
    UUID id = UUID.randomUUID();
    RunnerCommand command = TestAgents.start(id, repo.toString(), null);
    executor("01-simple-text").start(command);
    awaitExit(id);

    executor.start(command);
    Thread.sleep(300);

    assertThat(eventsOf(id)).filteredOn(e -> e.type() == AgentEventType.WORKSPACE_READY).hasSize(1);
  }

  @Test
  void cancelTerminatesTheRunningAgent() throws Exception {
    UUID id = UUID.randomUUID();
    executor("07-cancelled").start(TestAgents.start(id, repo.toString(), null));
    awaitRunning(id);

    executor.cancel(id);
    NormalizedEvent exit = awaitExit(id);

    assertThat(exit.type()).isEqualTo(AgentEventType.PROCESS_EXITED);
    assertThat(exit.payload()).doesNotContainKey("error");
    assertThat(exit.payload().get("signal")).isIn("SIGTERM", "SIGKILL");
  }

  @Test
  void anAntigravityAgentCancelledRightAfterThinkingKeepsThoseTokens() throws Exception {
    // Un paso solo de razonamiento (sin texto) y, antes de otro mensaje o herramienta, se cancela.
    Path fixture = dir.resolve("pensar.jsonl");
    Files.writeString(
        fixture,
        """
        {"event":"init","conversation_id":"c-1","init":{"permission_mode":"accept-edits"}}
        {"event":"step_update","step_update":{"step_index":1,"state":"DONE","step_type":"agent_response","usage":{"input_tokens":900,"output_tokens":0,"thinking_tokens":40,"cache_read_tokens":100}}}
        """);
    UUID id = UUID.randomUUID();
    agyExecutor(fixture.toString(), Map.of())
        .start(TestAgents.withProvider(TestAgents.start(id, repo.toString(), null), "antigravity"));
    Path log = dir.resolve("logs").resolve(id + ".ndjson");
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (!Files.exists(log) || !Files.readString(log).contains("agent_response")) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("agy no llegó al paso del agente");
      }
      Thread.sleep(20);
    }

    executor.cancel(id);
    NormalizedEvent exit = awaitExit(id);

    // Del diario vuelven como números JSON: se comparan por su valor.
    Map<?, ?> usage = (Map<?, ?>) exit.payload().get("usage");
    assertThat(
            java.util.stream.Stream.of("input", "output", "cacheRead")
                .map(k -> ((Number) usage.get(k)).longValue()))
        .containsExactly(900L, 40L, 100L);
    assertThat(eventsOf(id))
        .filteredOn(e -> e.type() != AgentEventType.PROCESS_EXITED)
        .noneMatch(e -> e.payload().containsKey("usage"));
  }

  @Test
  void aTimeoutTerminatesTheAgentWithAnError() throws Exception {
    UUID id = UUID.randomUUID();
    executor("07-cancelled").start(TestAgents.start(id, repo.toString(), Duration.ofSeconds(1)));

    NormalizedEvent exit = awaitExit(id);

    assertThat((String) exit.payload().get("error")).contains("tiempo");
  }

  @Test
  void anEstimatedCostOverTheBudgetTerminatesTheAgentWithAnError() throws Exception {
    UUID id = UUID.randomUUID();
    // 07-cancelled no termina solo: su primer mensaje ya cuesta unos 0,03 US$.
    executor("07-cancelled")
        .start(
            TestAgents.startWithin(
                id, repo.toString(), new AgentLimits(null, new BigDecimal("0.01"), null)));

    NormalizedEvent exit = awaitExit(id);

    assertThat((String) exit.payload().get("error"))
        .startsWith("Presupuesto agotado")
        .endsWith("supera el máximo de 0.01 US$");
    assertThat(new BigDecimal(exit.payload().get("estimatedCostUsd").toString()))
        .isGreaterThan(new BigDecimal("0.01"));
    assertThat(exit.payload().get("signal")).isIn("SIGTERM", "SIGKILL");
  }

  @Test
  void cancellingAnAgentThatIsNotRunningStillReportsItsEnd() {
    UUID id = UUID.randomUUID();
    executor("01-simple-text").cancel(id);

    assertThat(journal.isFinished(id)).isTrue();
    assertThat(eventsOf(id))
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.type()).isEqualTo(AgentEventType.PROCESS_EXITED);
              assertThat(e.payload()).doesNotContainKey("exitCode").containsKey("error");
            });
  }

  @Test
  void aMissingRepositoryEndsTheInvocationWithAnError() throws Exception {
    UUID id = UUID.randomUUID();
    executor("01-simple-text")
        .start(TestAgents.start(id, dir.resolve("no-existe").toString(), null));

    NormalizedEvent exit = awaitExit(id);

    assertThat(eventsOf(id)).hasSize(1);
    assertThat((String) exit.payload().get("error")).startsWith("No se pudo preparar el worktree");
  }

  @Test
  void resumeContinuesTheSessionInTheSameWorktree() throws Exception {
    UUID first = UUID.randomUUID();
    RunnerCommand start = TestAgents.start(first, repo.toString(), null);
    executor("02-tools").start(start);
    awaitExit(first);
    Map<String, Object> ready = eventsOf(first).getFirst().payload();
    UUID session = start.start().sessionId();

    UUID resumed = UUID.randomUUID();
    executor.start(
        TestAgents.resume(
            resumed,
            repo.toString(),
            session,
            new ResumeFrom(
                session.toString(),
                false,
                (String) ready.get("path"),
                (String) ready.get("branch"))));
    NormalizedEvent exit = awaitExit(resumed);

    assertThat(exit.payload()).containsEntry("exitCode", 0).doesNotContainKey("error");
    List<NormalizedEvent> events = eventsOf(resumed);
    assertThat(events.getFirst().payload())
        .containsEntry("path", ready.get("path"))
        .containsEntry("branch", ready.get("branch"));
    assertThat(events.get(1).type()).isEqualTo(AgentEventType.SESSION_STARTED);
    assertThat(events.get(1).payload()).containsEntry("sessionId", session.toString());
  }

  @Test
  void aNewSessionCanStartInTheWorktreeOfAPreviousOne() throws Exception {
    UUID first = UUID.randomUUID();
    RunnerCommand start = TestAgents.start(first, repo.toString(), null);
    executor("02-tools").start(start);
    awaitExit(first);
    Map<String, Object> ready = eventsOf(first).getFirst().payload();
    Path worktree = Path.of((String) ready.get("path"));
    Files.writeString(worktree.resolve("notas.txt"), "sin confirmar\n");

    UUID next = UUID.randomUUID();
    RunnerCommand command =
        TestAgents.continueIn(
            next,
            repo.toString(),
            new WorkspaceRef(worktree.toString(), (String) ready.get("branch")));
    executor.start(command);
    NormalizedEvent exit = awaitExit(next);

    assertThat(exit.payload()).containsEntry("exitCode", 0).doesNotContainKey("error");
    List<NormalizedEvent> events = eventsOf(next);
    assertThat(events.getFirst().payload())
        .containsEntry("path", ready.get("path"))
        .containsEntry("branch", ready.get("branch"));
    // Sesión nueva (no --resume), y los cambios sin confirmar de la anterior siguen ahí.
    assertThat(events.get(1).payload())
        .containsEntry("sessionId", command.start().sessionId().toString());
    assertThat(worktree.resolve("notas.txt")).hasContent("sin confirmar");
  }

  @Test
  void childrenOfAParallelStageWorkInCopiesOfTheInheritedWorktree() throws Exception {
    UUID first = UUID.randomUUID();
    executor("02-tools").start(TestAgents.start(first, repo.toString(), null));
    awaitExit(first);
    Map<String, Object> ready = eventsOf(first).getFirst().payload();
    Path original = Path.of((String) ready.get("path"));
    Files.writeString(original.resolve("notas.txt"), "sin confirmar\n");
    WorkspaceRef copy = new WorkspaceRef(original.toString(), (String) ready.get("branch"), true);

    UUID quality = UUID.randomUUID();
    UUID security = UUID.randomUUID();
    executor.start(
        TestAgents.command(quality, repo.toString(), "echo calidad >> notas.txt", null, copy));
    executor.start(
        TestAgents.command(security, repo.toString(), "echo seguridad >> notas.txt", null, copy));
    assertThat(awaitExit(quality).payload()).containsEntry("exitCode", 0);
    assertThat(awaitExit(security).payload()).containsEntry("exitCode", 0);

    Path qualityPath = Path.of((String) eventsOf(quality).getFirst().payload().get("path"));
    Path securityPath = Path.of((String) eventsOf(security).getFirst().payload().get("path"));
    assertThat(qualityPath).isNotEqualTo(original).isNotEqualTo(securityPath);
    // Cada hijo parte de los cambios sin confirmar del original y no ve los del otro.
    assertThat(qualityPath.resolve("notas.txt")).hasContent("sin confirmar\ncalidad");
    assertThat(securityPath.resolve("notas.txt")).hasContent("sin confirmar\nseguridad");
    assertThat(original.resolve("notas.txt")).hasContent("sin confirmar");
    assertThat(eventsOf(quality).getFirst().payload().get("branch"))
        .isNotEqualTo(ready.get("branch"));
  }

  @Test
  void forkContinuesACopyOfTheSessionInANewWorktree() throws Exception {
    UUID first = UUID.randomUUID();
    RunnerCommand start = TestAgents.start(first, repo.toString(), null);
    executor("02-tools").start(start);
    awaitExit(first);
    Map<String, Object> ready = eventsOf(first).getFirst().payload();
    Path parent = Path.of((String) ready.get("path"));
    Files.writeString(parent.resolve("notas.txt"), "sin confirmar\n");
    String session = start.start().sessionId().toString();

    UUID forked = UUID.randomUUID();
    UUID forkSession = UUID.randomUUID();
    executor.start(
        TestAgents.resume(
            forked,
            repo.toString(),
            forkSession,
            new ResumeFrom(session, true, parent.toString(), (String) ready.get("branch"))));
    NormalizedEvent exit = awaitExit(forked);

    assertThat(exit.payload()).containsEntry("exitCode", 0).doesNotContainKey("error");
    List<NormalizedEvent> events = eventsOf(forked);
    Path worktree = Path.of((String) events.getFirst().payload().get("path"));
    assertThat(worktree).isNotEqualTo(parent);
    assertThat(worktree.resolve("notas.txt")).hasContent("sin confirmar");
    assertThat(events.get(1).payload()).containsEntry("sessionId", forkSession.toString());
    // fake-claude, como el CLI, solo encuentra la sesión en el directorio de su cwd.
    Path projects = dir.resolve("claude/projects");
    String project = worktree.toRealPath().toString().replaceAll("[^A-Za-z0-9]", "-");
    assertThat(projects.resolve(project).resolve(session + ".jsonl")).isRegularFile();
    assertThat(projects.resolve(project).resolve(forkSession + ".jsonl")).isRegularFile();
  }

  @Test
  void resumeOnlyAcceptsWorktreesOfThisRunner() throws Exception {
    UUID id = UUID.randomUUID();
    executor("01-simple-text")
        .start(
            TestAgents.resume(
                id,
                repo.toString(),
                UUID.randomUUID(),
                new ResumeFrom(UUID.randomUUID().toString(), false, repo.toString(), "main")));

    NormalizedEvent exit = awaitExit(id);

    assertThat((String) exit.payload().get("error"))
        .startsWith("No se pudo preparar el worktree")
        .contains("no existe en este runner");
  }

  @Test
  void aResumeWithASessionIdThatIsNotAnIdFailsBeforeTouchingAnything() throws Exception {
    UUID first = UUID.randomUUID();
    executor("01-simple-text").start(TestAgents.start(first, repo.toString(), null));
    awaitExit(first);
    String path = (String) eventsOf(first).getFirst().payload().get("path");

    for (String session : new String[] {"--dangerously-skip-permissions", "../../x"}) {
      UUID id = UUID.randomUUID();
      executor.start(
          TestAgents.resume(
              id, repo.toString(), UUID.randomUUID(), new ResumeFrom(session, true, path, "x")));

      NormalizedEvent exit = awaitExit(id);
      assertThat(exit.payload())
          .containsEntry("failure", "start")
          .containsEntry(
              "error",
              "No se pudo preparar el worktree: `" + session + "` no es un id de sesión válido");
      assertThat(eventsOf(id)).hasSize(1);
    }
  }

  @Test
  void aWorktreeOnlyRunsOneInvocationAtATime() throws Exception {
    UUID running = UUID.randomUUID();
    RunnerCommand start = TestAgents.start(running, repo.toString(), null);
    executor("07-cancelled").start(start);
    awaitRunning(running);
    Map<String, Object> ready = eventsOf(running).getFirst().payload();
    String session = start.start().sessionId().toString();

    UUID second = UUID.randomUUID();
    executor.start(
        TestAgents.resume(
            second,
            repo.toString(),
            UUID.fromString(session),
            new ResumeFrom(
                session, false, (String) ready.get("path"), (String) ready.get("branch"))));
    NormalizedEvent rejected = awaitExit(second);

    assertThat((String) rejected.payload().get("error")).contains("Ya hay otra invocación");
    assertThat(executor.running()).containsExactly(running);
    executor.cancel(running);
    awaitExit(running);
  }

  @Test
  void collectsThePromptTheLogTheResultAndTheChangesOfTheInvocation() throws Exception {
    UUID id = UUID.randomUUID();
    executor("02-tools", Map.of("FAKE_CLAUDE_APPLY", "1"))
        .start(TestAgents.start(id, repo.toString(), null));
    awaitExit(id);

    Map<ArtifactType, Journal.PendingArtifact> artifacts = artifactsOf(id);
    assertThat(artifacts)
        .containsOnlyKeys(
            ArtifactType.PROMPT,
            ArtifactType.LOG,
            ArtifactType.RESULT,
            ArtifactType.GIT_CHANGES,
            ArtifactType.DIFF);
    assertThat(artifacts.values())
        .allSatisfy(
            a -> {
              assertThat(a.upload().agentRunId()).isEqualTo(id);
              assertThat(a.upload().verificationRunId()).isNull();
              assertThat(a.file()).exists();
            });
    assertThat(artifacts.get(ArtifactType.PROMPT).file()).hasContent("Arregla add");
    assertThat(Files.readString(artifacts.get(ArtifactType.RESULT).file())).contains("fix add");
    assertThat(Files.readString(artifacts.get(ArtifactType.GIT_CHANGES).file()))
        .contains("\"subject\":\"fix add\"")
        .contains("\"path\":\"calc.py\"");
    assertThat(artifacts.get(ArtifactType.GIT_CHANGES).upload().metadata())
        .containsEntry("commits", 1)
        .containsEntry("files", 1);
    assertThat(Files.readString(artifacts.get(ArtifactType.DIFF).file()))
        .contains("-    return a - b")
        .contains("+    return a + b");
  }

  @Test
  void aCommandRunsInANewWorktreeAndEndsWithItsExitCode() throws Exception {
    UUID id = UUID.randomUUID();
    executor("02-tools")
        .start(
            TestAgents.command(
                id,
                repo.toString(),
                "echo compilando; echo hecho > salida.txt; echo fallo >&2; exit 3",
                null,
                null));

    NormalizedEvent exit = awaitExit(id);
    List<NormalizedEvent> events = eventsOf(id);
    assertThat(events)
        .extracting(NormalizedEvent::type)
        .containsExactly(
            AgentEventType.WORKSPACE_READY,
            AgentEventType.TOOL_STARTED,
            AgentEventType.TOOL_COMPLETED,
            AgentEventType.PROCESS_EXITED);
    assertThat(events.get(1).payload())
        .containsEntry("name", "Bash")
        .containsEntry(
            "input",
            Map.of("command", "echo compilando; echo hecho > salida.txt; echo fallo >&2; exit 3"));
    assertThat(events.get(2).payload())
        .containsEntry("isError", true)
        .containsEntry("output", "compilando\nfallo\n");
    assertThat(exit.payload()).containsEntry("exitCode", 3).doesNotContainKey("error");

    Map<ArtifactType, Journal.PendingArtifact> artifacts = artifactsOf(id);
    assertThat(artifacts)
        .containsOnlyKeys(
            ArtifactType.PROMPT, ArtifactType.LOG, ArtifactType.GIT_CHANGES, ArtifactType.DIFF);
    assertThat(Files.readString(artifacts.get(ArtifactType.LOG).file()))
        .isEqualTo("compilando\nfallo\n");
    assertThat(artifacts.get(ArtifactType.LOG).upload().metadata()).containsEntry("exitCode", 3);
    assertThat(Files.readString(artifacts.get(ArtifactType.DIFF).file())).contains("+hecho");
  }

  @Test
  void aCommandContinuesTheWorktreeOfAPreviousStage() throws Exception {
    UUID first = UUID.randomUUID();
    executor("02-tools", Map.of("FAKE_CLAUDE_APPLY", "1"))
        .start(TestAgents.start(first, repo.toString(), null));
    awaitExit(first);
    String path = eventsOf(first).getFirst().payload().get("path").toString();
    String branch = eventsOf(first).getFirst().payload().get("branch").toString();

    UUID id = UUID.randomUUID();
    executor.start(
        TestAgents.command(
            id, repo.toString(), "grep -c 'a + b' calc.py", null, new WorkspaceRef(path, branch)));

    NormalizedEvent exit = awaitExit(id);
    assertThat(eventsOf(id).getFirst().payload()).containsEntry("path", path);
    assertThat(eventsOf(id).get(2).payload())
        .containsEntry("isError", false)
        .containsEntry("output", "1\n");
    assertThat(exit.payload()).containsEntry("exitCode", 0);
  }

  @Test
  void aPushStageCommitsAndPushesTheWorktreeOfThePreviousStage() throws Exception {
    UUID first = UUID.randomUUID();
    executor("02-tools", Map.of("FAKE_CLAUDE_APPLY", "1"))
        .start(TestAgents.start(first, repo.toString(), null));
    awaitExit(first);
    Map<String, Object> ready = eventsOf(first).getFirst().payload();
    String path = ready.get("path").toString();
    Path remote = dir.resolve("remote.git");
    TestRepos.git(dir, "init", "-q", "--bare", remote.toString());
    List<UUID> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
    executor.pushTokens(
        agentRunId -> {
          asked.add(agentRunId);
          return "ghs_tokenDePruebaDelPush000000000000000";
        });

    UUID id = UUID.randomUUID();
    executor.start(
        TestAgents.push(
            id,
            repo.toString(),
            new WorkspaceRef(path, ready.get("branch").toString()),
            new PushBranch(
                remote.toUri().toString(),
                "skynet/tkm-1",
                ready.get("baseCommit").toString(),
                false,
                "TKM-1: cambios")));

    NormalizedEvent exit = awaitExit(id);
    assertThat(exit.payload()).containsEntry("exitCode", 0);
    assertThat(asked).containsExactly(id);
    List<NormalizedEvent> events = eventsOf(id);
    assertThat(events)
        .extracting(NormalizedEvent::type)
        .contains(
            AgentEventType.WORKSPACE_READY,
            AgentEventType.TOOL_STARTED,
            AgentEventType.TOOL_COMPLETED,
            AgentEventType.GIT_PUSHED,
            AgentEventType.PROCESS_EXITED);
    NormalizedEvent pushed =
        events.stream()
            .filter(e -> e.type() == AgentEventType.GIT_PUSHED)
            .findFirst()
            .orElseThrow();
    assertThat(pushed.payload())
        .containsEntry("branch", "skynet/tkm-1")
        .containsEntry("sha", TestRepos.git(remote, "rev-parse", "refs/heads/skynet/tkm-1"))
        .containsEntry("commits", 1)
        // El agente de prueba ya confirma sus cambios.
        .containsEntry("committed", false);
  }

  @Test
  void aPushWithoutATokenFails() throws Exception {
    UUID first = UUID.randomUUID();
    executor("02-tools", Map.of("FAKE_CLAUDE_APPLY", "1"))
        .start(TestAgents.start(first, repo.toString(), null));
    awaitExit(first);
    Map<String, Object> ready = eventsOf(first).getFirst().payload();
    executor.pushTokens(
        agentRunId -> {
          throw new java.io.IOException("401");
        });

    UUID id = UUID.randomUUID();
    executor.start(
        TestAgents.push(
            id,
            repo.toString(),
            new WorkspaceRef(ready.get("path").toString(), ready.get("branch").toString()),
            new PushBranch(
                "https://github.com/guilu/demo-repo.git",
                "skynet/tkm-1",
                ready.get("baseCommit").toString(),
                false,
                "TKM-1: cambios")));

    NormalizedEvent exit = awaitExit(id);
    assertThat(exit.payload().get("error").toString())
        .startsWith("No se pudo subir la rama")
        .contains("token de GitHub");
    assertThat(exit.payload()).containsEntry("failure", "error");
  }

  @Test
  void aCommandThatRunsTooLongIsTerminated() throws Exception {
    UUID id = UUID.randomUUID();
    executor("02-tools")
        .start(TestAgents.command(id, repo.toString(), "sleep 30", Duration.ofMillis(300), null));

    NormalizedEvent exit = awaitExit(id);
    assertThat(exit.payload().get("error").toString()).startsWith("Se agotó el tiempo máximo");
  }

  @Test
  void cancelTerminatesARunningCommand() throws Exception {
    UUID id = UUID.randomUUID();
    executor("02-tools").start(TestAgents.command(id, repo.toString(), "sleep 30", null, null));
    awaitRunning(id);

    executor.cancel(id);

    NormalizedEvent exit = awaitExit(id);
    assertThat(exit.payload()).doesNotContainKey("error");
    assertThat(exit.payload().get("signal")).isIn("SIGTERM", "SIGKILL");
  }

  @Test
  void verifyRunsTheCommandInTheWorktreeAndReadsItsTestReports() throws Exception {
    Path worktree = finishedWorktree();
    UUID agent = UUID.randomUUID();
    String command =
        """
        mkdir -p build/test-results/test
        cat > build/test-results/test/TEST-Calc.xml <<'XML'
        <testsuite name="Calc"><testcase name="ok"/><testcase name="ko"><failure message="no"/></testcase></testsuite>
        XML
        echo salida; echo error >&2; exit 3
        """;
    RunnerCommand verify = verify(agent, worktree, command, Duration.ofSeconds(30));

    executor.verify(verify);
    NormalizedEvent completed = awaitVerification(agent);

    List<NormalizedEvent> events = eventsOf(agent);
    assertThat(events)
        .extracting(NormalizedEvent::type)
        .containsExactly(
            AgentEventType.VERIFICATION_STARTED, AgentEventType.VERIFICATION_COMPLETED);
    assertThat(completed.payload())
        .containsEntry("verificationRunId", verify.verify().verificationRunId().toString())
        .containsEntry("exitCode", 3)
        .containsEntry("tests", Map.of("total", 2, "failed", 1, "errors", 0, "skipped", 0))
        .doesNotContainKey("error");
    Map<ArtifactType, Journal.PendingArtifact> artifacts = artifactsOf(agent);
    assertThat(artifacts).containsOnlyKeys(ArtifactType.VERIFICATION_LOG, ArtifactType.TEST_REPORT);
    assertThat(artifacts.get(ArtifactType.VERIFICATION_LOG).file()).hasContent("salida\nerror");
    assertThat(artifacts.get(ArtifactType.TEST_REPORT).upload().verificationRunId())
        .isEqualTo(verify.verify().verificationRunId());
    assertThat(journal.processes()).isEmpty();

    executor.verify(verify);
    Thread.sleep(300);
    assertThat(eventsOf(agent)).hasSize(2);
  }

  @Test
  void aVerificationThatRunsTooLongIsTerminated() throws Exception {
    Path worktree = finishedWorktree();
    UUID agent = UUID.randomUUID();

    executor.verify(verify(agent, worktree, "sleep 30", Duration.ofMillis(300)));
    NormalizedEvent completed = awaitVerification(agent);

    assertThat((String) completed.payload().get("error")).startsWith("Se agotó el tiempo máximo");
    assertThat(completed.payload().get("signal")).isIn("SIGTERM", "SIGKILL");
  }

  @Test
  void aVerificationDoesNotRunWhileTheWorktreeIsBusyOrUnknown() throws Exception {
    UUID running = UUID.randomUUID();
    executor("07-cancelled").start(TestAgents.start(running, repo.toString(), null));
    awaitRunning(running);
    Path worktree = Path.of((String) eventsOf(running).getFirst().payload().get("path"));

    UUID busy = UUID.randomUUID();
    executor.verify(verify(busy, worktree, "true", Duration.ofSeconds(30)));
    assertThat((String) awaitVerification(busy).payload().get("error"))
        .contains("Hay una invocación en curso");

    UUID unknown = UUID.randomUUID();
    executor.verify(verify(unknown, repo, "true", Duration.ofSeconds(30)));
    assertThat((String) awaitVerification(unknown).payload().get("error"))
        .startsWith("No se pudo verificar el worktree");
    assertThat(eventsOf(unknown))
        .extracting(NormalizedEvent::type)
        .containsExactly(AgentEventType.VERIFICATION_COMPLETED);

    executor.cancel(running);
    awaitExit(running);
  }

  @Test
  void aStageVerificationRunsItsChecksWithoutTheRepositoryCommand() throws Exception {
    UUID finished = UUID.randomUUID();
    executor("01-simple-text").start(TestAgents.start(finished, repo.toString(), null));
    awaitExit(finished);
    Map<String, Object> ready = eventsOf(finished).getFirst().payload();
    Path worktree = Path.of((String) ready.get("path"));
    UUID agent = UUID.randomUUID();
    RunnerCommand verify =
        RunnerCommand.verify(
            UUID.randomUUID(),
            agent,
            Instant.now(),
            new RunVerification(
                UUID.randomUUID(),
                worktree.toString(),
                null,
                List.of(),
                Duration.ofSeconds(30),
                List.of(
                    VerificationCheck.withPaths("artifacts", List.of("calc.py")),
                    VerificationCheck.named("diff"),
                    VerificationCheck.command("echo comprobado; test -f calc.py")),
                (String) ready.get("baseCommit")));

    executor.verify(verify);
    NormalizedEvent completed = awaitVerification(agent);

    assertThat(eventsOf(agent).getFirst().payload())
        .containsEntry("checks", List.of("artifacts", "diff", "command"))
        .doesNotContainKey("command");
    assertThat(completed.payload())
        .containsEntry(
            "checks",
            List.of(
                Map.of("name", "artifacts", "target", "calc.py", "passed", true),
                Map.of(
                    "name",
                    "diff",
                    "passed",
                    false,
                    "reason",
                    "No hay cambios respecto al inicio de la fase"),
                Map.of(
                    "name",
                    "command",
                    "target",
                    "echo comprobado; test -f calc.py",
                    "passed",
                    true)))
        .doesNotContainKeys("error", "exitCode");
    Map<ArtifactType, Journal.PendingArtifact> artifacts = artifactsOf(agent);
    assertThat(artifacts)
        .containsOnlyKeys(ArtifactType.VERIFICATION_LOG, ArtifactType.DECLARED_FILE);
    assertThat(artifacts.get(ArtifactType.VERIFICATION_LOG).upload().name())
        .isEqualTo("check-1.log");
    assertThat(artifacts.get(ArtifactType.VERIFICATION_LOG).file()).hasContent("comprobado");
    // El fichero declarado en `artifacts`, tal como quedó en el worktree.
    Journal.PendingArtifact declared = artifacts.get(ArtifactType.DECLARED_FILE);
    assertThat(declared.upload().name()).isEqualTo("calc.py");
    assertThat(declared.upload().verificationRunId())
        .isEqualTo(verify.verify().verificationRunId());
    assertThat(declared.upload().mediaType()).isEqualTo("application/octet-stream");
    assertThat(declared.upload().metadata())
        .containsEntry("path", "calc.py")
        .containsEntry("glob", "calc.py");
    assertThat(Files.readAllBytes(declared.file()))
        .isEqualTo(Files.readAllBytes(worktree.resolve("calc.py")));
  }

  /** Worktree de una invocación ya terminada. */
  private Path finishedWorktree() throws InterruptedException {
    UUID id = UUID.randomUUID();
    executor("01-simple-text").start(TestAgents.start(id, repo.toString(), null));
    awaitExit(id);
    return Path.of((String) eventsOf(id).getFirst().payload().get("path"));
  }

  private static RunnerCommand verify(
      UUID agentRunId, Path worktree, String command, Duration timeout) {
    return RunnerCommand.verify(
        UUID.randomUUID(),
        agentRunId,
        Instant.now(),
        new RunVerification(
            UUID.randomUUID(),
            worktree.toString(),
            command,
            List.of("**/build/test-results/**/*.xml"),
            timeout));
  }

  private NormalizedEvent awaitVerification(UUID agentRunId) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      List<NormalizedEvent> events = eventsOf(agentRunId);
      if (!events.isEmpty()
          && events.getLast().type() == AgentEventType.VERIFICATION_COMPLETED
          && journal.unfinishedVerifications().isEmpty()) {
        return events.getLast();
      }
      Thread.sleep(50);
    }
    throw new AssertionError("La verificación no terminó: " + eventsOf(agentRunId));
  }

  private Map<ArtifactType, Journal.PendingArtifact> artifactsOf(UUID agentRunId) {
    Map<ArtifactType, Journal.PendingArtifact> artifacts = new HashMap<>();
    for (Journal.PendingArtifact artifact : journal.pendingArtifacts(1000)) {
      if (artifact.upload().agentRunId().equals(agentRunId)) {
        artifacts.put(artifact.upload().type(), artifact);
      }
    }
    return artifacts;
  }
}
