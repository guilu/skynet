package dev.skynet.controlplane.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skynet.controlplane.support.IntegrationTest;
import dev.skynet.protocol.AgentEventType;
import dev.skynet.protocol.NormalizedEvent;
import dev.skynet.protocol.runner.EventBatch;
import dev.skynet.protocol.runner.RunnerHeartbeat;
import dev.skynet.protocol.runner.RunnerRegistration;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;

/** Protocolo runner ↔ control plane (§4.3): registro, órdenes con long-poll e ingestión. */
class RunnerProtocolIT extends IntegrationTest {

  @Test
  void registrationRequiresTheSharedTokenAndReRegisteringRotatesTheToken() {
    assertStatus(
        HttpStatus.UNAUTHORIZED,
        () ->
            http.post()
                .uri("/api/runner/register")
                .body(new RunnerRegistration("laptop", "wrong", "dev", 1, null))
                .retrieve()
                .toBodilessEntity());

    Runner first = register("laptop", 1);
    heartbeat(first);
    assertStatus(
        HttpStatus.UNAUTHORIZED,
        () ->
            http.post()
                .uri("/api/runner/heartbeat")
                .body(new RunnerHeartbeat(1, List.of()))
                .retrieve()
                .toBodilessEntity());

    Runner again = register("laptop", 2);
    assertThat(again.id()).isEqualTo(first.id());
    assertStatus(HttpStatus.UNAUTHORIZED, () -> heartbeat(first));
    heartbeat(again);
  }

  @Test
  void runFromLaunchToCompletion() {
    Runner runner = register("laptop", 1);
    Launched run = launch("Arregla add()");

    List<JsonNode> commands = poll(runner, 1);
    assertThat(commands).hasSize(1);
    JsonNode start = commands.getFirst();
    assertThat(start.path("type").asString()).isEqualTo("START");
    assertThat(start.path("agentRunId").asString()).isEqualTo(run.agentId().toString());
    JsonNode payload = start.path("start");
    assertThat(payload.path("prompt").asString()).isEqualTo("Arregla add()");
    assertThat(payload.path("repositoryPath").asString()).isEqualTo("/repos/demo");
    assertThat(payload.path("baseBranch").asString()).isEqualTo("main");
    assertThat(payload.path("workItemKey").asString()).isEqualTo("RUN-1");
    assertThat(payload.path("permissionMode").asString()).isEqualTo("dontAsk");
    assertThat(payload.path("workflowRunId").asString()).isEqualTo(run.runId().toString());

    JsonNode agent = agent(run);
    assertThat(agent.path("status").asString()).isEqualTo("STARTING");
    assertThat(agent.path("runnerId").asString()).isEqualTo(runner.id().toString());
    assertThat(payload.path("sessionId").asString())
        .isEqualTo(agent.path("providerSessionId").asString());
    assertThat(stageStatus(run)).isEqualTo("STARTING");
    ack(runner, start);

    Events events = new Events(run.agentId());
    events.add(
        AgentEventType.SESSION_STARTED,
        Map.of("sessionId", payload.path("sessionId").asString(), "model", "claude-opus-5-5"));
    events.add(AgentEventType.TOOL_STARTED, Map.of("toolUseId", "t1", "name", "Read"));
    JsonNode result = send(runner, events.batch());
    assertThat(result.path("accepted").asInt()).isEqualTo(2);
    assertThat(agent(run).path("status").asString()).isEqualTo("EXECUTING");
    assertThat(stageStatus(run)).isEqualTo("RUNNING");

    events.add(AgentEventType.TOOL_COMPLETED, Map.of("toolUseId", "t1", "isError", false));
    events.add(
        AgentEventType.RESULT,
        Map.of(
            "subtype",
            "success",
            "isError",
            false,
            "numTurns",
            4,
            "costUsdCumulative",
            new BigDecimal("0.0573992"),
            "usage",
            Map.of("input", 6, "output", 488)));
    events.add(AgentEventType.PROCESS_EXITED, Map.of("exitCode", 0));
    // El lote completo, con los dos primeros repetidos: se ignoran sin efectos.
    result = send(runner, events.batch());
    assertThat(result.path("accepted").asInt()).isEqualTo(3);
    assertThat(result.path("duplicates").asInt()).isEqualTo(2);

    agent = agent(run);
    assertThat(agent.path("status").asString()).isEqualTo("COMPLETED");
    assertThat(agent.path("model").asString()).isEqualTo("claude-opus-5-5");
    assertThat(agent.path("numTurns").asInt()).isEqualTo(4);
    assertThat(agent.path("inputTokens").asLong()).isEqualTo(6);
    assertThat(agent.path("outputTokens").asLong()).isEqualTo(488);
    assertThat(agent.path("costUsd").decimalValue()).isEqualByComparingTo("0.0573992");
    assertThat(agent.path("exitCode").asInt()).isZero();
    assertThat(agent.path("finishedAt").isNull()).isFalse();
    JsonNode after = get("/api/workflow-runs/" + run.runId());
    assertThat(after.path("status").asString()).isEqualTo("SUCCEEDED");
    assertThat(after.path("stages").get(0).path("status").asString()).isEqualTo("SUCCEEDED");

    assertThat(eventTypes(run))
        .containsSubsequence(
            "agent.spawned",
            "agent.status.changed", // QUEUED → STARTING
            "stage.status.changed", // READY → STARTING
            "agent.session.started",
            "agent.status.changed", // → THINKING
            "stage.status.changed", // → RUNNING
            "agent.tool.started",
            "agent.tool.completed",
            "agent.result",
            "agent.process.exited",
            "agent.status.changed", // → COMPLETED
            "stage.status.changed",
            "workflow.status.changed")
        .containsOnlyOnce("agent.session.started", "agent.process.exited");
  }

