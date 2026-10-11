package dev.skynet.controlplane.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skynet.controlplane.support.IntegrationTest;
import dev.skynet.protocol.AgentEventType;
import dev.skynet.protocol.NormalizedEvent;
import dev.skynet.protocol.runner.EventBatch;
import dev.skynet.protocol.runner.RunnerRegistration;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;

/**
 * El motor: fases en paralelo, dependencias, worktrees heredados, fail-fast, reinicios (W2) y fases
 * {@code command} (W3).
 */
class WorkflowEngineIT extends IntegrationTest {

  /**
   * {@code plan} y {@code tests} en paralelo; {@code fix} sigue en el worktree de {@code tests}.
   */
  static final String DAG =
      """
      id: dag
      version: 1
      inputs:
        issue:
          type: string
          required: true
      agents:
        fixer:
          prompt: "Arregla {{inputs.issue}} de {{workItem.key}}"
          tools: [Read, Edit, WebFetch]
          permissionMode: plan
          limits:
            maxTurns: 500
      stages:
        - id: plan
          type: agent
          prompt: "Planifica {{inputs.issue}}"
        - id: tests
          type: agent
          prompt: "Escribe tests para {{inputs.issue}}"
        - id: fix
          type: agent
          agent: fixer
          dependsOn: ["plan?", tests]
          workspaceFrom: tests
      """;

  /** Dos fases en cadena; la segunda, en un worktree nuevo. */
  static final String CHAIN =
      """
      id: chain
      version: 1
      stages:
        - id: build
          type: agent
          prompt: construye
        - id: ship
          type: agent
          prompt: publica
          dependsOn: [build]
          workspace: isolated-worktree
      """;

  /** Implementar con un modelo propio, pasar los tests con un comando y revisar. */
  static final String BUILD =
      """
      id: build
      version: 1
      agents:
        dev:
          prompt: Implementa {{workItem.key}}
          model: claude-haiku-4-5
      stages:
        - id: implement
          type: agent
          agent: dev
        - id: tests
          type: command
          command: ./gradlew test
          dependsOn: [implement]
        - id: review
          type: agent
          prompt: Revisa
          dependsOn: [tests]
      """;

  private String projectId;
  private String repositoryId;
  private String workItemId;

  @BeforeEach
  void project() {
    projectId = post("/api/projects", Map.of("key", "ENG", "name", "Motor")).path("id").asString();
    repositoryId =
        post(
                "/api/projects/" + projectId + "/repositories",
                Map.of("name", "demo", "localPath", "/repos/eng"))
            .path("id")
            .asString();
    workItemId =
        post(
                "/api/projects/" + projectId + "/work-items",
                Map.of("title", "Arreglar el login", "type", "BUG"))
            .path("id")
            .asString();
  }

