package dev.skynet.controlplane.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skynet.controlplane.support.IntegrationTest;
import dev.skynet.controlplane.workflow.PullRequests;
import dev.skynet.protocol.AgentEventType;
import dev.skynet.protocol.NormalizedEvent;
import dev.skynet.protocol.runner.EventBatch;
import dev.skynet.protocol.runner.RunnerRegistration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;

/**
 * La entrega por PR de una fase {@code github-pr} (EN-A), con un runner simulado y el GitHub falso:
 * el runner recibe el push, pide su token, avisa de lo que subió y la fase espera a que se abra la
 * PR con las evidencias.
 */
class PullRequestDeliveryIT extends IntegrationTest {

  private static final FakeGitHub GITHUB = new FakeGitHub();
  private static final String SHA = "c0ffee0000000000000000000000000000000001";

  static final String DELIVERY =
      """
      id: entrega
      version: 1
      stages:
        - id: implementar
          name: Implementar
          type: agent
          prompt: Implementa
          outputSchema:
            type: object
            properties:
              summary:
                type: string
        - id: pr
          type: github-pr
          dependsOn: [implementar]
          condition: repository.github
          pullRequest:
            title: "{{workItem.key}}: {{stages.implementar.output.summary}}"
            body: Hecho por Skynet.
            labels: [skynet]
        - id: despues
          type: agent
          prompt: Sigue
          dependsOn: [pr]
      """;

  @DynamicPropertySource
  static void github(DynamicPropertyRegistry registry) {
    registry.add("skynet.github.app-id", () -> FakeGitHub.APP_ID);
    registry.add("skynet.github.private-key-file", () -> FakeGitHub.writeKey().toString());
    registry.add("skynet.github.webhook-secret", () -> "secreto-de-prueba");
    registry.add("skynet.github.api-url", GITHUB::url);
    registry.add("skynet.github.web-url", () -> "https://github.com");
    // Las PR se abren a mano en cada test (openPending), no con el planificador.
    registry.add("skynet.github.pull-requests.poll-interval", () -> "1h");
    registry.add("skynet.github.pull-requests.public-url", () -> "https://skynet.example");
  }

  @AfterAll
  static void stop() {
    GITHUB.close();
  }

  @Autowired private PullRequests pullRequests;

  private String repositoryId;
  private String workItemId;
  private String workItemKey;

  @BeforeEach
  void setUp() {
    GITHUB.reset();
    GITHUB.install(7, "guilu", "guilu/demo-repo");
    String projectId =
        post("/api/projects", Map.of("key", "PRD", "name", "Entrega")).path("id").asString();
    repositoryId =
        post(
                "/api/projects/" + projectId + "/repositories",
                Map.of(
                    "name",
                    "demo-repo",
                    "localPath",
                    "/repos/demo",
                    "remoteUrl",
                    "https://github.com/guilu/demo-repo.git"))
            .path("id")
            .asString();
    post("/api/github/sync", Map.of());
    JsonNode item =
        post(
            "/api/projects/" + projectId + "/work-items",
            Map.of(
                "title",
                "Corregir add() con negativos",
                "description",
                "Falla con -1 + 1.",
                "type",
                "BUG"));
    workItemId = item.path("id").asString();
    workItemKey = item.path("key").asString();
  }

