package dev.skynet.runner.agent;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skynet.protocol.AgentEventType;
import dev.skynet.protocol.NormalizedEvent;
import dev.skynet.protocol.runner.AgentLimits;
import dev.skynet.protocol.runner.ArtifactType;
import dev.skynet.protocol.runner.CleanupWorkspace;
import dev.skynet.protocol.runner.ResumeFrom;
import dev.skynet.protocol.runner.RunVerification;
import dev.skynet.protocol.runner.RunnerCommand;
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
            TestAgents.fakeClaude(),
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