  @Test
  void parallelStagesThenADependentOneInTheWorktreeItContinues() {
    put(
        "/api/projects/" + projectId + "/repositories/" + repositoryId + "/agent-policy",
        Map.of(
            "allowedTools",
            List.of("Read", "Edit", "Bash"),
            "permissionMode",
            "acceptEdits",
            "maxTurns",
            40,
            "maxBudgetUsd",
            5,
            "timeoutMinutes",
            60));
    String definitionId = publish(DAG);
    Runner runner = register("laptop");

    // Antes de lanzar, la web enseña lo que tendrá cada fase en este repositorio.
    JsonNode policies =
        get(
            "/api/workflow-versions/"
                + definitionId
                + "/effective-policy?repositoryId="
                + repositoryId);
    assertThat(policies).hasSize(3);
    assertThat(policies.get(0).path("agent").isNull()).isTrue();
    assertThat(policies.get(0).path("permissionMode").asString()).isEqualTo("acceptEdits");
    assertThat(policies.get(0).path("maxTurns").isNull()).isTrue();
    JsonNode fixPolicy = policies.get(2);
    assertThat(fixPolicy.path("agent").asString()).isEqualTo("fixer");
    assertThat(fixPolicy.path("allowedTools"))
        .extracting(JsonNode::asString)
        .containsExactly("Read", "Edit");
    assertThat(fixPolicy.path("permissionMode").asString()).isEqualTo("plan");
    assertThat(fixPolicy.path("maxTurns").asInt()).isEqualTo(40);

    JsonNode run = launch(definitionId, Map.of("issue", "#42"));
    String runId = run.path("id").asString();
    assertThat(run.path("status").asString()).isEqualTo("RUNNING");
    assertThat(run.path("workflow").path("key").asString()).isEqualTo("dag");
    JsonNode fixStage = run.path("stages").get(2);
    assertThat(fixStage.path("stageKey").asString()).isEqualTo("fix");
    assertThat(fixStage.path("agent").asString()).isEqualTo("fixer");
    assertThat(fixStage.path("dependsOn").get(0).path("stage").asString()).isEqualTo("plan");
    assertThat(fixStage.path("dependsOn").get(0).path("optional").asBoolean()).isTrue();
    assertThat(fixStage.path("dependsOn").get(1).path("optional").asBoolean()).isFalse();
    assertThat(stageStatuses(runId))
        .containsEntry("plan", "READY")
        .containsEntry("tests", "READY")
        .containsEntry("fix", "PENDING");

    List<JsonNode> commands = runner.claim();
    assertThat(commands).hasSize(2);
    Map<String, JsonNode> byPrompt = new HashMap<>();
    commands.forEach(c -> byPrompt.put(c.path("start").path("prompt").asString(), c));
    assertThat(byPrompt).containsOnlyKeys("Planifica #42", "Escribe tests para #42");
    JsonNode tests = byPrompt.get("Escribe tests para #42");
    assertThat(tests.path("start").path("workspace").isNull()).isTrue();

    complete(runner, byPrompt.get("Planifica #42"), "/w/eng/plan");
    assertThat(stageStatuses(runId)).containsEntry("fix", "PENDING");
    complete(runner, tests, "/w/eng/tests");

    // fix arranca en el worktree de tests, en el mismo runner, con lo que el repositorio permite.
    List<JsonNode> fix = runner.claim();
    assertThat(fix).hasSize(1);
    JsonNode start = fix.getFirst().path("start");
    assertThat(start.path("prompt").asString()).isEqualTo("Arregla #42 de ENG-1");
    assertThat(start.path("workspace").path("path").asString()).isEqualTo("/w/eng/tests");
    assertThat(start.path("workspace").path("branch").asString()).isEqualTo("skynet/tests");
    assertThat(start.path("allowedTools"))
        .extracting(JsonNode::asString)
        .doesNotContain("WebFetch");
    assertThat(start.path("permissionMode").asString()).isEqualTo("plan");
    assertThat(start.path("limits").path("maxTurns").asInt()).isLessThan(500);

    complete(runner, fix.getFirst(), "/w/eng/tests");
    JsonNode finished = get("/api/workflow-runs/" + runId);
    assertThat(finished.path("status").asString()).isEqualTo("SUCCEEDED");
    assertThat(eventTypes(runId))
        .contains("stage.pending", "stage.ready", "workflow.status.changed")
        .doesNotContain("stage.start.failed");
    assertThat(jobs()).isZero();

    // Reintentar el agente de fix mantiene lo que su agente del YAML tiene permitido.
    post("/api/agent-runs/" + fix.getFirst().path("agentRunId").asString() + "/retry", Map.of());
    JsonNode retry = runner.claim().getFirst().path("start");
    assertThat(retry.path("allowedTools"))
        .extracting(JsonNode::asString)
        .containsExactly("Read", "Edit");
    assertThat(retry.path("permissionMode").asString()).isEqualTo("plan");
  }

  @Test
  void aFailingStageCancelsItsSiblingsAndFailsTheRun() {
    String definitionId = publish(DAG);
    Runner runner = register("laptop");
    String runId = launch(definitionId, Map.of("issue", "#7")).path("id").asString();
    Map<String, JsonNode> byStage = new HashMap<>();
    runner.claim().forEach(c -> byStage.put(c.path("start").path("prompt").asString(), c));
    JsonNode plan = byStage.get("Planifica #7");
    JsonNode tests = byStage.get("Escribe tests para #7");
    Events testsEvents = new Events(UUID.fromString(tests.path("agentRunId").asString()));
    testsEvents.add(AgentEventType.SESSION_STARTED, Map.of("sessionId", "t"));
    runner.send(testsEvents);

    Events planEvents = new Events(UUID.fromString(plan.path("agentRunId").asString()));
    planEvents.add(AgentEventType.SESSION_STARTED, Map.of("sessionId", "p"));
    planEvents.add(AgentEventType.PROCESS_EXITED, Map.of("exitCode", 1));
    runner.send(planEvents);

    // fix no llega a empezar; a tests se le ordena terminar y la ejecución espera a que acabe.
    assertThat(stageStatuses(runId))
        .containsEntry("plan", "FAILED")
        .containsEntry("tests", "RUNNING")
        .containsEntry("fix", "CANCELLED");
    assertThat(get("/api/workflow-runs/" + runId).path("status").asString()).isEqualTo("RUNNING");
    List<JsonNode> cancel = runner.claim();
    assertThat(cancel).extracting(c -> c.path("type").asString()).containsExactly("CANCEL");

    testsEvents.add(AgentEventType.PROCESS_EXITED, Map.of("exitCode", 143, "signal", "SIGTERM"));
    runner.send(testsEvents);
    assertThat(stageStatuses(runId)).containsEntry("tests", "CANCELLED");
    assertThat(get("/api/workflow-runs/" + runId).path("status").asString()).isEqualTo("FAILED");
  }