  @Test
  void pushesTheBranchWithAScopedTokenAndOpensThePullRequestWithItsEvidence() {
    Runner runner = register("portatil");
    String runId = launch(publish(DELIVERY)).path("id").asString();
    complete(runner, runner.claim().getFirst(), Map.of("summary", "suma bien los negativos"));

    JsonNode push = runner.claim().getFirst();
    String pushAgent = push.path("agentRunId").asString();
    JsonNode start = push.path("start");
    assertThat(start.path("push").path("remoteUrl").asString())
        .isEqualTo("https://github.com/guilu/demo-repo.git");
    String branch = "skynet/" + workItemKey.toLowerCase();
    assertThat(start.path("push").path("branch").asString()).isEqualTo(branch);
    assertThat(start.path("push").path("base").asString()).isEqualTo("abc1234");
    assertThat(start.path("push").path("force").asBoolean()).isTrue();
    assertThat(start.path("workspace").path("path").asString()).isEqualTo("/w/demo/implementar");
    assertThat(start.path("command").isNull()).isTrue();
    assertThat(start.path("provider").isNull()).isTrue();
    assertThat(provider(pushAgent)).isEqualTo("git-push");
    // El token no viaja en la orden.
    assertThat(push.toString()).doesNotContain("ghs_");

    // Solo el runner del push consigue el token, limitado al repositorio y a contents: write.
    String token = runner.pushToken(pushAgent).path("token").asString();
    assertThat(token).startsWith("ghs_");
    JsonNode request = GITHUB.tokenRequests.getLast();
    assertThat(request.path("permissions").path("contents").asString()).isEqualTo("write");
    assertThat(request.path("permissions").size()).isEqualTo(1);
    assertThat(request.path("repository_ids").get(0).asLong())
        .isEqualTo(FakeGitHub.repositoryId("guilu/demo-repo"));
    Runner other = register("otro");
    assertStatus(() -> other.pushToken(pushAgent), 404);

    Events events = new Events(UUID.fromString(pushAgent));
    events.add(
        AgentEventType.WORKSPACE_READY,
        Map.of(
            "path",
            "/w/demo/implementar",
            "branch",
            "skynet/implementar",
            "baseCommit",
            "abc1234"));
    events.add(
        AgentEventType.TOOL_STARTED,
        Map.of("toolUseId", "push", "name", "git push", "input", Map.of()));
    events.add(
        AgentEventType.GIT_PUSHED,
        Map.of("branch", branch, "sha", SHA, "commits", 1, "committed", false));
    events.add(AgentEventType.PROCESS_EXITED, Map.of("exitCode", 0));
    runner.send(events);
    // El push terminó bien, pero la fase espera a su PR.
    assertThat(stageStatuses(runId)).containsEntry("pr", "RUNNING");
    assertThat(runner.claim()).isEmpty();
    assertStatus(() -> runner.pushToken(pushAgent), 409);

    GITHUB.branchShas.put(branch, SHA);
    pullRequests.openPending();

    assertThat(stageStatuses(runId)).containsEntry("pr", "SUCCEEDED");
    Map<String, Object> pr = GITHUB.pull("guilu/demo-repo", 12);
    assertThat(pr).containsEntry("title", workItemKey + ": suma bien los negativos");
    assertThat(pr.get("base")).isEqualTo(Map.of("ref", "main"));
    assertThat(pr.get("labels")).isEqualTo(List.of("skynet"));
    assertThat(pr.get("body").toString())
        .startsWith("Hecho por Skynet.")
        .contains("## " + workItemKey + " - Corregir add() con negativos")
        .contains("> Falla con -1 + 1.")
        .contains("## Evidencias")
        .contains("| Implementar | terminada bien | suma bien los negativos |")
        .contains("[ver la ejecución](https://skynet.example/runs/" + runId + ")");

    JsonNode views = get("/api/workflow-runs/" + runId + "/pull-requests");
    assertThat(views).hasSize(1);
    assertThat(views.get(0).path("status").asString()).isEqualTo("OPEN");
    assertThat(views.get(0).path("number").asInt()).isEqualTo(12);
    assertThat(views.get(0).path("url").asString())
        .isEqualTo("https://github.com/guilu/demo-repo/pull/12");
    assertThat(views.get(0).path("headSha").asString()).isEqualTo(SHA);
    assertThat(eventTypes(runId)).contains("stage.github.pr.opened");

    // Lo siguiente continúa el mismo worktree.
    JsonNode next = runner.claim().getFirst();
    assertThat(taskPrompt(next.path("start"))).isEqualTo("Sigue");
    assertThat(next.path("start").path("workspace").path("path").asString())
        .isEqualTo("/w/demo/implementar");
  }

  @Test
  void aRepositoryWithoutGitHubSkipsTheStageByItsCondition() {
    jdbc.sql("UPDATE repository SET remote_url = NULL WHERE id = ?::uuid")
        .param(repositoryId)
        .update();
    Runner runner = register("portatil");
    String runId = launch(publish(DELIVERY)).path("id").asString();
    complete(runner, runner.claim().getFirst(), Map.of("summary", "hecho"));

    assertThat(stageStatuses(runId)).containsEntry("pr", "SKIPPED");
    assertThat(GITHUB.repoRequests).isEmpty();
  }

