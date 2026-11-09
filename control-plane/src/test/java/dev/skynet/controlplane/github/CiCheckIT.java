package dev.skynet.controlplane.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skynet.controlplane.support.IntegrationTest;
import dev.skynet.controlplane.workflow.CiChecks;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;

/**
 * La espera de la CI de una fase {@code github-check} (EN-B), con un runner simulado y el GitHub
 * falso: verde, roja que se corrige en una ronda, roja sin más rondas, sin CI, sin el permiso de
 * los logs y un webhook perdido que resuelve la consulta periódica.
 */
class CiCheckIT extends IntegrationTest {

  private static final FakeGitHub GITHUB = new FakeGitHub();
  private static final String SHA = "c0ffee0000000000000000000000000000000001";
  private static final String FIXED = "c0ffee0000000000000000000000000000000002";

  static final String CI =
      """
      id: ci
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
          fix: implementar
          maxRounds: 1
        - id: despues
          type: agent
          prompt: Sigue
          dependsOn: [ci]
      """;

  @DynamicPropertySource
  static void github(DynamicPropertyRegistry registry) {
    registry.add("skynet.github.app-id", () -> FakeGitHub.APP_ID);
    registry.add("skynet.github.private-key-file", () -> FakeGitHub.writeKey().toString());
    registry.add("skynet.github.webhook-secret", () -> "secreto-de-prueba");
    registry.add("skynet.github.api-url", GITHUB::url);
    registry.add("skynet.github.web-url", () -> "https://github.com");
    // Las PR se abren y la CI se consulta a mano en cada test, no con el planificador.
    registry.add("skynet.github.pull-requests.poll-interval", () -> "1h");
    registry.add("skynet.github.ci.poll-interval", () -> "1h");
  }

  @AfterAll
  static void stop() {
    GITHUB.close();
  }

  @Autowired private PullRequests pullRequests;
  @Autowired private CiChecks ciChecks;
  @Autowired private ApplicationEventPublisher publisher;

  private String repositoryId;
  private String workItemId;
  private String workItemKey;

  @BeforeEach
  void setUp() {
    GITHUB.reset();
    GITHUB.install(7, "guilu", "guilu/demo-repo");
    String projectId =
        post("/api/projects", Map.of("key", "CIX", "name", "CI")).path("id").asString();
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
    workItemKey = item.path("key").asString();
  }

  @Test
  void aGreenCiPassesTheStageAndALostWebhookIsResolvedByThePeriodicCheck() {
    Runner runner = register("portatil");
    String runId = launch(publish(CI)).path("id").asString();
    openPullRequest(runner, runId);

    long test = GITHUB.checkRun(SHA, "test", "github-actions", "in_progress", null);
    ciChecks.checkDue();
    assertThat(stageStatuses(runId)).containsEntry("ci", "RUNNING");
    JsonNode wait = get("/api/workflow-runs/" + runId + "/ci-checks").get(0);
    assertThat(wait.path("status").asString()).isEqualTo("WAITING");
    assertThat(wait.path("headSha").asString()).isEqualTo(SHA);
    assertThat(wait.path("checks").get(0).path("state").asString()).isEqualTo("pending");

    // Termina, pero el webhook no llega: no se pregunta hasta la consulta periódica.
    GITHUB.finishCheckRun(SHA, test, "success");
    ciChecks.checkDue();
    assertThat(stageStatuses(runId)).containsEntry("ci", "RUNNING");
    jdbc.sql("UPDATE github_ci_wait SET next_check_at = now() - interval '1 second'").update();
    ciChecks.checkDue();

    assertThat(stageStatuses(runId)).containsEntry("ci", "SUCCEEDED");
    assertThat(eventTypes(runId)).contains("stage.github.ci.waiting", "stage.github.ci.passed");
    assertThat(taskPrompt(runner.claim().getFirst().path("start"))).isEqualTo("Sigue");
  }