  @Test
  void failedResultFailsTheRun() {
    Runner runner = register("laptop", 1);
    Launched run = launch("x");
    poll(runner, 1);

    Events events = new Events(run.agentId());
    events.add(AgentEventType.SESSION_STARTED, Map.of("sessionId", "s"));
    events.add(
        AgentEventType.RESULT,
        Map.of(
            "subtype",
            "error_max_turns",
            "isError",
            true,
            "errors",
            List.of("Reached maximum number of turns (1)")));
    events.add(AgentEventType.PROCESS_EXITED, Map.of("exitCode", 1));
    send(runner, events.batch());

    JsonNode agent = agent(run);
    assertThat(agent.path("status").asString()).isEqualTo("FAILED");
    assertThat(agent.path("resultSubtype").asString()).isEqualTo("error_max_turns");
    assertThat(agent.path("error").asString()).isEqualTo("Reached maximum number of turns (1)");
    assertThat(get("/api/workflow-runs/" + run.runId()).path("status").asString())
        .isEqualTo("FAILED");
  }

  @Test
  void processExitWithoutResultFails() {
    Runner runner = register("laptop", 1);
    Launched run = launch("x");
    poll(runner, 1);

    Events events = new Events(run.agentId());
    events.add(AgentEventType.PROCESS_EXITED, Map.of("exitCode", 137, "signal", "SIGKILL"));
    send(runner, events.batch());

    JsonNode agent = agent(run);
    assertThat(agent.path("status").asString()).isEqualTo("FAILED");
    assertThat(agent.path("error").asString()).contains("sin resultado").contains("SIGKILL");
    assertThat(stageStatus(run)).isEqualTo("FAILED");
  }

  @Test
  void aRunnerErrorFailsTheAgentWithThatError() {
    Runner runner = register("laptop", 1);
    Launched run = launch("x");
    poll(runner, 1);

    Events events = new Events(run.agentId());
    events.add(
        AgentEventType.PROCESS_EXITED,
        Map.of(
            "error", "No se pudo preparar el worktree: El repositorio no existe en este runner"));
    send(runner, events.batch());

    JsonNode agent = agent(run);
    assertThat(agent.path("status").asString()).isEqualTo("FAILED");
    assertThat(agent.path("error").asString()).startsWith("No se pudo preparar el worktree");
    assertThat(agent.path("exitCode").isNull() || agent.path("exitCode").isMissingNode()).isTrue();
    assertThat(stageStatus(run)).isEqualTo("FAILED");
  }

  @Test
  void aTimeoutFailsTheAgentEvenIfItExitedCleanly() {
    Runner runner = register("laptop", 1);
    Launched run = launch("x");
    poll(runner, 1);

    Events events = new Events(run.agentId());
    events.add(
        AgentEventType.WORKSPACE_READY,
        Map.of("path", "/w/r/a", "branch", "skynet/adhoc/abcd1234", "baseCommit", "abc"));
    events.add(AgentEventType.SESSION_STARTED, Map.of("sessionId", "s"));
    events.add(
        AgentEventType.PROCESS_EXITED,
        Map.of("exitCode", 143, "signal", "SIGTERM", "error", "Se agotó el tiempo máximo (PT30M)"));
    send(runner, events.batch());

    JsonNode agent = agent(run);
    assertThat(agent.path("status").asString()).isEqualTo("FAILED");
    assertThat(agent.path("error").asString()).contains("tiempo máximo");
    assertThat(eventTypes(run)).contains("agent.workspace.ready");
  }

