package dev.skynet.controlplane.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skynet.controlplane.support.IntegrationTest;
import dev.skynet.protocol.AgentEventType;
import dev.skynet.protocol.NormalizedEvent;
import dev.skynet.protocol.runner.EventBatch;
import dev.skynet.protocol.runner.RunnerHeartbeat;
import dev.skynet.protocol.runner.RunnerRegistration;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;

/** Proyecciones de lectura de M3 (ADR-0001): lo que la web muestra sin deducir estado. */
class ReadProjectionsIT extends IntegrationTest {

  @Autowired UnresponsiveDetector detector;

  @Test
  void tokensActivityAndTotalsAreLiveBeforeTheResult() {
    Runner runner = register("laptop");
    Launched run = launch("ALPHA", "Arregla add()");
    startOn(runner);

    Events events = new Events(run.agentId());
    events.add(AgentEventType.SESSION_STARTED, Map.of("sessionId", "s-1", "model", "m"));
    events.add(
        AgentEventType.TOOL_STARTED,
        Map.of(
            "toolUseId", "t1",
            "name", "Read",
            "usage", Map.of("input", 2, "output", 16, "cacheRead", 100, "cacheCreation", 50)));
    events.add(
        AgentEventType.MESSAGE_RECEIVED,
        Map.of(
            "text",
            "Leyendo",
            "usage",
            Map.of("input", 2, "output", 30, "cacheRead", 200, "cacheCreation", 0)));
    send(runner, events.batch());

    JsonNode agent = agent(run);
    assertThat(agent.path("status").asString()).isEqualTo("THINKING");
    assertThat(agent.path("inputTokens").asLong()).isEqualTo(4);
    assertThat(agent.path("outputTokens").asLong()).isEqualTo(46);
    assertThat(agent.path("cacheReadTokens").asLong()).isEqualTo(300);
    assertThat(agent.path("cacheCreationTokens").asLong()).isEqualTo(50);
    assertThat(agent.path("currentTool").asString()).isEqualTo("Read");
    assertThat(agent.path("lastEventType").asString()).isEqualTo("agent.message.received");

    JsonNode view = get("/api/workflow-runs/" + run.runId());
    assertThat(view.path("workItemKey").asString()).isEqualTo("ALPHA-1");
    assertThat(view.path("workItemTitle").asString()).isEqualTo("T");
    assertThat(view.path("currentAgentRunId").asString()).isEqualTo(run.agentId().toString());
    assertThat(view.path("currentStageRunId").asString())
        .isEqualTo(view.path("stages").get(0).path("id").asString());
    assertThat(view.path("totals").path("cacheReadTokens").asLong()).isEqualTo(300);
    assertThat(view.path("totals").path("costUsd").isNull()).isTrue();

    events.add(AgentEventType.TOOL_COMPLETED, Map.of("toolUseId", "t1", "isError", false));
    send(runner, events.batch());
    assertThat(agent(run).path("currentTool").isNull()).isTrue();

    // El resultado sustituye la suma en vivo por los totales del proveedor.
    events.add(
        AgentEventType.RESULT,
        Map.of(
            "subtype",
            "success",
            "isError",
            false,
            "numTurns",
            2,
            "costUsdCumulative",
            new BigDecimal("0.0421"),
            "usage",
            Map.of("input", 6, "output", 488, "cacheRead", 1000, "cacheCreation", 60)));
    events.add(AgentEventType.PROCESS_EXITED, Map.of("exitCode", 0));
    send(runner, events.batch());

    agent = agent(run);
    assertThat(agent.path("status").asString()).isEqualTo("COMPLETED");
    assertThat(agent.path("inputTokens").asLong()).isEqualTo(6);
    assertThat(agent.path("outputTokens").asLong()).isEqualTo(488);
    assertThat(agent.path("cacheReadTokens").asLong()).isEqualTo(1000);
    assertThat(agent.path("cacheCreationTokens").asLong()).isEqualTo(60);
    view = get("/api/workflow-runs/" + run.runId());
    assertThat(view.path("currentAgentRunId").isNull()).isTrue();
    assertThat(view.path("currentStageRunId").isNull()).isTrue();
    assertThat(view.path("totals").path("costUsd").decimalValue()).isEqualByComparingTo("0.0421");
    assertThat(view.path("totals").path("outputTokens").asLong()).isEqualTo(488);
  }