  @Test
  void aWebhookForTheCommitMakesItCheckAtOnce() {
    Runner runner = register("portatil");
    String runId = launch(publish(CI)).path("id").asString();
    openPullRequest(runner, runId);
    ciChecks.checkDue();
    assertThat(stageStatuses(runId)).containsEntry("ci", "RUNNING");

    GITHUB.status(SHA, "ci/jenkins", "success");
    publisher.publishEvent(
        new GitHubCommitChecked(UUID.fromString(repositoryId), "otro".repeat(10)));
    ciChecks.checkDue();
    assertThat(stageStatuses(runId)).containsEntry("ci", "RUNNING");
    publisher.publishEvent(new GitHubCommitChecked(UUID.fromString(repositoryId), SHA));
    ciChecks.checkDue();

    assertThat(stageStatuses(runId)).containsEntry("ci", "SUCCEEDED");
  }

  @Test
  void aRedCiIsFixedInOneRoundAndPushedAgain() {
    Runner runner = register("portatil");
    String runId = launch(publish(CI)).path("id").asString();
    openPullRequest(runner, runId);
    long test = GITHUB.checkRun(SHA, "test", "github-actions", "completed", "failure");
    GITHUB.annotations.put(
        test,
        List.of(
            Map.of(
                "path",
                "calc.py",
                "start_line",
                2,
                "annotation_level",
                "failure",
                "message",
                "assert -1 + 1 == 0")));
    GITHUB.jobLogs.put(
        test,
        "2026-10-31T10:00:00.0000000Z línea 1\n".repeat(300)
            + "2026-10-31T10:00:01.0000000Z FAILED test_calc.py::test_add\n");

    ciChecks.checkDue();

    assertThat(stageStatuses(runId)).containsEntry("ci", "RUNNING");
    JsonNode wait = get("/api/workflow-runs/" + runId + "/ci-checks").get(0);
    assertThat(wait.path("status").asString()).isEqualTo("FIXING");
    assertThat(wait.path("round").asInt()).isEqualTo(1);
    assertThat(GITHUB.comments)
        .singleElement()
        .asString()
        .startsWith("guilu/demo-repo#12: **Skynet:** la CI falló en `test`")
        .contains("ronda 1 de 1");
    assertThat(GITHUB.logDownloadAuthorized).isFalse();

    JsonNode fix = runner.claim().getFirst();
    String prompt = taskPrompt(fix.path("start"));
    assertThat(prompt)
        .contains("La CI de la PR #12 ha fallado en el commit c0ffee0")
        .contains("### test (failure)")
        .contains("- calc.py:2 [failure] assert -1 + 1 == 0")
        .contains("FAILED test_calc.py::test_add")
        .doesNotContain("2026-10-31T10:00:01");
    assertThat(prompt.lines().filter(l -> l.equals("línea 1")).count()).isEqualTo(199);
    complete(runner, fix, Map.of());

    JsonNode push = runner.claim().getFirst();
    assertThat(provider(push.path("agentRunId").asString())).isEqualTo("git-push");
    assertThat(push.path("start").path("push").path("branch").asString())
        .isEqualTo("skynet/" + workItemKey.toLowerCase());
    assertThat(push.path("start").path("push").path("force").asBoolean()).isFalse();
    sendPushed(runner, push, FIXED);

    wait = get("/api/workflow-runs/" + runId + "/ci-checks").get(0);
    assertThat(wait.path("status").asString()).isEqualTo("WAITING");
    assertThat(wait.path("headSha").asString()).isEqualTo(FIXED);
    assertThat(stageStatuses(runId)).containsEntry("ci", "RUNNING");

    GITHUB.checkRun(FIXED, "test", "github-actions", "completed", "success");
    jdbc.sql("UPDATE github_ci_wait SET next_check_at = now() - interval '1 second'").update();
    ciChecks.checkDue();

    assertThat(stageStatuses(runId)).containsEntry("ci", "SUCCEEDED");
    assertThat(
            get("/api/workflow-runs/" + runId + "/pull-requests").get(0).path("headSha").asString())
        .isEqualTo(FIXED);
    assertThat(eventTypes(runId))
        .containsSubsequence(
            "stage.github.ci.waiting",
            "stage.github.ci.failed",
            "stage.github.ci.fix.started",
            "agent.git.pushed",
            "stage.github.ci.waiting",
            "stage.github.ci.passed");
  }