  @Test
  void longPollReturnsAsSoonAsARunIsLaunched() throws Exception {
    Runner runner = register("laptop", 1);
    CompletableFuture<List<JsonNode>> pending =
        CompletableFuture.supplyAsync(() -> poll(runner, 20));
    Thread.sleep(300);
    assertThat(pending).isNotDone();

    long t0 = System.nanoTime();
    launch("x");
    List<JsonNode> commands = pending.get(10, TimeUnit.SECONDS);
    assertThat(commands).extracting(c -> c.path("type").asString()).containsExactly("START");
    assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(5));
  }

  @Test
  void capacityLimitsHowManyStartsARunnerReceives() {
    Runner runner = register("laptop", 1);
    Launched first = launch("uno");
    Launched second = launch("dos");

    assertThat(poll(runner, 0))
        .extracting(c -> c.path("agentRunId").asString())
        .containsExactly(first.agentId().toString());
    assertThat(poll(runner, 0)).isEmpty();
    assertThat(agent(second).path("status").asString()).isEqualTo("QUEUED");

    Events events = new Events(first.agentId());
    events.add(AgentEventType.PROCESS_EXITED, Map.of("exitCode", 1));
    send(runner, events.batch());

    assertThat(poll(runner, 0))
        .extracting(c -> c.path("agentRunId").asString())
        .containsExactly(second.agentId().toString());
  }

  @Test
  void unacknowledgedCommandsAreDeliveredAgain() throws Exception {
    Runner runner = register("laptop", 1);
    launch("x");
    JsonNode start = poll(runner, 0).getFirst();
    assertThat(poll(runner, 0)).isEmpty();

    Thread.sleep(1_200); // skynet.runner.redeliver-after = 1s en los tests
    List<JsonNode> again = poll(runner, 0);
    assertThat(again)
        .extracting(c -> c.path("id").asString())
        .containsExactly(start.path("id").asString());

    ack(runner, start);
    ack(runner, start); // idempotente
    Thread.sleep(1_200);
    assertThat(poll(runner, 0)).isEmpty();
  }

  @Test
  void cancellingARunningAgentSendsACancelAndFinishesWhenTheProcessExits() {
    Runner runner = register("laptop", 1);
    Launched run = launch("x");
    ack(runner, poll(runner, 0).getFirst());
    Events events = new Events(run.agentId());
    events.add(AgentEventType.SESSION_STARTED, Map.of("sessionId", "s"));
    send(runner, events.batch());

    JsonNode cancelled = post("/api/agent-runs/" + run.agentId() + "/cancel", Map.of());
    assertThat(cancelled.path("agent").path("status").asString()).isEqualTo("THINKING");
    assertThat(cancelled.path("agent").path("cancelRequestedAt").isNull()).isFalse();
    // Pedirlo otra vez no crea otra orden.
    post("/api/agent-runs/" + run.agentId() + "/cancel", Map.of());

    List<JsonNode> commands = poll(runner, 0);
    assertThat(commands).extracting(c -> c.path("type").asString()).containsExactly("CANCEL");
    assertThat(commands.getFirst().path("agentRunId").asString())
        .isEqualTo(run.agentId().toString());
    ack(runner, commands.getFirst());

    events.add(AgentEventType.PROCESS_EXITED, Map.of("exitCode", 143, "signal", "SIGTERM"));
    send(runner, events.batch());

    assertThat(agent(run).path("status").asString()).isEqualTo("CANCELLED");
    JsonNode after = get("/api/workflow-runs/" + run.runId());
    assertThat(after.path("status").asString()).isEqualTo("CANCELLED");
    assertThat(after.path("stages").get(0).path("status").asString()).isEqualTo("CANCELLED");
    assertStatus(
        HttpStatus.CONFLICT, () -> post("/api/agent-runs/" + run.agentId() + "/cancel", Map.of()));
  }

  @Test
  void cancellingAQueuedAgentWithdrawsItsStart() {
    Runner runner = register("laptop", 1);
    Launched run = launch("x");
    post("/api/agent-runs/" + run.agentId() + "/cancel", Map.of());

    assertThat(poll(runner, 0)).isEmpty();
    assertThat(
            jdbc.sql("SELECT status FROM runner_command WHERE agent_run_id = ?")
                .param(run.agentId())
                .query(String.class)
                .single())
        .isEqualTo("CANCELLED");
  }

  @Test
  void eventsForAgentsOfOtherRunnersAreRejected() {
    Runner owner = register("owner", 1);
    Runner intruder = register("intruder", 1);
    Launched run = launch("x");
    poll(owner, 0);

    Events events = new Events(run.agentId());
    events.add(AgentEventType.SESSION_STARTED, Map.of("sessionId", "s"));
    Events unknown = new Events(UUID.randomUUID());
    unknown.add(AgentEventType.SESSION_STARTED, Map.of("sessionId", "s"));

    JsonNode result =
        send(
            intruder,
            new EventBatch(
                List.of(events.batch().events().getFirst(), unknown.batch().events().getFirst())));
    assertThat(result.path("accepted").asInt()).isZero();
    assertThat(result.path("rejected"))
        .extracting(r -> r.path("reason").asString())
        .containsExactly("not-assigned-to-runner", "unknown-agent-run");
    assertThat(agent(run).path("status").asString()).isEqualTo("STARTING");
  }

  // --- utilidades ---

  private record Runner(UUID id, String token) {}

  private record Launched(UUID runId, UUID agentId) {}

  /** Eventos de una invocación con {@code seq} consecutivo, como los genera el runner. */
  private static final class Events {
    private final UUID agentRunId;
    private final List<NormalizedEvent> all = new ArrayList<>();

    Events(UUID agentRunId) {
      this.agentRunId = agentRunId;
    }

    void add(AgentEventType type, Map<String, ?> payload) {
      all.add(
          new NormalizedEvent(
              UUID.randomUUID(),
              agentRunId,
              all.size() + 1,
              Instant.now(),
              type,
              new LinkedHashMap<>(payload)));
    }

    EventBatch batch() {
      return new EventBatch(all);
    }
  }

  private int launches;

  private Launched launch(String prompt) {
    if (launches++ == 0) {
      String projectId =
          post("/api/projects", Map.of("key", "RUN", "name", "Runs")).path("id").asString();
      repositoryId =
          post(
                  "/api/projects/" + projectId + "/repositories",
                  Map.of("name", "demo", "localPath", "/repos/demo"))
              .path("id")
              .asString();
      workItemId =
          post("/api/projects/" + projectId + "/work-items", Map.of("title", "T", "type", "BUG"))
              .path("id")
              .asString();
    }
    JsonNode run =
        post(
            "/api/work-items/" + workItemId + "/runs",
            Map.of("repositoryId", repositoryId, "prompt", prompt));
    return new Launched(
        UUID.fromString(run.path("id").asString()),
        UUID.fromString(run.path("stages").get(0).path("agents").get(0).path("id").asString()));
  }

  private String repositoryId;
  private String workItemId;

  private Runner register(String name, int capacity) {
    JsonNode registered =
        http.post()
            .uri("/api/runner/register")
            .body(
                new RunnerRegistration(name, RUNNER_REGISTRATION_TOKEN, "dev", capacity, "2.1.288"))
            .retrieve()
            .body(JsonNode.class);
    return new Runner(
        UUID.fromString(registered.path("runnerId").asString()),
        registered.path("token").asString());
  }

  private void heartbeat(Runner runner) {
    http.post()
        .uri("/api/runner/heartbeat")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + runner.token())
        .body(new RunnerHeartbeat(1, List.of()))
        .retrieve()
        .toBodilessEntity();
  }

  private List<JsonNode> poll(Runner runner, int waitSeconds) {
    List<JsonNode> list = new ArrayList<>();
    http.get()
        .uri("/api/runner/commands?waitSeconds=" + waitSeconds)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + runner.token())
        .retrieve()
        .body(JsonNode.class)
        .forEach(list::add);
    return list;
  }

  private void ack(Runner runner, JsonNode command) {
    http.post()
        .uri("/api/runner/commands/" + command.path("id").asString() + "/ack")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + runner.token())
        .retrieve()
        .toBodilessEntity();
  }

  private JsonNode send(Runner runner, EventBatch batch) {
    return http.post()
        .uri("/api/runner/events")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + runner.token())
        .body(batch)
        .retrieve()
        .body(JsonNode.class);
  }

  private JsonNode agent(Launched run) {
    return get("/api/agent-runs/" + run.agentId()).path("agent");
  }

  private String stageStatus(Launched run) {
    return get("/api/workflow-runs/" + run.runId()).path("stages").get(0).path("status").asString();
  }

  private List<String> eventTypes(Launched run) {
    List<String> types = new ArrayList<>();
    get("/api/events?workflowRunId=" + run.runId() + "&limit=500")
        .forEach(e -> types.add(e.path("type").asString()));
    return types;
  }

  private JsonNode post(String path, Object body) {
    return http.post().uri(path).body(body).retrieve().body(JsonNode.class);
  }

  private JsonNode get(String path) {
    return http.get().uri(path).retrieve().body(JsonNode.class);
  }

  private static void assertStatus(HttpStatus expected, Runnable call) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            HttpClientErrorException.class,
            e -> assertThat(e.getStatusCode().value()).isEqualTo(expected.value()));
  }
}