  @Test
  void aStageWaitsForTheVerificationOfTheWorktreeItContinues() {
    put(
        "/api/projects/" + projectId + "/repositories/" + repositoryId + "/verification",
        Map.of("validationCommand", "npm test"));
    String definitionId = publish(DAG);
    Runner runner = register("laptop");
    String runId = launch(definitionId, Map.of("issue", "#9")).path("id").asString();
    for (JsonNode command : runner.claim()) {
      String prompt = command.path("start").path("prompt").asString();
      complete(runner, command, prompt.startsWith("Planifica") ? "/w/plan" : "/w/tests");
    }

    // Cada agente deja una verificación en cola; fix espera a la de su worktree.
    List<JsonNode> verifications = runner.claim();
    assertThat(verifications).extracting(c -> c.path("type").asString()).containsOnly("VERIFY");
    assertThat(stageStatuses(runId)).containsEntry("fix", "READY");
    assertThat(agentsOf(runId, "fix")).isZero();

    for (JsonNode verification : verifications) {
      Events events = new Events(UUID.fromString(verification.path("agentRunId").asString()));
      String id = verification.path("verify").path("verificationRunId").asString();
      events.add(AgentEventType.VERIFICATION_STARTED, Map.of("verificationRunId", id));
      events.add(
          AgentEventType.VERIFICATION_COMPLETED, Map.of("verificationRunId", id, "exitCode", 0));
      runner.send(events);
    }
    List<JsonNode> fix = runner.claim();
    assertThat(fix).hasSize(1);
    assertThat(fix.getFirst().path("start").path("workspace").path("path").asString())
        .isEqualTo("/w/tests");
  }