  /** Un juez que aprueba antes de la PR, una CI que se corrige y una puerta de fusión (AU-B). */
  static final String JUDGED =
      """
      id: juzgada-ci
      version: 1
      agents:
        juez:
          model: otro-modelo
        revisor:
          model: otro-modelo
      stages:
        - id: implementar
          type: agent
          prompt: Implementa
        - id: juzgar
          type: verification
          dependsOn: [implementar]
          judge:
            agents: [{agent: juez}, {agent: revisor}]
            rubric:
              - {id: resuelve, text: "Resuelve la tarea", required: true}
        - id: pr
          type: github-pr
          dependsOn: [juzgar]
        - id: ci
          type: github-check
          dependsOn: [pr]
          fix: implementar
          maxRounds: 1
        - id: aprobar
          type: human-approval
          dependsOn: [ci]
          action: Fusionar
        - id: fusionar
          type: github-merge
          dependsOn: [aprobar]
      """;

  /** Lo que vieron los jueces: la instantánea de su copia, con lo que no estaba confirmado. */
  private static final String JUDGED_SHA = "5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a";

  @Test
  void aFixAfterTheJudgeIsJudgedAgainOnlyOnItsDeltaByOneJudgeAtLevelOne() {
    Runner runner = register("portatil");
    String runId = launch(publish(JUDGED)).path("id").asString();
    JsonNode fix = judgedThenRed(runner, runId);
    // La corrección mide su nivel desde lo que se juzgó.
    assertThat(fix.path("start").path("risk").path("base").asString()).isEqualTo(JUDGED_SHA);
    fixed(runner, fix, Map.of("tier", 1, "files", 1, "lines", 3, "base", JUDGED_SHA));

    // La CI pasa, pero la fase espera al juez del delta: nivel 1, solo el primero.
    assertThat(stageStatuses(runId)).containsEntry("ci", "RUNNING");
    assertThat(get("/api/workflow-runs/" + runId + "/ci-checks").get(0).path("status").asString())
        .isEqualTo("JUDGING");
    List<JsonNode> judges = runner.claim();
    assertThat(judges).hasSize(1);
    JsonNode judge = judges.getFirst();
    assertThat(judge.path("start").path("workspace").path("copy").asBoolean()).isTrue();
    assertThat(judge.path("start").path("permissionMode").asString()).isEqualTo("plan");
    assertThat(taskPrompt(judge.path("start")))
        .contains("Este cambio ya lo aprobó un juez en el commit `" + JUDGED_SHA + "`")
        .contains("nivel de riesgo 1")
        .contains("`git diff " + JUDGED_SHA + "`");
    judged(runner, judge, "/w/demo/delta", 4);
    awaitStage(runId, "ci", "SUCCEEDED");

    assertThat(eventTypes(runId))
        .containsSubsequence(
            "stage.github.ci.fix.started",
            "stage.judge.delta.started",
            "stage.judge.delta.verdict",
            "stage.github.ci.passed");
    Map<String, Object> delta =
        jdbc.sql("SELECT status, tier, judged_sha, head_sha FROM judged_delta").query().singleRow();
    assertThat(delta)
        .containsEntry("status", "PASSED")
        .containsEntry("tier", 1)
        .containsEntry("judged_sha", JUDGED_SHA)
        .containsEntry("head_sha", FIXED);
    // La puerta de fusión dice qué se juzgó y qué se fusiona.
    JsonNode approval = awaitApproval(runId);
    JsonNode judged = approval.path("evidence").path("pullRequest").path("judged");
    assertThat(judged.path("judgedSha").asString()).isEqualTo(JUDGED_SHA);
    assertThat(judged.path("mergeSha").asString()).isEqualTo(FIXED);
    assertThat(judged.path("status").asString()).isEqualTo("PASSED");
    assertThat(judged.path("text").asString())
        .startsWith("Juzgado en `5a5a5a5` · se fusiona `c0ffee0` · delta revisado (nivel 1, 4");
  }