  @Test
  void listsRunsByStatusAndProjectNewestFirst() {
    Launched first = launch("ALPHA", "uno");
    Launched second = launch("ALPHA", "dos");
    Launched other = launch("BETA", "tres");
    post("/api/agent-runs/" + first.agentId() + "/cancel", Map.of());

    JsonNode all = get("/api/workflow-runs");
    assertThat(all.path("total").asLong()).isEqualTo(3);
    assertThat(ids(all)).containsExactly(other.runId(), second.runId(), first.runId());

    JsonNode running = get("/api/workflow-runs?status=RUNNING&status=PENDING");
    assertThat(ids(running)).containsExactly(other.runId(), second.runId());

    JsonNode cancelled =
        get("/api/workflow-runs?status=CANCELLED&projectId=" + projectIds.get("ALPHA"));
    assertThat(ids(cancelled)).containsExactly(first.runId());
    assertThat(
            get("/api/workflow-runs?status=CANCELLED&projectId=" + projectIds.get("BETA"))
                .path("total")
                .asLong())
        .isZero();

    JsonNode page = get("/api/workflow-runs?size=1&page=1");
    assertThat(page.path("total").asLong()).isEqualTo(3);
    assertThat(page.path("size").asInt()).isEqualTo(1);
    assertThat(ids(page)).containsExactly(second.runId());
  }

  @Test
  void runnersReportLoadAndStaleness() {
    Runner runner = register("laptop");
    Launched run = launch("ALPHA", "x");
    startOn(runner);

    JsonNode runners = get("/api/runners");
    assertThat(runners).hasSize(1);
    JsonNode view = runners.get(0);
    assertThat(view.path("name").asString()).isEqualTo("laptop");
    assertThat(view.path("status").asString()).isEqualTo("ONLINE");
    assertThat(view.path("activeAgents").asInt()).isEqualTo(1);
    assertThat(view.path("providerVersion").asString()).isEqualTo("2.1.288");

    jdbc.sql("UPDATE runner SET last_heartbeat_at = ? WHERE id = ?")
        .params(Timestamp.from(Instant.now().minus(10, ChronoUnit.MINUTES)), runner.id())
        .update();
    assertThat(get("/api/runners").get(0).path("status").asString()).isEqualTo("STALE");

    JsonNode dashboard = get("/api/dashboard");
    assertThat(dashboard.path("staleRunners").get(0).path("id").asString())
        .isEqualTo(runner.id().toString());
    assertThat(dashboard.path("activeRunsTotal").asLong()).isEqualTo(1);
    assertThat(dashboard.path("activeRuns").get(0).path("id").asString())
        .isEqualTo(run.runId().toString());

    heartbeat(runner);
    assertThat(get("/api/runners").get(0).path("status").asString()).isEqualTo("ONLINE");
  }

  @Test
  void silentAgentsBecomeUnresponsiveAndRecoverWithTheirNextEvent() {
    Runner runner = register("laptop");
    Launched run = launch("ALPHA", "x");
    startOn(runner);
    Events events = new Events(run.agentId());
    events.add(AgentEventType.SESSION_STARTED, Map.of("sessionId", "s-1"));
    events.add(AgentEventType.TOOL_STARTED, Map.of("toolUseId", "t1", "name", "Bash"));
    send(runner, events.batch());

    // Con el umbral en el pasado, nadie está en silencio.
    assertThat(detector.detect(Instant.now().minus(1, ChronoUnit.HOURS))).isZero();
    assertThat(detector.detect(Instant.now().plusSeconds(1))).isEqualTo(1);
    assertThat(agent(run).path("status").asString()).isEqualTo("UNRESPONSIVE");
    // Ya no está activo: no se vuelve a marcar.
    assertThat(detector.detect(Instant.now().plusSeconds(1))).isZero();

    JsonNode unresponsive = get("/api/dashboard").path("unresponsiveAgents");
    assertThat(unresponsive).hasSize(1);
    assertThat(unresponsive.get(0).path("agentRunId").asString())
        .isEqualTo(run.agentId().toString());
    assertThat(unresponsive.get(0).path("workItemKey").asString()).isEqualTo("ALPHA-1");
    assertThat(lastStatusChange(run).path("reason").asString()).isEqualTo("no-activity");

    // Cualquier evento posterior lo devuelve a un estado activo.
    events.add(
        AgentEventType.FILE_CHANGED,
        Map.of("toolUseId", "t1", "path", "a.txt", "change", "created"));
    send(runner, events.batch());
    assertThat(agent(run).path("status").asString()).isEqualTo("THINKING");
    assertThat(lastStatusChange(run).path("reason").asString()).isEqualTo("activity-resumed");
    assertThat(get("/api/dashboard").path("unresponsiveAgents")).isEmpty();
  }

