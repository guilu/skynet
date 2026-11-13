package dev.skynet.controlplane.github;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skynet.controlplane.support.IntegrationTest;
import dev.skynet.controlplane.workflow.CiChecks;
import dev.skynet.controlplane.workflow.PullRequestMerges;
import dev.skynet.controlplane.workflow.PullRequests;
import dev.skynet.protocol.AgentEventType;
import dev.skynet.protocol.NormalizedEvent;
import dev.skynet.protocol.runner.EventBatch;
import dev.skynet.protocol.runner.RunnerRegistration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * La fusión de una fase {@code github-merge} (EN-C), con un runner simulado y el GitHub falso:
 * fusiona con el commit aprobado y borra la rama; no fusiona si la PR cambió o la CI ya no está en
 * verde; una PR fusionada a mano cuenta como hecha y una cerrada sin fusionar cancela la ejecución.
 */
class MergeIT extends IntegrationTest {

  private static final FakeGitHub GITHUB = new FakeGitHub();
  private static final String SHA = "c0ffee0000000000000000000000000000000001";
  private static final String OTHER = "c0ffee0000000000000000000000000000000009";

  static final String DELIVERY =
      """
      id: entrega
      version: 1
      stages:
        - id: implementar
          type: agent
          prompt: Implementa
        - id: pr
          type: github-pr
          dependsOn: [implementar]
        - id: ci
          type: github-check
          dependsOn: [pr]
        - id: aprobar-fusion
          type: human-approval
          dependsOn: [ci]
          action: "Fusionar la PR de {{workItem.key}}"
        - id: fusionar
          type: github-merge
          dependsOn: [aprobar-fusion]
      """;

  @DynamicPropertySource
  static void github(DynamicPropertyRegistry registry) {
    registry.add("skynet.github.app-id", () -> FakeGitHub.APP_ID);
    registry.add("skynet.github.private-key-file", () -> FakeGitHub.writeKey().toString());
    registry.add("skynet.github.webhook-secret", () -> "secreto-de-prueba");
    registry.add("skynet.github.api-url", GITHUB::url);
    registry.add("skynet.github.web-url", () -> "https://github.com");
    // Las PR se abren y se fusionan, y la CI se consulta, a mano en cada test.
    registry.add("skynet.github.pull-requests.poll-interval", () -> "1h");
    registry.add("skynet.github.ci.poll-interval", () -> "1h");
  }

  @AfterAll
  static void stop() {
    GITHUB.close();
  }

  @Autowired private PullRequests pullRequests;
  @Autowired private CiChecks ciChecks;
  @Autowired private PullRequestMerges merges;
  @Autowired private ApplicationEventPublisher publisher;

  private static final tools.jackson.databind.ObjectMapper json =
      tools.jackson.databind.json.JsonMapper.builder().build();

  private String repositoryId;
  private String workItemId;