  @Test
  void withoutTheConditionTheStageFailsWithTheReason() {
    jdbc.sql(
            "UPDATE repository SET remote_url = 'https://gitlab.com/guilu/demo' WHERE id = ?::uuid")
        .param(repositoryId)
        .update();
    Runner runner = register("portatil");
    String runId =
        launch(publish(DELIVERY.replace("    condition: repository.github\n", "")))
            .path("id")
            .asString();
    complete(runner, runner.claim().getFirst(), Map.of("summary", "hecho"));

    assertThat(stageStatuses(runId)).containsEntry("pr", "FAILED");
    assertThat(get("/api/workflow-runs/" + runId).path("status").asString()).isEqualTo("FAILED");
    assertThat(
            jdbc.sql(
                    "SELECT payload->>'error' FROM event WHERE workflow_run_id = ?::uuid"
                        + " AND event_type = 'stage.start.failed'")
                .param(runId)
                .query(String.class)
                .single())
        .contains("no está enlazado con GitHub");
  }

  @Test
  void gitHubRefusingThePullRequestFailsTheStage() {
    GITHUB.createPullStatus = 422;
    Runner runner = register("portatil");
    String runId = launch(publish(DELIVERY)).path("id").asString();
    pushed(runner, runner.claim().getFirst());

    pullRequests.openPending();

    assertThat(stageStatuses(runId)).containsEntry("pr", "FAILED");
    JsonNode view = get("/api/workflow-runs/" + runId + "/pull-requests").get(0);
    assertThat(view.path("status").asString()).isEqualTo("FAILED");
    assertThat(view.path("error").asString()).contains("GitHub respondió 422");
    assertThat(eventTypes(runId)).contains("stage.github.pr.failed");
  }

  @Test
  void anUnavailableGitHubIsRetriedLater() {
    GITHUB.createPullStatus = 502;
    Runner runner = register("portatil");
    String runId = launch(publish(DELIVERY)).path("id").asString();
    pushed(runner, runner.claim().getFirst());

    pullRequests.openPending();
    assertThat(stageStatuses(runId)).containsEntry("pr", "RUNNING");
    JsonNode view = get("/api/workflow-runs/" + runId + "/pull-requests").get(0);
    assertThat(view.path("status").asString()).isEqualTo("PUSHED");
    assertThat(view.path("error").asString()).contains("502");

    // Antes de su hora no se reintenta; después, sí.
    pullRequests.openPending();
    assertThat(GITHUB.pulls.getOrDefault("guilu/demo-repo", Map.of())).isEmpty();
    GITHUB.createPullStatus = 0;
    jdbc.sql("UPDATE github_pull_request SET next_attempt_at = now() - interval '1 second'")
        .update();
    pullRequests.openPending();
    assertThat(stageStatuses(runId)).containsEntry("pr", "SUCCEEDED");
  }

  @Test
  void aSecondRunOfTheSameWorkItemUpdatesTheOpenPullRequest() {
    Runner runner = register("portatil");
    String definition = publish(DELIVERY);
    String first = launch(definition).path("id").asString();
    pushed(runner, runner.claim().getFirst());
    pullRequests.openPending();
    assertThat(stageStatuses(first)).containsEntry("pr", "SUCCEEDED");
    post("/api/workflow-runs/" + first + "/cancel", Map.of());

    String second = launch(definition).path("id").asString();
    pushed(runner, awaitStart(runner, "Implementa"));
    pullRequests.openPending();

    assertThat(stageStatuses(second)).containsEntry("pr", "SUCCEEDED");
    assertThat(GITHUB.pulls.get("guilu/demo-repo")).hasSize(1);
    assertThat(eventTypes(second)).contains("stage.github.pr.updated");
  }

  // ---- Ayudas ----------------------------------------------------------------------------------

  /** El agente de {@code implementar} termina bien y el push de {@code pr} sube la rama. */
  private void pushed(Runner runner, JsonNode implement) {
    complete(runner, implement, Map.of("summary", "hecho"));
    JsonNode push = runner.claim().getFirst();
    Events events = new Events(UUID.fromString(push.path("agentRunId").asString()));
    events.add(
        AgentEventType.WORKSPACE_READY,
        Map.of(
            "path",
            "/w/demo/implementar",
            "branch",
            "skynet/implementar",
            "baseCommit",
            "abc1234"));
    events.add(
        AgentEventType.TOOL_STARTED,
        Map.of("toolUseId", "push", "name", "git push", "input", Map.of()));
    events.add(
        AgentEventType.GIT_PUSHED,
        Map.of("branch", push.path("start").path("push").path("branch").asString(), "sha", SHA));
    events.add(AgentEventType.PROCESS_EXITED, Map.of("exitCode", 0));
    runner.send(events);
  }

