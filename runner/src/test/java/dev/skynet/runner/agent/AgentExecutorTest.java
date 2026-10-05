package dev.skynet.runner.agent;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skynet.protocol.AgentEventType;
import dev.skynet.protocol.NormalizedEvent;
import dev.skynet.protocol.runner.ResumeFrom;
import dev.skynet.protocol.runner.RunnerCommand;
import dev.skynet.runner.TestAgents;
import dev.skynet.runner.TestRepos;
import dev.skynet.runner.journal.Journal;
import dev.skynet.runner.supervisor.ProcessSupervisor;
import dev.skynet.runner.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
    executor =
        new AgentExecutor(
            journal,
            new ProcessSupervisor(),
            new WorkspaceManager(dir.resolve("workspaces")),
            TestAgents.fakeClaude(),
            TestAgents.fakeClaudeEnv(fixture, dir.resolve("claude")),
            dir.resolve("logs"),
            Duration.ofSeconds(2),
            () -> {});
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
}