  @Test
  void aCommandStageRunsInTheWorktreeItContinuesAndGatesTheNextStage() {
    put(
        "/api/projects/" + projectId + "/repositories/" + repositoryId + "/verification",
        Map.of("validationCommand", "npm test"));
    String definitionId = publish(BUILD);
    Runner runner = register("laptop");

    JsonNode policies =
        get(
            "/api/workflow-versions/"
                + definitionId
                + "/effective-policy?repositoryId="
                + repositoryId);
    assertThat(policies.get(0).path("model").asString()).isEqualTo("claude-haiku-4-5");
    assertThat(policies.get(1).path("type").asString()).isEqualTo("command");
    assertThat(policies.get(1).path("command").asString()).isEqualTo("./gradlew test");
    assertThat(policies.get(2).path("model").isNull()).isTrue();

    String runId = launch(definitionId, Map.of()).path("id").asString();
    JsonNode implement = runner.claim().getFirst();
    assertThat(implement.path("start").path("model").asString()).isEqualTo("claude-haiku-4-5");
    assertThat(implement.path("start").path("command").isNull()).isTrue();
    complete(runner, implement, "/w/eng/implement");

    // La verificación automática del agente va primero; el comando espera a que termine.
    JsonNode verification = runner.claim().getFirst();
    assertThat(verification.path("type").asString()).isEqualTo("VERIFY");
    assertThat(stageStatuses(runId)).containsEntry("tests", "READY");
    Events verified = new Events(UUID.fromString(verification.path("agentRunId").asString()));
    String verificationId = verification.path("verify").path("verificationRunId").asString();
    verified.add(
        AgentEventType.VERIFICATION_COMPLETED,
        Map.of("verificationRunId", verificationId, "exitCode", 0));
    runner.send(verified);

    // El comando va al runner como una invocación más, en el worktree de implement.
    JsonNode tests = runner.claim().getFirst();
    JsonNode start = tests.path("start");
    assertThat(start.path("command").asString()).isEqualTo("./gradlew test");
    assertThat(start.path("prompt").asString()).isEqualTo("./gradlew test");
    assertThat(start.path("model").isNull()).isTrue();
    assertThat(start.path("workspace").path("path").asString()).isEqualTo("/w/eng/implement");
    assertThat(start.path("limits").path("timeout").isNull()).isFalse();
    String testsAgent = tests.path("agentRunId").asString();
    assertThat(provider(testsAgent)).isEqualTo("command");

    Events events = new Events(UUID.fromString(testsAgent));
    events.add(
        AgentEventType.WORKSPACE_READY,
        Map.of("path", "/w/eng/implement", "branch", "skynet/implement", "baseCommit", "abc"));
    events.add(
        AgentEventType.TOOL_STARTED,
        Map.of("toolUseId", "c", "name", "Bash", "input", Map.of("command", "./gradlew test")));
    runner.send(events);
    assertThat(stageStatuses(runId)).containsEntry("tests", "RUNNING");
    events = new Events(UUID.fromString(testsAgent), 2);
    events.add(
        AgentEventType.TOOL_COMPLETED,
        Map.of("toolUseId", "c", "name", "Bash", "isError", false, "output", "BUILD SUCCESSFUL"));
    events.add(AgentEventType.PROCESS_EXITED, Map.of("exitCode", 0));
    runner.send(events);

    // Sin verificación del comando: lo siguiente es review, en el mismo worktree.
    List<JsonNode> next = runner.claim();
    assertThat(next).hasSize(1);
    assertThat(next.getFirst().path("type").asString()).isEqualTo("START");
    assertThat(next.getFirst().path("start").path("prompt").asString()).isEqualTo("Revisa");
    assertThat(next.getFirst().path("start").path("workspace").path("path").asString())
        .isEqualTo("/w/eng/implement");
    assertThat(stageStatuses(runId)).containsEntry("tests", "SUCCEEDED");

    // Un comando no tiene conversación que continuar ni se reintenta por separado.
    assertConflict(() -> post("/api/agent-runs/" + testsAgent + "/retry", Map.of()));
    assertConflict(
        () -> post("/api/agent-runs/" + testsAgent + "/messages", Map.of("text", "otra vez")));
    assertConflict(() -> post("/api/agent-runs/" + testsAgent + "/verifications", Map.of()));
  }

  @Test
  void aFailingCommandFailsItsStageAndTheRun() {
    String definitionId = publish(BUILD);
    Runner runner = register("laptop");
    String runId = launch(definitionId, Map.of()).path("id").asString();
    complete(runner, runner.claim().getFirst(), "/w/eng/implement");
    JsonNode tests = runner.claim().getFirst();

    Events events = new Events(UUID.fromString(tests.path("agentRunId").asString()));
    events.add(
        AgentEventType.TOOL_STARTED,
        Map.of("toolUseId", "c", "name", "Bash", "input", Map.of("command", "./gradlew test")));
    events.add(
        AgentEventType.TOOL_COMPLETED,
        Map.of("toolUseId", "c", "name", "Bash", "isError", true, "output", "2 tests failed"));
    events.add(AgentEventType.PROCESS_EXITED, Map.of("exitCode", 2));
    runner.send(events);

    assertThat(stageStatuses(runId))
        .containsEntry("tests", "FAILED")
        .containsEntry("review", "CANCELLED");
    JsonNode run = get("/api/workflow-runs/" + runId);
    assertThat(run.path("status").asString()).isEqualTo("FAILED");
    JsonNode agent = run.path("stages").get(1).path("agents").get(0);
    assertThat(agent.path("status").asString()).isEqualTo("FAILED");
    assertThat(agent.path("error").asString()).isEqualTo("El comando terminó con código 2");
  }