  @Test
  void aDeltaOfLevelTwoIsJudgedByThePanelAndFailsTheStageIfTheyFailIt() {
    Runner runner = register("portatil");
    String runId = launch(publish(JUDGED)).path("id").asString();
    JsonNode fix = judgedThenRed(runner, runId);
    fixed(runner, fix, Map.of("tier", 2, "files", 1, "lines", 3, "base", JUDGED_SHA));

    List<JsonNode> judges = runner.claim();
    assertThat(judges).hasSize(2);
    judged(runner, judges.get(0), "/w/demo/delta-1", 2);
    judged(runner, judges.get(1), "/w/demo/delta-2", 2);
    awaitStage(runId, "ci", "FAILED");
    assertThat(get("/api/workflow-runs/" + runId + "/ci-checks").get(0).path("error").asString())
        .startsWith("El juez suspende lo que se cambió después de aprobar: 2");
    assertThat(jdbc.sql("SELECT status FROM judged_delta").query(String.class).single())
        .isEqualTo("FAILED");
  }

  @Test
  void aDeltaOfLevelZeroIsNotJudgedAgain() {
    Runner runner = register("portatil");
    String runId = launch(publish(JUDGED)).path("id").asString();
    fixed(
        runner,
        judgedThenRed(runner, runId),
        Map.of("tier", 0, "files", 1, "lines", 0, "base", JUDGED_SHA));
    awaitStage(runId, "ci", "SUCCEEDED");
    assertThat(eventTypes(runId)).contains("stage.judge.delta.skipped");
    assertThat(
            awaitApproval(runId)
                .path("evidence")
                .path("pullRequest")
                .path("judged")
                .path("text")
                .asString())
        .endsWith("delta de nivel 0, sin volver a juzgar");
  }

  @Test
  void aDeltaMeasuredFromAnotherCommitIsJudgedByTheWholePanel() {
    Runner runner = register("portatil");
    String runId = launch(publish(JUDGED)).path("id").asString();
    fixed(
        runner,
        judgedThenRed(runner, runId),
        Map.of("tier", 0, "files", 1, "lines", 0, "base", "abc1234"));
    assertThat(runner.claim()).hasSize(2);
    assertThat(jdbc.sql("SELECT tier FROM judged_delta").query(Integer.class).single())
        .isEqualTo(2);
  }

  /**
   * Implementa, los dos jueces aprueban (en copias con cambios sin confirmar), se abre la PR y su
   * CI falla: devuelve la corrección.
   */
  private JsonNode judgedThenRed(Runner runner, String runId) {
    JsonNode implement = awaitStart(runner, "Implementa");
    complete(runner, implement, Map.of());
    List<JsonNode> panel = new ArrayList<>();
    for (int i = 0; i < 20 && panel.size() < 2; i++) {
      panel.addAll(runner.claim());
    }
    assertThat(panel).hasSize(2);
    judged(runner, panel.get(0), "/w/demo/juez", 5);
    judged(runner, panel.get(1), "/w/demo/revisor", 5);
    awaitStage(runId, "juzgar", "SUCCEEDED");
    assertThat(
            jdbc.sql("SELECT judged_sha FROM stage_judge WHERE workflow_run_id = ?::uuid")
                .param(runId)
                .query(String.class)
                .single())
        .isEqualTo(JUDGED_SHA);
    sendPushed(runner, runner.claim().getFirst(), SHA);
    pullRequests.openPending();
    awaitStage(runId, "ci", "RUNNING");
    GITHUB.checkRun(SHA, "test", "github-actions", "completed", "failure");
    jdbc.sql("UPDATE github_ci_wait SET next_check_at = now() - interval '1 second'").update();
    ciChecks.checkDue();
    return runner.claim().getFirst();
  }