  @Test
  void singleEventsAndHistoryBackwards() {
    Launched run = launch("ALPHA", "x");
    List<Long> sequences = new ArrayList<>();
    get("/api/events?workflowRunId=" + run.runId())
        .forEach(e -> sequences.add(e.path("sequence").asLong()));
    // workflow.started, stage.ready, agent.spawned
    assertThat(sequences).hasSize(3);

    JsonNode spawned = get("/api/events/" + sequences.get(2));
    assertThat(spawned.path("type").asString()).isEqualTo("agent.spawned");
    assertThat(spawned.path("payload").path("promptSha256").asString()).hasSize(64);
    assertThatThrownBy(() -> get("/api/events/999999999"))
        .isInstanceOfSatisfying(
            HttpClientErrorException.class,
            e -> assertThat(e.getStatusCode().value()).isEqualTo(404));

    List<Long> before = new ArrayList<>();
    get("/api/events?workflowRunId=" + run.runId() + "&before=" + sequences.get(2) + "&limit=1")
        .forEach(e -> before.add(e.path("sequence").asLong()));
    assertThat(before).containsExactly(sequences.get(1));
    before.clear();
    get("/api/events?workflowRunId=" + run.runId() + "&before=" + (sequences.get(2) + 1))
        .forEach(e -> before.add(e.path("sequence").asLong()));
    assertThat(before).containsExactlyElementsOf(sequences);
  }

  @Test
  void listsTheAdhocDefinition() {
    JsonNode definitions = get("/api/workflow-definitions");
    assertThat(definitions).hasSize(1);
    assertThat(definitions.get(0).path("key").asString()).isEqualTo("adhoc");
    assertThat(definitions.get(0).path("version").asInt()).isEqualTo(1);
    assertThat(definitions.get(0).path("sourceYaml").asString()).contains("stages:");
  }

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

  private final Map<String, String> projectIds = new LinkedHashMap<>();
  private final Map<String, String[]> repoAndWorkItem = new LinkedHashMap<>();

  private Launched launch(String projectKey, String prompt) {
    String[] ids =
        repoAndWorkItem.computeIfAbsent(
            projectKey,
            key -> {
              String projectId =
                  post("/api/projects", Map.of("key", key, "name", key)).path("id").asString();
              projectIds.put(key, projectId);
              String repositoryId =
                  post(
                          "/api/projects/" + projectId + "/repositories",
                          Map.of("name", "demo", "localPath", "/repos/" + key.toLowerCase()))
                      .path("id")
                      .asString();
              String workItemId =
                  post(
                          "/api/projects/" + projectId + "/work-items",
                          Map.of("title", "T", "type", "BUG"))
                      .path("id")
                      .asString();
              return new String[] {repositoryId, workItemId};
            });
    JsonNode run =
        post(
            "/api/work-items/" + ids[1] + "/runs",
            Map.of("repositoryId", ids[0], "prompt", prompt));
    return new Launched(
        UUID.fromString(run.path("id").asString()),
        UUID.fromString(run.path("stages").get(0).path("agents").get(0).path("id").asString()));
  }

  private Runner register(String name) {
    JsonNode registered =
        http.post()
            .uri("/api/runner/register")
            .body(new RunnerRegistration(name, RUNNER_REGISTRATION_TOKEN, "dev", 2, "2.1.288"))
            .retrieve()
            .body(JsonNode.class);
    return new Runner(
        UUID.fromString(registered.path("runnerId").asString()),
        registered.path("token").asString());
  }

  /** El runner recoge la orden de arranque y la confirma. */
  private void startOn(Runner runner) {
    JsonNode commands =
        http.get()
            .uri("/api/runner/commands?waitSeconds=1")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + runner.token())
            .retrieve()
            .body(JsonNode.class);
    assertThat(commands).hasSize(1);
    http.post()
        .uri("/api/runner/commands/" + commands.get(0).path("id").asString() + "/ack")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + runner.token())
        .retrieve()
        .toBodilessEntity();
  }

  private void heartbeat(Runner runner) {
    http.post()
        .uri("/api/runner/heartbeat")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + runner.token())
        .body(new RunnerHeartbeat(2, List.of()))
        .retrieve()
        .toBodilessEntity();
  }

  private void send(Runner runner, EventBatch batch) {
    http.post()
        .uri("/api/runner/events")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + runner.token())
        .body(batch)
        .retrieve()
        .toBodilessEntity();
  }

  private JsonNode agent(Launched run) {
    return get("/api/agent-runs/" + run.agentId()).path("agent");
  }

  private JsonNode lastStatusChange(Launched run) {
    JsonNode last = null;
    for (JsonNode e : get("/api/events?aggregateId=" + run.agentId() + "&limit=500")) {
      if (e.path("type").asString().equals("agent.status.changed")) {
        last = e.path("payload");
      }
    }
    return last;
  }

  private static List<UUID> ids(JsonNode page) {
    List<UUID> ids = new ArrayList<>();
    page.path("items").forEach(r -> ids.add(UUID.fromString(r.path("id").asString())));
    return ids;
  }

  private JsonNode post(String path, Object body) {
    return http.post().uri(path).body(body).retrieve().body(JsonNode.class);
  }

  private JsonNode get(String path) {
    return http.get().uri(path).retrieve().body(JsonNode.class);
  }
}