  @Test
  void aChangeTheControlPlaneDidNotEvaluateIsPickedUpOnceByAWorker() {
    String definitionId = publish(CHAIN);
    String runId = launch(definitionId, Map.of()).path("id").asString();
    assertThat(agentsOf(runId, "build")).isOne();

    // build termina, pero el control plane cae antes de evaluar la ejecución; un worker la había
    // reclamado y su alquiler sigue vigente.
    jdbc.sql(
            "UPDATE agent_run SET status = 'COMPLETED' WHERE stage_run_id IN (SELECT id FROM"
                + " stage_run WHERE workflow_run_id = ?::uuid)")
        .param(runId)
        .update();
    jdbc.sql(
            "UPDATE stage_run SET status = 'SUCCEEDED' WHERE workflow_run_id = ?::uuid"
                + " AND stage_key = 'build'")
        .param(runId)
        .update();
    Instant now = Instant.now();
    jdbc.sql(
            "INSERT INTO workflow_job (workflow_run_id, run_after, locked_until, created_at)"
                + " VALUES (?::uuid, ?, ?, ?)")
        .params(
            runId, Timestamp.from(now), Timestamp.from(now.plusSeconds(60)), Timestamp.from(now))
        .update();
    sleep(Duration.ofMillis(2500));
    assertThat(agentsOf(runId, "ship")).isZero();

    // Vence el alquiler: otro worker la retoma y arranca ship una sola vez.
    jdbc.sql("UPDATE workflow_job SET locked_until = ? WHERE workflow_run_id = ?::uuid")
        .params(Timestamp.from(now.minusSeconds(1)), runId)
        .update();
    await(() -> jobs() == 0);
    assertThat(agentsOf(runId, "ship")).isOne();

    // Evaluarla otra vez (un worker que repite el trabajo) no duplica nada.
    jdbc.sql(
            "INSERT INTO workflow_job (workflow_run_id, run_after, created_at) VALUES (?::uuid,"
                + " ?, ?)")
        .params(runId, Timestamp.from(now), Timestamp.from(now))
        .update();
    await(() -> jobs() == 0);
    assertThat(agentsOf(runId, "ship")).isOne();
    assertThat(eventTypes(runId).stream().filter("agent.spawned"::equals)).hasSize(2);
  }

  @Test
  void cancellingARunCancelsItsQueuedAgents() {
    String definitionId = publish(DAG);
    String runId = launch(definitionId, Map.of("issue", "#1")).path("id").asString();

    JsonNode cancelled = post("/api/workflow-runs/" + runId + "/cancel", Map.of());

    assertThat(cancelled.path("status").asString()).isEqualTo("CANCELLED");
    assertThat(stageStatuses(runId))
        .containsOnlyKeys("plan", "tests", "fix")
        .doesNotContainValue("READY");
    assertThat(stageStatuses(runId).values()).containsOnly("CANCELLED");
    assertThatThrownBy(() -> post("/api/workflow-runs/" + runId + "/cancel", Map.of()))
        .isInstanceOfSatisfying(
            HttpClientErrorException.class,
            e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
  }

  @Test
  void launchValidatesTheInputsOfTheWorkflow() {
    String definitionId = publish(DAG);

    assertBadRequest(() -> launch(definitionId, Map.of()), "Falta el dato obligatorio `issue`");
    assertBadRequest(
        () -> launch(definitionId, Map.of("issue", "#1", "foco", "x")),
        "El dato `foco` no es de este workflow; admite `issue`");
    assertBadRequest(
        () ->
            post(
                "/api/work-items/" + workItemId + "/runs",
                Map.of("repositoryId", repositoryId, "definitionId", definitionId, "prompt", "x")),
        "El workflow dag no pide un prompt: rellena sus datos");

    // Sin definitionId, el atajo de siempre: adhoc con su prompt.
    JsonNode adhoc =
        post(
            "/api/work-items/" + workItemId + "/runs",
            Map.of("repositoryId", repositoryId, "prompt", "Arregla el login"));
    assertThat(adhoc.path("stages").get(0).path("stageKey").asString()).isEqualTo("agent");
    assertThat(adhoc.path("stages").get(0).path("agents")).hasSize(1);
  }

  // --- Ayudas ---

  private String publish(String yaml) {
    JsonNode draft = post("/api/workflows", Map.of("sourceYaml", yaml));
    String id = draft.path("id").asString();
    post(
        "/api/workflow-versions/" + id + "/publish",
        Map.of("revision", draft.path("revision").asLong()));
    return id;
  }

  private JsonNode launch(String definitionId, Map<String, Object> inputs) {
    return post(
        "/api/work-items/" + workItemId + "/runs",
        Map.of("repositoryId", repositoryId, "definitionId", definitionId, "inputs", inputs));
  }

  /** El agente prepara su worktree y termina bien. */
  private void complete(Runner runner, JsonNode command, String worktree) {
    Events events = new Events(UUID.fromString(command.path("agentRunId").asString()));
    events.add(
        AgentEventType.WORKSPACE_READY,
        Map.of(
            "path",
            worktree,
            "branch",
            "skynet/" + worktree.substring(worktree.lastIndexOf('/') + 1),
            "baseCommit",
            "abc"));
    events.add(AgentEventType.SESSION_STARTED, Map.of("sessionId", UUID.randomUUID().toString()));
    events.add(AgentEventType.RESULT, Map.of("subtype", "success", "isError", false));
    events.add(AgentEventType.PROCESS_EXITED, Map.of("exitCode", 0));
    runner.send(events);
  }

  private Map<String, String> stageStatuses(String runId) {
    Map<String, String> statuses = new LinkedHashMap<>();
    get("/api/workflow-runs/" + runId)
        .path("stages")
        .forEach(s -> statuses.put(s.path("stageKey").asString(), s.path("status").asString()));
    return statuses;
  }

  private int agentsOf(String runId, String stageKey) {
    return jdbc.sql(
            "SELECT count(*) FROM agent_run a JOIN stage_run s ON s.id = a.stage_run_id"
                + " WHERE s.workflow_run_id = ?::uuid AND s.stage_key = ?")
        .params(runId, stageKey)
        .query(Integer.class)
        .single();
  }

  private String provider(String agentRunId) {
    return jdbc.sql("SELECT provider FROM agent_run WHERE id = ?::uuid")
        .param(agentRunId)
        .query(String.class)
        .single();
  }

  private static void assertConflict(Runnable call) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            HttpClientErrorException.class,
            e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
  }