  /**
   * La corrección termina, el runner sube sus cambios con el nivel medido ({@code risk}), la rama
   * se sube y la CI pasa.
   */
  private void fixed(Runner runner, JsonNode fix, Map<String, Object> risk) {
    upload(
        runner,
        UUID.fromString(fix.path("agentRunId").asString()),
        Map.of("risk", risk, "files", 1));
    complete(runner, fix, Map.of());
    sendPushed(runner, runner.claim().getFirst(), FIXED);
    GITHUB.checkRun(FIXED, "test", "github-actions", "completed", "success");
    jdbc.sql("UPDATE github_ci_wait SET next_check_at = now() - interval '1 second'").update();
    ciChecks.checkDue();
  }

  /** Un juez termina en su copia, con la instantánea de lo que vio y su nota. */
  private void judged(Runner runner, JsonNode command, String path, int score) {
    Events events = new Events(UUID.fromString(command.path("agentRunId").asString()));
    events.add(
        AgentEventType.WORKSPACE_READY,
        Map.of(
            "path",
            path,
            "branch",
            "skynet/copia",
            "baseCommit",
            "abc1234",
            "snapshot",
            JUDGED_SHA));
    events.add(AgentEventType.SESSION_STARTED, Map.of("sessionId", UUID.randomUUID().toString()));
    events.add(
        AgentEventType.RESULT,
        Map.of(
            "subtype",
            "success",
            "isError",
            false,
            "structuredOutput",
            Map.of(
                "criteria",
                List.of(Map.of("id", "resuelve", "score", score, "reason", "motivo")),
                "findings",
                List.of(),
                "summary",
                "Resumen")));
    events.add(AgentEventType.PROCESS_EXITED, Map.of("exitCode", 0));
    runner.send(events);
  }