  @BeforeEach
  void setUp() {
    GITHUB.reset();
    GITHUB.install(7, "guilu", "guilu/demo-repo");
    String projectId =
        post("/api/projects", Map.of("key", "MRG", "name", "Fusión")).path("id").asString();
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
            Map.of("title", "Arreglar add()", "description", "Falla.", "type", "BUG"));
    workItemId = item.path("id").asString();
  }

  @Test
  void anApprovedPullRequestIsMergedWithTheApprovedCommitAndItsBranchDeleted() {
    String runId = untilApproval();

    // La aprobación enseña la PR y la CI del commit que se aprueba.
    JsonNode approval = get("/api/approvals?status=PENDING").get(0);
    JsonNode pr = get("/api/approvals/" + approval.path("id").asString()).path("evidence");
    assertThat(pr.path("pullRequest").path("number").asInt()).isEqualTo(12);
    assertThat(pr.path("pullRequest").path("headSha").asString()).isEqualTo(SHA);
    assertThat(pr.path("pullRequest").path("ci").path("status").asString()).isEqualTo("PASSED");
    assertThat(pr.path("pullRequest").path("ci").path("checks").get(0).path("name").asString())
        .isEqualTo("test");

    approve(approval);
    assertThat(stageStatuses(runId)).containsEntry("fusionar", "RUNNING");
    JsonNode pending = get("/api/workflow-runs/" + runId + "/merges").get(0);
    assertThat(pending.path("status").asString()).isEqualTo("PENDING");
    assertThat(pending.path("method").asString()).isEqualTo("squash");
    assertThat(pending.path("expectedSha").asString()).isEqualTo(SHA);

    merges.mergePending();

    assertThat(GITHUB.repoRequests)
        .anySatisfy(
            r ->
                assertThat(r)
                    .startsWith("PUT /repos/guilu/demo-repo/pulls/12/merge")
                    .contains("\"sha\":\"" + SHA + "\"")
                    .contains("\"merge_method\":\"squash\""));
    String branch =
        get("/api/workflow-runs/" + runId + "/pull-requests").get(0).path("branch").asString();
    assertThat(GITHUB.deletedBranches).containsExactly(branch);
    assertThat(stageStatuses(runId)).containsEntry("fusionar", "SUCCEEDED");
    assertThat(get("/api/workflow-runs/" + runId).path("status").asString()).isEqualTo("SUCCEEDED");
    JsonNode merged = get("/api/workflow-runs/" + runId + "/merges").get(0);
    assertThat(merged.path("status").asString()).isEqualTo("MERGED");
    assertThat(merged.path("mergedSha").asString()).isEqualTo("e".repeat(40));
    assertThat(merged.path("branchDeleted").asBoolean()).isTrue();
    assertThat(
            get("/api/workflow-runs/" + runId + "/pull-requests").get(0).path("status").asString())
        .isEqualTo("MERGED");
    assertThat(get("/api/work-items/" + workItemId).path("status").asString()).isEqualTo("CLOSED");
    assertThat(eventTypes(runId)).contains("stage.github.pr.merged", "workitem.closed");

    // El webhook de la fusión que hizo Skynet llega después y no cambia nada.
    publisher.publishEvent(
        new GitHubPullRequestClosed(UUID.fromString(repositoryId), 12, true, "e".repeat(40)));
    assertThat(eventTypes(runId)).containsOnlyOnce("stage.github.pr.merged");
  }

  @Test
  void aPullRequestThatChangedAfterTheApprovalIsNotMerged() {
    String runId = untilApproval();
    approve(get("/api/approvals?status=PENDING").get(0));
    setHead(OTHER);

    merges.mergePending();

    assertThat(GITHUB.repoRequests).noneMatch(r -> r.startsWith("PUT "));
    assertThat(stageStatuses(runId)).containsEntry("fusionar", "FAILED");
    assertThat(get("/api/workflow-runs/" + runId).path("status").asString()).isEqualTo("FAILED");
    assertThat(get("/api/workflow-runs/" + runId + "/merges").get(0).path("error").asString())
        .isEqualTo(
            "La PR cambió después de la aprobación: se aprobó el commit c0ffee0 y ahora tiene el"
                + " c0ffee0. No se fusiona: revísala en GitHub");
    assertThat(eventTypes(runId)).contains("stage.github.pr.merge.failed");
    assertThat(get("/api/work-items/" + workItemId).path("status").asString())
        .isNotEqualTo("CLOSED");
  }

  @Test
  void aCiThatIsNoLongerGreenIsNotMergedAndAPendingOneWaits() {
    String runId = untilApproval();
    approve(get("/api/approvals?status=PENDING").get(0));
    long lint = GITHUB.checkRun(SHA, "lint", "github-actions", "in_progress", null);

    merges.mergePending();
    assertThat(stageStatuses(runId)).containsEntry("fusionar", "RUNNING");
    assertThat(get("/api/workflow-runs/" + runId + "/merges").get(0).path("error").asString())
        .isEqualTo("La CI del commit aún no ha terminado");

    GITHUB.finishCheckRun(SHA, lint, "failure");
    jdbc.sql("UPDATE github_merge SET next_attempt_at = now() - interval '1 second'").update();
    merges.mergePending();

    assertThat(GITHUB.repoRequests).noneMatch(r -> r.startsWith("PUT "));
    assertThat(stageStatuses(runId)).containsEntry("fusionar", "FAILED");
    assertThat(get("/api/workflow-runs/" + runId + "/merges").get(0).path("error").asString())
        .isEqualTo("La CI ya no está en verde: falló en lint");
  }

  @Test
  void aMergeGitHubRefusesFailsTheStageWithItsMessage() {
    String runId = untilApproval();
    approve(get("/api/approvals?status=PENDING").get(0));
    GITHUB.mergeStatus = 405;

    merges.mergePending();

    assertThat(stageStatuses(runId)).containsEntry("fusionar", "FAILED");
    assertThat(get("/api/workflow-runs/" + runId + "/merges").get(0).path("error").asString())
        .startsWith("GitHub no fusionó la PR: GitHub respondió 405")
        .contains("Pull Request is not mergeable");
    assertThat(GITHUB.deletedBranches).isEmpty();
  }

  @Test
  void aMergeStageThatTimedOutDoesNotMergeAfterwards() {
    String runId = untilApproval();
    approve(get("/api/approvals?status=PENDING").get(0));
    jdbc.sql(
            "UPDATE stage_run SET status = 'CANCELLED' WHERE workflow_run_id = ?::uuid"
                + " AND stage_key = 'fusionar'")
        .param(runId)
        .update();

    merges.mergePending();

    assertThat(GITHUB.repoRequests).noneMatch(r -> r.startsWith("PUT "));
    JsonNode merge = get("/api/workflow-runs/" + runId + "/merges").get(0);
    assertThat(merge.path("status").asString()).isEqualTo("FAILED");
    assertThat(merge.path("error").asString()).isEqualTo("La fase terminó antes de fusionar la PR");
  }

  @Test
  void aPullRequestMergedByHandCountsAsMerged() {
    String runId = untilApproval();
    approve(get("/api/approvals?status=PENDING").get(0));

    publisher.publishEvent(
        new GitHubPullRequestClosed(UUID.fromString(repositoryId), 12, true, "a".repeat(40)));

    assertThat(stageStatuses(runId)).containsEntry("fusionar", "SUCCEEDED");
    JsonNode merged = get("/api/workflow-runs/" + runId + "/merges").get(0);
    assertThat(merged.path("status").asString()).isEqualTo("MERGED");
    assertThat(merged.path("mergedSha").asString()).isEqualTo("a".repeat(40));
    assertThat(get("/api/work-items/" + workItemId).path("status").asString()).isEqualTo("CLOSED");
    merges.mergePending();
    assertThat(GITHUB.repoRequests).noneMatch(r -> r.startsWith("PUT "));
  }

  @Test
  void aPullRequestMergedByHandBeforeTheStageStartsCountsAsMergedWhenItStarts() {
    String runId = untilApproval();
    publisher.publishEvent(
        new GitHubPullRequestClosed(UUID.fromString(repositoryId), 12, true, "a".repeat(40)));
    assertThat(
            get("/api/workflow-runs/" + runId + "/pull-requests").get(0).path("status").asString())
        .isEqualTo("MERGED");

    approve(get("/api/approvals?status=PENDING").get(0));

    assertThat(stageStatuses(runId)).containsEntry("fusionar", "SUCCEEDED");
    assertThat(get("/api/workflow-runs/" + runId).path("status").asString()).isEqualTo("SUCCEEDED");
  }

  @Test
  void aPullRequestClosedWithoutMergingCancelsTheRun() {
    String runId = untilApproval();
    publisher.publishEvent(
        new GitHubPullRequestClosed(UUID.fromString(repositoryId), 99, false, null));
    assertThat(stageStatuses(runId)).containsEntry("aprobar-fusion", "WAITING_FOR_APPROVAL");

    publisher.publishEvent(
        new GitHubPullRequestClosed(UUID.fromString(repositoryId), 12, false, null));

    assertThat(get("/api/workflow-runs/" + runId).path("status").asString()).isEqualTo("CANCELLED");
    assertThat(
            get("/api/workflow-runs/" + runId + "/pull-requests").get(0).path("status").asString())
        .isEqualTo("CLOSED");
    assertThat(eventTypes(runId)).contains("workflow.github.pr.closed");
    assertThat(
            jdbc.sql(
                    "SELECT payload ->> 'reason' FROM event WHERE workflow_run_id = ?::uuid"
                        + " AND event_type = 'workflow.cancel.requested'")
                .param(runId)
                .query(String.class)
                .single())
        .isEqualTo("pull-request-closed");
  }

  // ---- Flujo -----------------------------------------------------------------------------------

  /** Lanza la entrega y la lleva hasta la aprobación de la fusión, con la CI en verde. */
  private String untilApproval() {
    Runner runner = register("portatil");
    String runId = launch(publish(DELIVERY)).path("id").asString();
    openPullRequest(runner, runId);
    setHead(SHA);
    GITHUB.checkRun(SHA, "test", "github-actions", "completed", "success");
    ciChecks.checkDue();
    assertThat(stageStatuses(runId))
        .containsEntry("ci", "SUCCEEDED")
        .containsEntry("aprobar-fusion", "WAITING_FOR_APPROVAL");
    return runId;
  }

  private void approve(JsonNode approval) {
    post(
        "/api/approvals/" + approval.path("id").asString() + "/decisions",
        Map.of("action", "APPROVE"));
  }

  /** Cambia el último commit de la PR en el GitHub falso, como si alguien subiera a su rama. */
  private void setHead(String sha) {
    Map<String, Object> pr = GITHUB.pull("guilu/demo-repo", 12);
    pr.put("head", Map.of("ref", ((Map<?, ?>) pr.get("head")).get("ref"), "sha", sha));
  }

  private void openPullRequest(Runner runner, String runId) {
    JsonNode implement = awaitStart(runner, "Implementa");
    complete(runner, implement, Map.of());
    sendPushed(runner, runner.claim().getFirst(), SHA);
    pullRequests.openPending();
    for (int i = 0; i < 50 && !"RUNNING".equals(stageStatuses(runId).get("ci")); i++) {
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    assertThat(stageStatuses(runId))
        .containsEntry("pr", "SUCCEEDED")
        .containsEntry("ci", "RUNNING");
  }

  private void sendPushed(Runner runner, JsonNode push, String sha) {
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
        Map.of("branch", push.path("start").path("push").path("branch").asString(), "sha", sha));
    events.add(AgentEventType.PROCESS_EXITED, Map.of("exitCode", 0));
    runner.send(events);
  }

  // ---- Ayudas ----------------------------------------------------------------------------------

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

  private List<String> eventTypes(String runId) {
    return jdbc.sql(
            "SELECT event_type FROM event WHERE workflow_run_id = ?::uuid ORDER BY sequence")
        .param(runId)
        .query(String.class)
        .list();
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

  @Test
  void aRevertInTheDefaultBranchIsAnEscapeOfTheJudge() throws Exception {
    String runId = untilApproval();
    approve(get("/api/approvals?status=PENDING").get(0));
    merges.mergePending();
    assertThat(get("/api/workflow-runs/" + runId + "/escapes")).isEmpty();

    // La PR que abre el botón «Revert» de GitHub, fusionada (EV-A).
    signed(
        "pull_request",
        "d-revert",
        Map.of(
            "action",
            "closed",
            "number",
            13,
            "pull_request",
            Map.of(
                "number",
                13,
                "merged",
                true,
                "merge_commit_sha",
                "f".repeat(40),
                "title",
                "Revert \"Arreglar add()\"",
                "body",
                "Reverts guilu/demo-repo#12\n\nRompía la suma de negativos.",
                "html_url",
                "https://github.com/guilu/demo-repo/pull/13"),
            "repository",
            Map.of("full_name", "guilu/demo-repo", "default_branch", "main"),
            "installation",
            Map.of("id", 7)));
    JsonNode escape = awaitEscape(runId);
    assertThat(escape.path("origin").asString()).isEqualTo("revert-pr");
    assertThat(escape.path("seenRef").asString()).isEqualTo("#13");
    assertThat(escape.path("seenUrl").asString())
        .isEqualTo("https://github.com/guilu/demo-repo/pull/13");
    assertThat(escape.path("pullRequestNumber").asInt()).isEqualTo(12);
    assertThat(escape.path("mergedSha").asString()).isEqualTo("e".repeat(40));
    assertThat(escape.path("repositoryName").asString()).isEqualTo("demo-repo");
    assertThat(escape.path("workItemTitle").asString()).isEqualTo("Arreglar add()");
    assertThat(escape.path("createdBy").asString()).isEqualTo("skynet");
    assertThat(eventTypes(runId)).containsOnlyOnce("run.escaped");

    // El push que deja ese revert en main es el mismo escape.
    String push = "a1b2c3d" + "0".repeat(33);
    signed(
        "push",
        "d-push-1",
        push(push, "Revert \"Arreglar add()\"\n\nThis reverts commit eeeeeee."));
    awaitDelivery("d-push-1");
    assertThat(get("/api/workflow-runs/" + runId + "/escapes")).hasSize(1);
    assertThat(eventTypes(runId)).containsOnlyOnce("run.escaped");
    // Uno a mano tampoco: ya lo tiene.
    assertConflict("/api/workflow-runs/" + runId + "/escapes", Map.of("note", "Otra vez"));

    // Se quita (no fue culpa del juez) y un push que lo revierte lo vuelve a crear.
    http.delete().uri("/api/escapes/" + escape.path("id").asString()).retrieve().toBodilessEntity();
    assertThat(get("/api/workflow-runs/" + runId + "/escapes")).isEmpty();
    assertThat(eventTypes(runId)).contains("run.escape.removed");
    signed("push", "d-push-2", push(push, "Revert\n\nThis reverts commit EEEEEEEEEE."));
    JsonNode pushed = awaitEscape(runId);
    assertThat(pushed.path("origin").asString()).isEqualTo("revert-push");
    assertThat(pushed.path("seenRef").asString()).isEqualTo("a1b2c3d");
    assertThat(pushed.path("seenUrl").asString())
        .isEqualTo("https://github.com/guilu/demo-repo/commit/" + push);
    // Un push a otra rama no cuenta.
    http.delete().uri("/api/escapes/" + pushed.path("id").asString()).retrieve().toBodilessEntity();
    Map<String, Object> other = new LinkedHashMap<>(push(push, "This reverts commit eeeeeee."));
    other.put("ref", "refs/heads/otra");
    signed("push", "d-push-3", other);
    awaitDelivery("d-push-3");
    assertThat(get("/api/workflow-runs/" + runId + "/escapes")).isEmpty();

    // A mano, con una nota; y en la lista de la fábrica.
    JsonNode manual =
        post("/api/workflow-runs/" + runId + "/escapes", Map.of("note", "Rompió la exportación"));
    assertThat(manual.path("origin").asString()).isEqualTo("manual");
    assertThat(manual.path("note").asString()).isEqualTo("Rompió la exportación");
    assertThat(manual.path("createdBy").asString()).isNotBlank().isNotEqualTo("skynet");
    JsonNode factory = get("/api/factory/escapes?days=7");
    assertThat(factory).hasSize(1);
    assertThat(factory.get(0).path("runId").asString()).isEqualTo(runId);
  }

  @Test
  void theJudgesAreCalibratedWithWhatHappenedToWhatTheyApproved() throws Exception {
    String runId = untilApproval();
    // Dos jueces aprobaron el cambio (EV-B): uno con 4,8 y otro con 4,1; un tercero suspendió.
    verdict(runId, "juez", 4.8, true);
    verdict(runId, "segundo", 4.1, true);
    verdict(runId, "estricto", 2.0, false);
    // Sin desenlace todavía: la PR está abierta.
    assertThat(judge("juez").path("judged").asInt()).isZero();

    approve(get("/api/approvals?status=PENDING").get(0));
    merges.mergePending();
    JsonNode kept = judge("juez");
    assertThat(kept.path("judged").asInt()).isEqualTo(1);
    assertThat(kept.path("kept").asInt()).isEqualTo(1);
    assertThat(kept.path("fewOutcomes").asBoolean()).isTrue();
    JsonNode high = kept.path("calibration").get(2);
    assertThat(high.path("label").asString()).isEqualTo("≥ 4,5");
    assertThat(high.path("kept").asInt()).isEqualTo(1);
    assertThat(high.path("keptRatio").asDouble()).isEqualTo(1.0);
    assertThat(judge("segundo").path("calibration").get(1).path("label").asString())
        .isEqualTo("4–4,5");
    assertThat(judge("segundo").path("calibration").get(1).path("kept").asInt()).isEqualTo(1);
    // El que suspendió no aprobó nada.
    assertThat(judge("estricto").path("judged").asInt()).isZero();

    // Se revierte: lo que aprobó se le escapó.
    publisher.publishEvent(
        new GitHubPullRequestClosed(
            UUID.fromString(repositoryId),
            13,
            true,
            "f".repeat(40),
            "guilu/demo-repo",
            "Revert \"Arreglar add()\"",
            "Reverts guilu/demo-repo#12",
            "https://github.com/guilu/demo-repo/pull/13"));
    JsonNode escape = awaitEscape(runId);
    JsonNode escaped = judge("juez");
    assertThat(escaped.path("kept").asInt()).isZero();
    assertThat(escaped.path("escaped").asInt()).isEqualTo(1);
    assertThat(escaped.path("calibration").get(2).path("escaped").asInt()).isEqualTo(1);
    assertThat(escaped.path("calibration").get(2).path("keptRatio").asDouble()).isZero();
    assertThat(escaped.path("escapes")).hasSize(1);
    assertThat(escaped.path("escapes").get(0).path("runId").asString()).isEqualTo(runId);
    assertThat(escaped.path("escapes").get(0).path("seenRef").asString()).isEqualTo("#13");
    assertThat(escaped.path("escapes").get(0).path("score").asDouble()).isEqualTo(4.8);

    // Sin el escape, y con alguien que pidió cambios después del juez: rechazado.
    http.delete().uri("/api/escapes/" + escape.path("id").asString()).retrieve().toBodilessEntity();
    jdbc.sql(
            """
            INSERT INTO approval_decision (id, approval_request_id, action, comment, actor,
              created_at)
            SELECT gen_random_uuid(), id, 'REQUEST_CHANGES', 'Así no', 'admin', now()
            FROM approval_request WHERE workflow_run_id = ? LIMIT 1
            """)
        .param(UUID.fromString(runId))
        .update();
    JsonNode rejected = judge("juez");
    assertThat(rejected.path("rejected").asInt()).isEqualTo(1);
    assertThat(rejected.path("escaped").asInt()).isZero();
    assertThat(rejected.path("kept").asInt()).isZero();
  }

  /** El veredicto de un juez en la primera fase de la ejecución, de hace una hora. */
  private void verdict(String runId, String judge, double score, boolean passed) {
    jdbc.sql(
            """
            INSERT INTO stage_verdict (id, stage_run_id, workflow_run_id, round, judge, lens,
              agent_run_id, status, score, passed, created_at, updated_at)
            SELECT gen_random_uuid(), s.id, s.workflow_run_id, 1, ?, NULL, a.id, 'DONE', ?, ?,
              now() - interval '1 hour', now() - interval '1 hour'
            FROM stage_run s JOIN agent_run a ON a.stage_run_id = s.id
            WHERE s.workflow_run_id = ? LIMIT 1
            """)
        .param(judge)
        .param(score)
        .param(passed)
        .param(UUID.fromString(runId))
        .update();
  }

  private JsonNode judge(String name) {
    for (JsonNode row : get("/api/factory/judges?days=7")) {
      if (row.path("judge").asString().equals(name)) return row;
    }
    throw new AssertionError("Sin fila para el juez " + name);
  }

  @Test
  void aRunThatMergedNothingCannotHaveAnEscape() {
    String runId = untilApproval();
    assertConflict("/api/workflow-runs/" + runId + "/escapes", Map.of());
    // Una PR de revert de una PR que no es de Skynet no hace nada.
    publisher.publishEvent(
        new GitHubPullRequestClosed(
            UUID.fromString(repositoryId),
            14,
            true,
            "f".repeat(40),
            "guilu/demo-repo",
            "Revert \"Otra cosa\"",
            "Reverts guilu/demo-repo#99",
            null));
    assertThat(get("/api/factory/escapes?days=7")).isEmpty();
  }

  private Map<String, Object> push(String sha, String message) {
    return Map.of(
        "ref",
        "refs/heads/main",
        "repository",
        Map.of("full_name", "guilu/demo-repo", "default_branch", "main"),
        "installation",
        Map.of("id", 7),
        "commits",
        List.of(
            Map.of(
                "id",
                sha,
                "message",
                message,
                "url",
                "https://github.com/guilu/demo-repo/commit/" + sha)));
  }

  /** Un webhook firmado, sin sesión, como llega de GitHub. */
  private void signed(String event, String delivery, Object payload) throws Exception {
    byte[] body = json.writeValueAsBytes(payload);
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/github/webhooks"))
            .header("Content-Type", "application/json")
            .header("X-GitHub-Event", event)
            .header("X-GitHub-Delivery", delivery)
            .header("X-Hub-Signature-256", WebhookSignature.sign("secreto-de-prueba", body))
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
    HttpResponse<String> response =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isLessThan(300);
  }

  private void awaitDelivery(String id) {
    awaitValue(
        () -> {
          for (JsonNode d : get("/api/github/deliveries")) {
            if (d.path("deliveryId").asString().equals(id)
                && !d.path("status").asString().equals("PENDING")) {
              return d;
            }
          }
          return null;
        },
        "la entrega " + id);
  }

  private JsonNode awaitEscape(String runId) {
    return awaitValue(
        () -> {
          JsonNode escapes = get("/api/workflow-runs/" + runId + "/escapes");
          return escapes.isEmpty() ? null : escapes.get(0);
        },
        "el escape de " + runId);
  }

  private static <T> T awaitValue(java.util.function.Supplier<T> probe, String what) {
    Instant deadline = Instant.now().plusSeconds(15);
    while (Instant.now().isBefore(deadline)) {
      T value = probe.get();
      if (value != null) {
        return value;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError(e);
      }
    }
    throw new AssertionError("No llegó " + what);
  }

  private void assertConflict(String path, Object body) {
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> post(path, body))
        .isInstanceOf(org.springframework.web.client.HttpClientErrorException.Conflict.class);
  }

  private JsonNode post(String path, Object body) {
    return http.post().uri(path).body(body).retrieve().body(JsonNode.class);
  }

  private JsonNode get(String path) {
    return http.get().uri(path).retrieve().body(JsonNode.class);
  }
}