  private int jobs() {
    return jdbc.sql("SELECT count(*) FROM workflow_job").query(Integer.class).single();
  }

  private List<String> eventTypes(String runId) {
    return jdbc.sql(
            "SELECT event_type FROM event WHERE workflow_run_id = ?::uuid ORDER BY sequence")
        .param(runId)
        .query(String.class)
        .list();
  }

  private void assertBadRequest(Runnable call, String message) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            HttpClientErrorException.class,
            e -> {
              assertThat(e.getStatusCode().value()).isEqualTo(400);
              assertThat(e.getResponseBodyAsString()).contains(message);
            });
  }

  private static void await(BooleanSupplier condition) {
    Instant deadline = Instant.now().plusSeconds(15);
    while (!condition.getAsBoolean()) {
      if (Instant.now().isAfter(deadline)) {
        throw new AssertionError("No se cumplió la condición a tiempo");
      }
      sleep(Duration.ofMillis(100));
    }
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private Runner register(String name) {
    JsonNode registered =
        http.post()
            .uri("/api/runner/register")
            .body(new RunnerRegistration(name, RUNNER_REGISTRATION_TOKEN, "dev", 4, "2.1.288"))
            .retrieve()
            .body(JsonNode.class);
    return new Runner(registered.path("token").asString());
  }

  /** Un runner simulado: recoge órdenes, las confirma y envía los eventos de sus agentes. */
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

    void send(Events events) {
      http.post()
          .uri("/api/runner/events")
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
          .body(events.batch())
          .retrieve()
          .toBodilessEntity();
    }
  }

  /** Eventos de una invocación con {@code seq} consecutivo, como los genera el runner. */
  private static final class Events {
    private final UUID agentRunId;
    private final List<NormalizedEvent> all = new ArrayList<>();

    private final int sent;

    Events(UUID agentRunId) {
      this(agentRunId, 0);
    }

    /** Eventos que siguen a los {@code sent} ya enviados. */
    Events(UUID agentRunId, int sent) {
      this.agentRunId = agentRunId;
      this.sent = sent;
    }

    void add(AgentEventType type, Map<String, ?> payload) {
      all.add(
          new NormalizedEvent(
              UUID.randomUUID(),
              agentRunId,
              sent + all.size() + 1,
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

  private JsonNode put(String path, Object body) {
    return http.put().uri(path).body(body).retrieve().body(JsonNode.class);
  }

  private JsonNode get(String path) {
    return http.get().uri(path).retrieve().body(JsonNode.class);
  }
}