  private void upload(Runner runner, UUID agentRunId, Map<String, Object> metadata) {
    byte[] content = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String sha256;
    try {
      sha256 =
          java.util.HexFormat.of()
              .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    dev.skynet.protocol.runner.ArtifactUpload upload =
        new dev.skynet.protocol.runner.ArtifactUpload(
            agentRunId,
            null,
            dev.skynet.protocol.runner.ArtifactType.GIT_CHANGES,
            "changes.json",
            "application/json",
            sha256,
            metadata);
    http.post()
        .uri("/api/runner/artifacts")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + runner.token)
        .header(
            dev.skynet.protocol.runner.ArtifactUpload.HEADER,
            java.util.Base64.getUrlEncoder()
                .encodeToString(
                    tools.jackson.databind.json.JsonMapper.builder()
                        .build()
                        .writeValueAsBytes(upload)))
        .contentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)
        .body(content)
        .retrieve()
        .toBodilessEntity();
  }

  private void awaitStage(String runId, String stage, String status) {
    for (int i = 0; i < 100 && !status.equals(stageStatuses(runId).get(stage)); i++) {
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    assertThat(stageStatuses(runId)).containsEntry(stage, status);
  }

  private JsonNode awaitApproval(String runId) {
    for (int i = 0; i < 100; i++) {
      for (JsonNode approval : get("/api/approvals?status=PENDING")) {
        if (approval.path("workflowRunId").asString().equals(runId)) {
          return get("/api/approvals/" + approval.path("id").asString());
        }
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    throw new AssertionError("No llegó la puerta de " + runId);
  }

  @Test
  void aCiStillRedAfterItsRoundsFailsTheStage() {
    GITHUB.deniedPermissions.add("actions");
    Runner runner = register("portatil");
    String runId = launch(publish(CI)).path("id").asString();
    openPullRequest(runner, runId);
    GITHUB.checkRun(SHA, "lint", "github-actions", "completed", "failure");
    ciChecks.checkDue();

    JsonNode fix = runner.claim().getFirst();
    assertThat(taskPrompt(fix.path("start")))
        .contains("### lint (failure)")
        .contains("la App necesita el permiso Actions: lectura");
    complete(runner, fix, Map.of());
    sendPushed(runner, runner.claim().getFirst(), FIXED);
    GITHUB.checkRun(FIXED, "lint", "github-actions", "completed", "failure");
    jdbc.sql("UPDATE github_ci_wait SET next_check_at = now() - interval '1 second'").update();
    ciChecks.checkDue();

    assertThat(stageStatuses(runId)).containsEntry("ci", "FAILED");
    assertThat(get("/api/workflow-runs/" + runId).path("status").asString()).isEqualTo("FAILED");
    assertThat(get("/api/workflow-runs/" + runId + "/ci-checks").get(0).path("error").asString())
        .isEqualTo("La CI falló en lint tras 1 ronda de corrección");
    assertThat(GITHUB.comments).hasSize(1);
  }

  @Test
  void withoutAnyCheckTheStagePassesWithAWarningOrFailsIfAsked() {
    Runner runner = register("portatil");
    String runId = launch(publish(CI)).path("id").asString();
    openPullRequest(runner, runId);
    ciChecks.checkDue();
    assertThat(stageStatuses(runId)).containsEntry("ci", "RUNNING");

    jdbc.sql(
            "UPDATE github_ci_wait SET waiting_since = now() - interval '6 minutes',"
                + " next_check_at = now() - interval '1 second'")
        .update();
    ciChecks.checkDue();

    assertThat(stageStatuses(runId)).containsEntry("ci", "SUCCEEDED");
    assertThat(
            jdbc.sql(
                    "SELECT payload->>'warning' FROM event WHERE workflow_run_id = ?::uuid"
                        + " AND event_type = 'stage.github.ci.passed'")
                .param(runId)
                .query(String.class)
                .single())
        .contains("no tiene CI");

    post("/api/workflow-runs/" + runId + "/cancel", Map.of());
    String strict =
        launch(
                publish(
                    CI.replaceFirst("id: ci\n", "id: ci-estricta\n")
                        .replace("    maxRounds: 1\n", "    maxRounds: 1\n    ifNoChecks: fail\n")))
            .path("id")
            .asString();
    openPullRequest(runner, strict);
    jdbc.sql(
            "UPDATE github_ci_wait SET waiting_since = now() - interval '6 minutes',"
                + " next_check_at = now() - interval '1 second' WHERE workflow_run_id = ?::uuid")
        .param(strict)
        .update();
    ciChecks.checkDue();

    assertThat(stageStatuses(strict)).containsEntry("ci", "FAILED");
  }

  @Test
  void requiredWaitsOnlyForTheNamedChecks() {
    Runner runner = register("portatil");
    String runId =
        launch(
                publish(
                    CI.replace("    maxRounds: 1\n", "    maxRounds: 1\n    required: [test]\n")))
            .path("id")
            .asString();
    openPullRequest(runner, runId);
    GITHUB.checkRun(SHA, "lint", "github-actions", "completed", "failure");
    ciChecks.checkDue();
    assertThat(stageStatuses(runId)).containsEntry("ci", "RUNNING");
    assertThat(GITHUB.comments).isEmpty();

    GITHUB.checkRun(SHA, "test", "github-actions", "completed", "success");
    jdbc.sql("UPDATE github_ci_wait SET next_check_at = now() - interval '1 second'").update();
    ciChecks.checkDue();
    assertThat(stageStatuses(runId)).containsEntry("ci", "SUCCEEDED");
  }

  @Test
  void theStageTimesOut() throws InterruptedException {
    Runner runner = register("portatil");
    String runId =
        launch(publish(CI.replace("    maxRounds: 1\n", "    maxRounds: 1\n    timeout: 2s\n")))
            .path("id")
            .asString();
    openPullRequest(runner, runId);
    for (int i = 0; i < 50 && !"FAILED".equals(stageStatuses(runId).get("ci")); i++) {
      Thread.sleep(200);
    }
    assertThat(stageStatuses(runId)).containsEntry("ci", "FAILED");
    assertThat(eventTypes(runId)).contains("stage.timed.out");
    ciChecks.checkDue();
    assertThat(stageStatuses(runId)).containsEntry("ci", "FAILED");
  }

  /** Implementar termina, la rama se sube con {@link #SHA}, se abre la PR y arranca la espera. */
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