  private JsonNode awaitStart(Runner runner, String prompt) {
    for (int i = 0; i < 20; i++) {
      for (JsonNode command : runner.claim()) {
        if ("START".equals(command.path("type").asString())
            && prompt.equals(taskPrompt(command.path("start")))) {
          return command;
        }
      }
    }
    throw new AssertionError("No llegó la invocación «" + prompt + "»");
  }

  private void complete(Runner runner, JsonNode command, Map<String, Object> output) {
    Events events = new Events(UUID.fromString(command.path("agentRunId").asString()));
    events.add(
        AgentEventType.WORKSPACE_READY,
        Map.of(
            "path",
            "/w/demo/implementar",
            "branch",
            "skynet/implementar",
            "baseCommit",
            "abc1234"));
    events.add(AgentEventType.SESSION_STARTED, Map.of("sessionId", UUID.randomUUID().toString()));
    events.add(
        AgentEventType.RESULT,
        Map.of("subtype", "success", "isError", false, "structuredOutput", output));
    events.add(AgentEventType.PROCESS_EXITED, Map.of("exitCode", 0));
    runner.send(events);
  }

  private String publish(String yaml) {
    JsonNode draft = post("/api/workflows", Map.of("sourceYaml", yaml));
    String id = draft.path("id").asString();
    post(
        "/api/workflow-versions/" + id + "/publish",
        Map.of("revision", draft.path("revision").asLong()));
    return id;
  }

  private JsonNode launch(String definitionId) {
    return post(
        "/api/work-items/" + workItemId + "/runs",
        Map.of("repositoryId", repositoryId, "definitionId", definitionId, "inputs", Map.of()));
  }

  private Map<String, String> stageStatuses(String runId) {
    Map<String, String> statuses = new LinkedHashMap<>();
    get("/api/workflow-runs/" + runId)
        .path("stages")
        .forEach(s -> statuses.put(s.path("stageKey").asString(), s.path("status").asString()));
    return statuses;
  }

  private String provider(String agentRunId) {
    return jdbc.sql("SELECT provider FROM agent_run WHERE id = ?::uuid")
        .param(agentRunId)
        .query(String.class)
        .single();
  }

  private List<String> eventTypes(String runId) {
    return jdbc.sql(
            "SELECT event_type FROM event WHERE workflow_run_id = ?::uuid ORDER BY sequence")
        .param(runId)
        .query(String.class)
        .list();
  }

  private static void assertStatus(Runnable call, int status) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            HttpClientErrorException.class,
            e -> assertThat(e.getStatusCode().value()).isEqualTo(status));
  }

  private Runner register(String name) {
    JsonNode registered =
        http.post()
            .uri("/api/runner/register")
            .body(
                new RunnerRegistration(name, RUNNER_REGISTRATION_TOKEN, "dev", 4, "2.1.288", null))
            .retrieve()
            .body(JsonNode.class);
    return new Runner(registered.path("token").asString());
  }

  private final class Runner {
    private final String token;

    Runner(String token) {
      this.token = token;
    }

    List<JsonNode> claim() {
      JsonNode commands =
          http.get()
              .uri("/api/runner/commands?waitSeconds=1")
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
              .retrieve()
              .body(JsonNode.class);
      List<JsonNode> claimed = new ArrayList<>();
      for (JsonNode command : commands) {
        http.post()
            .uri("/api/runner/commands/" + command.path("id").asString() + "/ack")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .retrieve()
            .toBodilessEntity();
        claimed.add(command);
      }
      return claimed;
    }

    JsonNode pushToken(String agentRunId) {
      return http.post()
          .uri("/api/runner/agent-runs/" + agentRunId + "/push-token")
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
          .retrieve()
          .body(JsonNode.class);
    }

    void send(Events events) {
      http.post()
          .uri("/api/runner/events")
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
          .body(events.batch())
          .retrieve()
          .toBodilessEntity();
    }
  }

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

  private JsonNode post(String path, Object body) {
    return http.post().uri(path).body(body).retrieve().body(JsonNode.class);
  }

  private JsonNode get(String path) {
    return http.get().uri(path).retrieve().body(JsonNode.class);
  }
}
