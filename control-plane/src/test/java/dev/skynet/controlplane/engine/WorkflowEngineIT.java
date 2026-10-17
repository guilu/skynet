package dev.skynet.controlplane.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skynet.controlplane.support.IntegrationTest;
import dev.skynet.protocol.AgentEventType;
import dev.skynet.protocol.NormalizedEvent;
import dev.skynet.protocol.runner.EventBatch;
import dev.skynet.protocol.runner.ProviderInfo;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * El motor: fases en paralelo, dependencias, worktrees heredados, fail-fast, reinicios (W2) y fases
 * {@code command}, {@code parallel}, condiciones y {@code conditional} (W3) y puertas de aprobación
 * (W5).
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

  /**
   * Implementar, revisar con dos hijos a la vez, corregir en el worktree original y endurecer en el
   * del revisor de seguridad.
   */
  static final String REVIEW =
      """
      id: review
      version: 1
      agents:
        calidad:
          prompt: Revisa la calidad de {{workItem.key}}
          model: claude-haiku-4-5
        seguridad:
          prompt: Revisa la seguridad
      stages:
        - id: implement
          type: agent
          prompt: Implementa
        - id: review
          type: parallel
          dependsOn: [implement]
          children:
            - agent: calidad
            - agent: seguridad
        - id: fix
          type: agent
          prompt: Corrige
          dependsOn: [review]
        - id: harden
          type: command
          command: make harden
          dependsOn: [review, fix]
          workspaceFrom: review.seguridad
      """;

  /**
   * Implementar, corregir con reintentos en el mismo worktree y pasar los tests, que solo se
   * reintentan si se acaba el tiempo (W6).
   */
  static final String RETRIED =
      """
      id: retried
      version: 1
      stages:
        - id: implement
          type: agent
          prompt: Implementa
        - id: fix
          type: agent
          prompt: Corrige
          dependsOn: [implement]
          retry:
            maxAttempts: 2
            delay: 1s
        - id: tests
          type: command
          command: make test
          dependsOn: [fix]
          retry: {maxAttempts: 3, on: [timeout], delay: 1s}
      """;

  /** REVIEW con reintentos en el revisor de calidad (W6). */
  static final String REVIEW_RETRIED =
      REVIEW
          .replace("id: review\nversion: 1", "id: review-retried\nversion: 1")
          .replace(
              "- agent: calidad\n",
              "- agent: calidad\n        retry: {maxAttempts: 2, delay: 1s}\n");

  /** Revisar con salida estructurada y corregir solo si hay hallazgos que bloquean. */
  static final String FIX =
      """
      id: fix
      version: 1
      agents:
        revisor:
          prompt: Revisa
          output:
            type: object
            properties:
              hasBlockingFindings:
                type: boolean
      stages:
        - id: review
          type: agent
          agent: revisor
        - id: fix
          type: agent
          prompt: Corrige
          dependsOn: [review]
          condition: review.hasBlockingFindings
        - id: limpio
          type: conditional
          dependsOn: [review]
          condition: "!review.hasBlockingFindings"
        - id: final
          type: command
          command: make test
          dependsOn: [review, "fix?"]
          workspaceFrom: review
      """;

  /**
   * Una especificación con su salida en la fase, otra fase con el schema en un fichero del
   * repositorio y un plan que lee las dos en su prompt.
   */
  static final String DATA =
      """
      id: datos
      version: 1
      inputs:
        conExterno:
          type: boolean
          default: true
      agents:
        analista:
          prompt: Especifica
          output:
            type: object
            properties:
              borrador:
                type: string
      stages:
        - id: spec
          type: agent
          agent: analista
          outputSchema:
            type: object
            properties:
              summary:
                type: string
              risks:
                type: array
        - id: externo
          type: agent
          prompt: Externo
          outputSchema: schemas/externo.json
          condition: inputs.conExterno
        - id: plan
          type: agent
          dependsOn: [spec, "externo?"]
          workspaceFrom: spec
          prompt: |
            Resumen: {{stages.spec.output.summary}}
            Riesgos: {{stages.spec.output.risks}}
            Externo: {{stages.externo.status}} {{stages.externo.output.nota}}
      """;

  /** Un agente de Codex con modelo, modo de permisos y salida estructurada propios. */
  static final String CODEX =
      """
      id: codex
      version: 1
      agents:
        worker:
          provider: codex
          model: gpt-5.5
          permissionMode: plan
          prompt: Arregla add()
          output:
            type: object
            properties:
              fixed:
                type: boolean
      stages:
        - id: fix
          type: agent
          agent: worker
      """;

  /** Un revisor de Gemini con salida estructurada. */
  static final String GEMINI =
      """
      id: gemini
      version: 1
      agents:
        revisor:
          provider: gemini
          permissionMode: plan
          prompt: Revisa la seguridad
          output:
            type: object
            properties:
              hasBlockingFindings:
                type: boolean
      stages:
        - id: review
          type: agent
          agent: revisor
      """;

  /** Una especificación que tiene que dejar su documento y cambios, y un plan detrás. */
  static final String VERIFIED =
      """
      id: verificada
      version: 1
      stages:
        - id: spec
          type: agent
          prompt: Especifica
          artifacts: [docs/spec.md]
          checks: [diff]
        - id: plan
          type: agent
          prompt: Planifica
          dependsOn: [spec]
      """;

  /** Un agente, una fase verification que pasa los tests en su worktree y una revisión. */
  static final String NODE =
      """
      id: nodo
      version: 1
      stages:
        - id: implement
          type: agent
          prompt: Implementa
        - id: verify
          type: verification
          dependsOn: [implement]
          checks: [tests, commit]
        - id: review
          type: agent
          prompt: Revisa
          dependsOn: [verify]
      """;

  /** Dos revisores en paralelo que tienen que dejar cambios en su copia. */
  static final String PARALLEL_CHECKS =
      """
      id: paralela-verificada
      version: 1
      agents:
        uno:
          prompt: Uno
        dos:
          prompt: Dos
      stages:
        - id: review
          type: parallel
          checks: [diff]
          children:
            - agent: uno
            - agent: dos
        - id: fix
          type: agent
          prompt: Corrige
          dependsOn: [review]
          workspace: isolated-worktree
      """;

  /** Un plan que se aprueba antes de implementar y pasar los tests. */
  static final String GATE =
      """
      id: puerta
      version: 1
      stages:
        - id: plan
          type: agent
          prompt: Planifica
          outputSchema:
            type: object
            properties:
              summary:
                type: string
        - id: aprobar-plan
          type: human-approval
          dependsOn: [plan]
          action: "Aprobar el plan de {{workItem.key}}: {{stages.plan.output.summary}}"
          risk: high
        - id: implement
          type: agent
          prompt: "Implementa: {{stages.plan.output.summary}}"
          dependsOn: [aprobar-plan]
        - id: tests
          type: command
          command: make test
          dependsOn: [implement]
      """;

  /** Una puerta tras una fase con comprobaciones, que se repiten al corregir. */
  static final String VERIFIED_GATE =
      """
      id: puerta-verificada
      version: 1
      stages:
        - id: spec
          type: agent
          prompt: Especifica
          artifacts: [docs/spec.md]
        - id: aprobar-spec
          type: human-approval
          dependsOn: [spec]
          action: Aprobar la especificación
        - id: plan
          type: agent
          prompt: Planifica
          dependsOn: [aprobar-spec]
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
  void aParallelStageRunsItsChildrenInCopiesAndTheNextStageContinuesTheOriginal() {
    String definitionId = publish(REVIEW);
    Runner runner = register("laptop");

    // Lo que se permite a cada hijo sale como una fase más, <fase>.<hijo>.
    JsonNode policies =
        get(
            "/api/workflow-versions/"
                + definitionId
                + "/effective-policy?repositoryId="
                + repositoryId);
    assertThat(policies)
        .extracting(p -> p.path("stage").asString())
        .containsExactly("implement", "review.calidad", "review.seguridad", "fix", "harden");
    assertThat(policies.get(1).path("type").asString()).isEqualTo("parallel");
    assertThat(policies.get(1).path("agent").asString()).isEqualTo("calidad");
    assertThat(policies.get(1).path("model").asString()).isEqualTo("claude-haiku-4-5");

    String runId = launch(definitionId, Map.of()).path("id").asString();
    complete(runner, runner.claim().getFirst(), "/w/eng/implement");

    // Los dos hijos arrancan a la vez, cada uno en una copia del worktree de implement.
    List<JsonNode> children = runner.claim();
    assertThat(children).hasSize(2);
    Map<String, JsonNode> byPrompt = new HashMap<>();
    children.forEach(c -> byPrompt.put(c.path("start").path("prompt").asString(), c));
    assertThat(byPrompt).containsOnlyKeys("Revisa la calidad de ENG-1", "Revisa la seguridad");
    for (JsonNode child : children) {
      JsonNode workspace = child.path("start").path("workspace");
      assertThat(workspace.path("path").asString()).isEqualTo("/w/eng/implement");
      assertThat(workspace.path("copy").asBoolean()).isTrue();
    }
    JsonNode quality = byPrompt.get("Revisa la calidad de ENG-1");
    assertThat(quality.path("start").path("model").asString()).isEqualTo("claude-haiku-4-5");
    JsonNode review = get("/api/workflow-runs/" + runId).path("stages").get(1);
    assertThat(review.path("agents"))
        .extracting(a -> a.path("childKey").asString())
        .containsExactlyInAnyOrder("calidad", "seguridad");

    // La fase termina cuando terminan los dos.
    complete(runner, quality, "/w/eng/calidad");
    assertThat(stageStatuses(runId))
        .containsEntry("review", "RUNNING")
        .containsEntry("fix", "PENDING");
    complete(runner, byPrompt.get("Revisa la seguridad"), "/w/eng/seguridad");
    assertThat(stageStatuses(runId)).containsEntry("review", "SUCCEEDED");

    // fix continúa el worktree original, sin los cambios de los revisores.
    JsonNode fix = runner.claim().getFirst();
    assertThat(fix.path("start").path("workspace").path("path").asString())
        .isEqualTo("/w/eng/implement");
    assertThat(fix.path("start").path("workspace").path("copy").asBoolean()).isFalse();
    complete(runner, fix, "/w/eng/implement");

    // harden continúa el del revisor de seguridad.
    JsonNode harden = runner.claim().getFirst();
    assertThat(harden.path("start").path("command").asString()).isEqualTo("make harden");
    assertThat(harden.path("start").path("workspace").path("path").asString())
        .isEqualTo("/w/eng/seguridad");
    complete(runner, harden, "/w/eng/seguridad");
    assertThat(get("/api/workflow-runs/" + runId).path("status").asString()).isEqualTo("SUCCEEDED");

    // Reintentar un hijo mantiene lo que su agente del YAML tiene permitido.
    post("/api/agent-runs/" + quality.path("agentRunId").asString() + "/retry", Map.of());
    assertThat(runner.claim().getFirst().path("start").path("model").asString())
        .isEqualTo("claude-haiku-4-5");
  }

  @Test
  void aFailingChildCancelsItsSiblingAndFailsTheRun() {
    String definitionId = publish(REVIEW);
    Runner runner = register("laptop");
    String runId = launch(definitionId, Map.of()).path("id").asString();
    complete(runner, runner.claim().getFirst(), "/w/eng/implement");
    Map<String, JsonNode> byPrompt = new HashMap<>();
    runner.claim().forEach(c -> byPrompt.put(c.path("start").path("prompt").asString(), c));
    Events security =
        new Events(
            UUID.fromString(byPrompt.get("Revisa la seguridad").path("agentRunId").asString()));
    security.add(AgentEventType.SESSION_STARTED, Map.of("sessionId", "s"));
    runner.send(security);

    Events quality =
        new Events(
            UUID.fromString(
                byPrompt.get("Revisa la calidad de ENG-1").path("agentRunId").asString()));
    quality.add(AgentEventType.SESSION_STARTED, Map.of("sessionId", "q"));
    quality.add(AgentEventType.PROCESS_EXITED, Map.of("exitCode", 1));
    runner.send(quality);

    // El hermano recibe la orden de terminar; la fase sigue en curso hasta que acabe.
    assertThat(stageStatuses(runId))
        .containsEntry("review", "RUNNING")
        .containsEntry("fix", "CANCELLED")
        .containsEntry("harden", "CANCELLED");
    assertThat(runner.claim()).extracting(c -> c.path("type").asString()).containsExactly("CANCEL");

    security = new Events(security.agentRunId, 1);
    security.add(AgentEventType.PROCESS_EXITED, Map.of("exitCode", 143, "signal", "SIGTERM"));
    runner.send(security);
    assertThat(stageStatuses(runId)).containsEntry("review", "FAILED");
    assertThat(get("/api/workflow-runs/" + runId).path("status").asString()).isEqualTo("FAILED");
  }

  @Test
  void aTransientFailureIsRetriedFromHowItsDependencyLeftTheWorktree() {
    String definitionId = publish(RETRIED);
    Runner runner = register("laptop");
    String runId = launch(definitionId, Map.of()).path("id").asString();
    complete(runner, runner.claim().getFirst(), "/w/eng/implement");

    // fix falla con un error del proceso: se reintenta, sin cancelar nada.
    JsonNode first = runner.claim().getFirst();
    String firstId = first.path("agentRunId").asString();
    Events events = new Events(UUID.fromString(firstId));
    events.add(
        AgentEventType.WORKSPACE_READY,
        Map.of(
            "path", "/w/eng/implement",
            "branch", "skynet/implement",
            "baseCommit", "abc",
            "snapshot", "snap-1"));
    events.add(AgentEventType.SESSION_STARTED, Map.of("sessionId", "f1"));
    events.add(AgentEventType.PROCESS_EXITED, Map.of("exitCode", 1));
    runner.send(events);

    JsonNode run = get("/api/workflow-runs/" + runId);
    assertThat(run.path("status").asString()).isEqualTo("RUNNING");
    List<JsonNode> fix = stagesOf(run, "fix");
    assertThat(fix)
        .extracting(f -> f.path("status").asString())
        .containsExactly("FAILED", "PENDING");
    assertThat(fix.get(0).path("agents").get(0).path("failure").asString()).isEqualTo("error");
    assertThat(fix.get(1).path("attempt").asInt()).isEqualTo(2);
    assertThat(fix.get(1).path("notBefore").isNull()).isFalse();
    assertThat(fix.get(1).path("retry").path("maxAttempts").asInt()).isEqualTo(2);
    assertThat(eventTypes(runId)).contains("stage.retry.scheduled");

    // Pasada la espera, el segundo intento vuelve al worktree como lo dejó implement.
    JsonNode second = awaitCommand(runner);
    assertThat(second.path("start").path("prompt").asString()).isEqualTo("Corrige");
    JsonNode workspace = second.path("start").path("workspace");
    assertThat(workspace.path("path").asString()).isEqualTo("/w/eng/implement");
    assertThat(workspace.path("restore").asString()).isEqualTo("snap-1");
    JsonNode retried = get("/api/agent-runs/" + second.path("agentRunId").asString()).path("agent");
    assertThat(retried.path("kind").asString()).isEqualTo("RETRY");
    assertThat(retried.path("parentAgentRunId").asString()).isEqualTo(firstId);
    complete(runner, second, "/w/eng/implement");
    assertThat(stageStatuses(runId)).containsEntry("fix", "SUCCEEDED");

    // tests solo se reintenta si se acaba el tiempo: un código distinto de 0 falla la ejecución.
    JsonNode tests = runner.claim().getFirst();
    Events failed = new Events(UUID.fromString(tests.path("agentRunId").asString()));
    failed.add(AgentEventType.PROCESS_EXITED, Map.of("exitCode", 2));
    runner.send(failed);
    assertThat(stageStatuses(runId)).containsEntry("tests", "FAILED");
    assertThat(get("/api/workflow-runs/" + runId).path("status").asString()).isEqualTo("FAILED");
  }

  @Test
  void aStageThatRunsOutOfAttemptsFailsTheRun() {
    String definitionId = publish(RETRIED);
    Runner runner = register("laptop");
    String runId = launch(definitionId, Map.of()).path("id").asString();
    complete(runner, runner.claim().getFirst(), "/w/eng/implement");

    for (int attempt = 1; attempt <= 2; attempt++) {
      JsonNode fix = attempt == 1 ? runner.claim().getFirst() : awaitCommand(runner);
      Events events = new Events(UUID.fromString(fix.path("agentRunId").asString()));
      // El runner se cae a mitad: es un fallo pasajero.
      events.add(
          AgentEventType.PROCESS_EXITED,
          Map.of("error", "El runner se detuvo durante la ejecución", "failure", "lost"));
      runner.send(events);
    }

    await(() -> "FAILED".equals(get("/api/workflow-runs/" + runId).path("status").asString()));
    List<JsonNode> fix = stagesOf(get("/api/workflow-runs/" + runId), "fix");
    assertThat(fix)
        .extracting(f -> f.path("status").asString())
        .containsExactly("FAILED", "FAILED");
    assertThat(fix.get(1).path("agents").get(0).path("failure").asString()).isEqualTo("lost");
    assertThat(stageStatuses(runId)).containsEntry("tests", "CANCELLED");
  }

  @Test
  void cancellingARunWhileARetryWaitsCancelsIt() {
    String definitionId = publish(RETRIED.replace("delay: 1s\n", "delay: 1h\n"));
    Runner runner = register("laptop");
    String runId = launch(definitionId, Map.of()).path("id").asString();
    complete(runner, runner.claim().getFirst(), "/w/eng/implement");
    Events events =
        new Events(UUID.fromString(runner.claim().getFirst().path("agentRunId").asString()));
    events.add(AgentEventType.PROCESS_EXITED, Map.of("exitCode", 1));
    runner.send(events);
    assertThat(stagesOf(get("/api/workflow-runs/" + runId), "fix"))
        .extracting(f -> f.path("status").asString())
        .containsExactly("FAILED", "PENDING");

    post("/api/workflow-runs/" + runId + "/cancel", Map.of());

    JsonNode run = get("/api/workflow-runs/" + runId);
    assertThat(run.path("status").asString()).isEqualTo("CANCELLED");
    assertThat(stagesOf(run, "fix"))
        .extracting(f -> f.path("status").asString())
        .containsExactly("FAILED", "CANCELLED");
  }

  @Test
  void aFailedChildIsRetriedAloneWhileItsSiblingFinishes() {
    String definitionId = publish(REVIEW_RETRIED);
    Runner runner = register("laptop");
    String runId = launch(definitionId, Map.of()).path("id").asString();
    complete(runner, runner.claim().getFirst(), "/w/eng/implement");
    Map<String, JsonNode> byPrompt = new HashMap<>();
    runner.claim().forEach(c -> byPrompt.put(c.path("start").path("prompt").asString(), c));
    JsonNode quality = byPrompt.get("Revisa la calidad de ENG-1");

    // calidad falla; seguridad no recibe la orden de terminar.
    Events failed = new Events(UUID.fromString(quality.path("agentRunId").asString()));
    failed.add(AgentEventType.PROCESS_EXITED, Map.of("exitCode", 1));
    runner.send(failed);
    assertThat(stageStatuses(runId))
        .containsEntry("review", "STARTING")
        .containsEntry("fix", "PENDING");
    assertThat(runner.claim()).isEmpty();

    // Cuando seguridad termina, la fase falla y se programa otro intento solo para calidad.
    complete(runner, byPrompt.get("Revisa la seguridad"), "/w/eng/seguridad");
    assertThat(stagesOf(get("/api/workflow-runs/" + runId), "review"))
        .extracting(r -> r.path("status").asString())
        .containsExactly("FAILED", "PENDING");
    JsonNode again = awaitCommand(runner);
    assertThat(again.path("start").path("prompt").asString())
        .isEqualTo("Revisa la calidad de ENG-1");
    assertThat(again.path("start").path("workspace").path("copy").asBoolean()).isTrue();
    assertThat(runner.claim()).isEmpty();
    complete(runner, again, "/w/eng/calidad-2");
    assertThat(stageStatuses(runId)).containsEntry("review", "SUCCEEDED");

    // fix sigue; harden continúa el worktree de seguridad, del primer intento.
    complete(runner, runner.claim().getFirst(), "/w/eng/implement");
    JsonNode harden = runner.claim().getFirst();
    assertThat(harden.path("start").path("workspace").path("path").asString())
        .isEqualTo("/w/eng/seguridad");
    complete(runner, harden, "/w/eng/seguridad");
    assertThat(get("/api/workflow-runs/" + runId).path("status").asString()).isEqualTo("SUCCEEDED");
  }

  @Test
  void aStageWithChecksKeepsRunningUntilItsVerificationPasses() {
    String definitionId = publish(VERIFIED);
    Runner runner = register("laptop");
    String runId = launch(definitionId, Map.of()).path("id").asString();
    complete(runner, runner.claim().getFirst(), "/w/eng/spec");

    // El agente ha terminado, pero la fase sigue en curso mientras se comprueba su worktree.
    assertThat(stageStatuses(runId))
        .containsEntry("spec", "RUNNING")
        .containsEntry("plan", "PENDING");
    JsonNode verify = runner.claim().getFirst();
    assertThat(verify.path("type").asString()).isEqualTo("VERIFY");
    JsonNode request = verify.path("verify");
    assertThat(request.path("command").isNull()).isTrue();
    assertThat(request.path("baseCommit").asString()).isEqualTo("abc");
    assertThat(request.path("checks"))
        .extracting(c -> c.path("name").asString())
        .containsExactly("artifacts", "diff");
    assertThat(request.path("checks").get(0).path("paths").get(0).asString())
        .isEqualTo("docs/spec.md");

    // La vista de la ejecución lleva las comprobaciones de la fase y su verificación en curso.
    JsonNode specView = get("/api/workflow-runs/" + runId).path("stages").get(0);
    assertThat(specView.path("checks")).hasSize(2);
    assertThat(specView.path("verifications").get(0).path("live").asBoolean()).isTrue();

    verified(runner, verify, true);
    assertThat(stageStatuses(runId)).containsEntry("spec", "SUCCEEDED");
    specView = get("/api/workflow-runs/" + runId).path("stages").get(0);
    assertThat(specView.path("verifications").get(0).path("status").asString()).isEqualTo("PASSED");
    assertThat(specView.path("verifications").get(0).path("checks").get(1).path("name").asString())
        .isEqualTo("diff");
    JsonNode plan = runner.claim().getFirst();
    assertThat(plan.path("start").path("prompt").asString()).isEqualTo("Planifica");
    assertThat(plan.path("start").path("workspace").path("path").asString())
        .isEqualTo("/w/eng/spec");
    assertThat(eventTypes(runId))
        .containsSubsequence("stage.verification.started", "stage.verification.completed");
  }

  @Test
  void aMissingArtifactFailsTheStageWithItsReasonAndTheNextStageDoesNotStart() {
    String definitionId = publish(VERIFIED);
    Runner runner = register("laptop");
    String runId = launch(definitionId, Map.of()).path("id").asString();
    JsonNode spec = runner.claim().getFirst();
    complete(runner, spec, "/w/eng/spec");

    verified(runner, runner.claim().getFirst(), false);

    assertThat(stageStatuses(runId))
        .containsEntry("spec", "FAILED")
        .containsEntry("plan", "CANCELLED");
    assertThat(get("/api/workflow-runs/" + runId).path("status").asString()).isEqualTo("FAILED");
    assertThat(runner.claim()).isEmpty();
    JsonNode verification =
        get("/api/agent-runs/" + spec.path("agentRunId").asString() + "/verifications").get(0);
    assertThat(verification.path("trigger").asString()).isEqualTo("STAGE");
    assertThat(verification.path("status").asString()).isEqualTo("FAILED");
    assertThat(verification.path("checks").get(0).path("reason").asString())
        .isEqualTo("`docs/spec.md` no existe");
  }

  @Test
  void aVerificationStageChecksTheWorktreeItInheritsAndTheNextStageContinuesIt() {
    put(
        "/api/projects/" + projectId + "/repositories/" + repositoryId + "/verification",
        Map.of("validationCommand", "npm test"));
    String definitionId = publish(NODE);
    Runner runner = register("laptop");
    String runId = launch(definitionId, Map.of()).path("id").asString();
    complete(runner, runner.claim().getFirst(), "/w/eng/implement");

    // implement no tiene comprobaciones: su verificación automática solo informa.
    JsonNode auto = runner.claim().getFirst();
    assertThat(auto.path("verify").path("checks")).isEmpty();
    verified(runner, auto, true);

    // La fase verification no arranca ningún agente: comprueba el worktree de implement.
    JsonNode verify = runner.claim().getFirst();
    assertThat(verify.path("type").asString()).isEqualTo("VERIFY");
    assertThat(verify.path("verify").path("command").asString()).isEqualTo("npm test");
    assertThat(verify.path("verify").path("workspacePath").asString())
        .isEqualTo("/w/eng/implement");
    assertThat(verify.path("verify").path("baseCommit").asString()).isEqualTo("abc");
    assertThat(provider(verify.path("agentRunId").asString())).isEqualTo("verification");
    assertThat(stageStatuses(runId)).containsEntry("verify", "RUNNING");
    String nodeAgent = verify.path("agentRunId").asString();
    assertConflict(() -> post("/api/agent-runs/" + nodeAgent + "/retry", Map.of()));

    verified(runner, verify, true);
    assertThat(stageStatuses(runId)).containsEntry("verify", "SUCCEEDED");
    JsonNode review = runner.claim().getFirst();
    assertThat(review.path("start").path("prompt").asString()).isEqualTo("Revisa");
    assertThat(review.path("start").path("workspace").path("path").asString())
        .isEqualTo("/w/eng/implement");
  }

  @Test
  void aFailingVerificationStageFailsWithTheChecksThatDidNotPass() {
    String definitionId = publish(NODE);
    Runner runner = register("laptop");
    String runId = launch(definitionId, Map.of()).path("id").asString();
    complete(runner, runner.claim().getFirst(), "/w/eng/implement");

    // Sin comando en el repositorio, tests no pasa.
    JsonNode verify = runner.claim().getFirst();
    assertThat(verify.path("verify").path("command").isNull()).isTrue();
    verified(runner, verify, false);

    assertThat(stageStatuses(runId))
        .containsEntry("verify", "FAILED")
        .containsEntry("review", "CANCELLED");
    JsonNode agent = get("/api/workflow-runs/" + runId).path("stages").get(1).path("agents").get(0);
    assertThat(agent.path("status").asString()).isEqualTo("FAILED");
    assertThat(agent.path("error").asString()).isEqualTo("tests: no pasa; commit: no pasa");
  }

  @Test
  void aParallelChildThatFailsItsChecksFailsTheStage() {
    String definitionId = publish(PARALLEL_CHECKS);
    Runner runner = register("laptop");
    String runId = launch(definitionId, Map.of()).path("id").asString();
    List<JsonNode> children = runner.claim();
    assertThat(children).hasSize(2);
    complete(runner, children.get(0), "/w/eng/uno");
    complete(runner, children.get(1), "/w/eng/dos");

    // Cada hijo se verifica en su worktree.
    List<JsonNode> verifications = runner.claim();
    assertThat(verifications)
        .extracting(v -> v.path("verify").path("workspacePath").asString())
        .containsExactlyInAnyOrder("/w/eng/uno", "/w/eng/dos");
    verified(runner, verifications.get(0), false);

    assertThat(stageStatuses(runId))
        .containsEntry("review", "FAILED")
        .containsEntry("fix", "CANCELLED");
    verified(runner, verifications.get(1), true);
    assertThat(stageStatuses(runId)).containsEntry("review", "FAILED");
    assertThat(get("/api/workflow-runs/" + runId).path("status").asString()).isEqualTo("FAILED");
  }

  @Test
  void messagesAndForksKeepTheAgentOfTheWorkflowAlongTheWholeSession() {
    String definitionId = publish(CODEX);
    Runner runner = register("servidor", List.of(new ProviderInfo("codex", "0.160.0")));
    launch(definitionId, Map.of());
    JsonNode start = runner.claim().getFirst();
    assertKeepsTheAgent(start);
    String agentId = start.path("agentRunId").asString();
    String session = UUID.randomUUID().toString();
    complete(runner, start, "/w/eng/fix", Map.of("fixed", true), session);

    // Un mensaje continúa la sesión con el agente del YAML.
    String resumedRun =
        post("/api/agent-runs/" + agentId + "/messages", Map.of("text", "Añade subtract()"))
            .path("id")
            .asString();
    JsonNode resume = runner.claim().getFirst();
    assertThat(resume.path("type").asString()).isEqualTo("RESUME");
    assertKeepsTheAgent(resume);
    assertThat(resume.path("start").path("sessionId").asString()).isEqualTo(session);
    assertThat(resume.path("start").path("resume").path("fork").asBoolean()).isFalse();
    complete(runner, resume, "/w/eng/fix", Map.of("fixed", true), session);
    assertThat(get("/api/workflow-runs/" + resumedRun).path("status").asString())
        .isEqualTo("SUCCEEDED");

    // Bifurcar el lanzamiento parte de la última continuación, que se ejecutó aparte: el agente
    // sigue siendo el del YAML, no el adhoc de la continuación.
    post("/api/agent-runs/" + agentId + "/fork", Map.of("text", "Añade multiply()"));
    JsonNode fork = runner.claim().getFirst();
    assertThat(fork.path("type").asString()).isEqualTo("RESUME");
    assertKeepsTheAgent(fork);
    assertThat(fork.path("start").path("sessionId").asString()).isNotEqualTo(session);
    assertThat(fork.path("start").path("resume").path("fork").asBoolean()).isTrue();
    assertThat(fork.path("start").path("resume").path("sessionId").asString()).isEqualTo(session);

    // Y un mensaje a la rama bifurcada, también.
    String branch = UUID.randomUUID().toString();
    complete(runner, fork, "/w/eng/fork", Map.of("fixed", true), branch);
    post(
        "/api/agent-runs/" + fork.path("agentRunId").asString() + "/messages",
        Map.of("text", "Y documenta"));
    assertKeepsTheAgent(runner.claim().getFirst());
  }

  @Test
  void aGeminiAgentRunsOnARunnerWithGeminiAndCanBeContinuedButNotForked() {
    String definitionId = publish(GEMINI);
    Runner claude = register("laptop");
    Runner gemini = register("servidor", List.of(new ProviderInfo("gemini", "0.65.0")));
    String runId = launch(definitionId, Map.of()).path("id").asString();

    assertThat(claude.claim()).isEmpty();
    JsonNode start = gemini.claim().getFirst();
    assertThat(start.path("start").path("provider").asString()).isEqualTo("gemini");
    assertThat(start.path("start").path("permissionMode").asString()).isEqualTo("plan");
    assertThat(start.path("start").path("outputSchema").asString())
        .contains("\"hasBlockingFindings\"");
    String session = start.path("start").path("sessionId").asString();
    complete(gemini, start, "/w/eng/review", Map.of("hasBlockingFindings", false), session);
    assertThat(get("/api/workflow-runs/" + runId).path("status").asString()).isEqualTo("SUCCEEDED");

    String agentId = start.path("agentRunId").asString();
    assertConflict(() -> post("/api/agent-runs/" + agentId + "/fork", Map.of("text", "otra")));
    post("/api/agent-runs/" + agentId + "/messages", Map.of("text", "Mira también el login"));
    JsonNode resume = gemini.claim().getFirst();
    assertThat(resume.path("type").asString()).isEqualTo("RESUME");
    assertThat(resume.path("start").path("provider").asString()).isEqualTo("gemini");
    assertThat(resume.path("start").path("resume").path("sessionId").asString()).isEqualTo(session);
    assertThat(resume.path("start").path("outputSchema").asString())
        .contains("\"hasBlockingFindings\"");
  }

  @Test
  void theProviderAndModelOfTheLaunchRunTheAgentWithoutNameAndItsContinuations() {
    Runner claude = register("laptop");
    Runner codex = register("servidor", List.of(new ProviderInfo("codex", "0.160.0")));
    JsonNode providers = get("/api/providers");
    assertThat(providers.findValuesAsString("id"))
        .containsExactly("claude-code", "codex", "gemini");
    assertThat(providers.get(1).path("onlineRunners").asInt()).isEqualTo(1);
    assertThat(providers.get(2).path("capabilities").toString()).doesNotContain("FORK");

    String runId =
        post(
                "/api/work-items/" + workItemId + "/runs",
                Map.of(
                    "repositoryId",
                    repositoryId,
                    "prompt",
                    "Arregla el login",
                    "provider",
                    "codex",
                    "model",
                    "gpt-5.5"))
            .path("id")
            .asString();
    assertThat(claude.claim()).isEmpty();
    JsonNode start = codex.claim().getFirst();
    assertThat(start.path("start").path("provider").asString()).isEqualTo("codex");
    assertThat(start.path("start").path("model").asString()).isEqualTo("gpt-5.5");
    String session = UUID.randomUUID().toString();
    complete(codex, start, "/w/eng/agent", null, session);
    assertThat(get("/api/workflow-runs/" + runId).path("status").asString()).isEqualTo("SUCCEEDED");

    // Mandar un mensaje y bifurcar siguen con el proveedor y el modelo del lanzamiento.
    String agentId = start.path("agentRunId").asString();
    post("/api/agent-runs/" + agentId + "/messages", Map.of("text", "Y el logout"));
    JsonNode resume = codex.claim().getFirst();
    assertThat(resume.path("start").path("provider").asString()).isEqualTo("codex");
    assertThat(resume.path("start").path("model").asString()).isEqualTo("gpt-5.5");
    complete(codex, resume, "/w/eng/agent", null, session);
    post("/api/agent-runs/" + agentId + "/fork", Map.of("text", "Otra solución"));
    JsonNode fork = codex.claim().getFirst();
    assertThat(fork.path("start").path("model").asString()).isEqualTo("gpt-5.5");

    // Claude Code sin modelo es lo de siempre.
    post(
        "/api/work-items/" + workItemId + "/runs",
        Map.of("repositoryId", repositoryId, "prompt", "Otra", "provider", "claude-code"));
    JsonNode plain = claude.claim().getFirst();
    assertThat(plain.path("start").path("provider").asString()).isEqualTo("claude-code");
  }

  @Test
  void theLaunchRejectsAnUnknownProviderAndOneForAWorkflowWhoseAgentsChooseTheirs() {
    assertBadRequest(
        () ->
            post(
                "/api/work-items/" + workItemId + "/runs",
                Map.of("repositoryId", repositoryId, "prompt", "x", "provider", "opencode")),
        "El proveedor `opencode` no existe");
    assertBadRequest(
        () ->
            post(
                "/api/work-items/" + workItemId + "/runs",
                Map.of("repositoryId", repositoryId, "prompt", "x", "model", "gpt 5; rm -rf")),
        "no es válido");
    String definitionId = publish(GEMINI);
    assertBadRequest(
        () ->
            post(
                "/api/work-items/" + workItemId + "/runs",
                Map.of(
                    "repositoryId",
                    repositoryId,
                    "definitionId",
                    definitionId,
                    "provider",
                    "codex")),
        "En gemini cada fase fija su agente");

    JsonNode policy =
        get("/api/workflow-versions/"
                + definitionId
                + "/effective-policy?repositoryId="
                + repositoryId)
            .get(0);
    assertThat(policy.path("provider").asString()).isEqualTo("gemini");
    assertThat(policy.path("unsupported").toString()).contains("allowedTools", "maxTurns");
  }

  private static void assertKeepsTheAgent(JsonNode command) {
    JsonNode start = command.path("start");
    assertThat(start.path("provider").asString()).isEqualTo("codex");
    assertThat(start.path("model").asString()).isEqualTo("gpt-5.5");
    assertThat(start.path("permissionMode").asString()).isEqualTo("plan");
    assertThat(start.path("outputSchema").asString()).contains("\"fixed\"");
  }

  @Test
  void theNextStageReceivesTheOutputOfThePreviousOnesInItsPrompt() {
    String definitionId = publish(DATA);
    Runner runner = register("laptop");
    String runId = launch(definitionId, Map.of()).path("id").asString();
    Map<String, JsonNode> started = new LinkedHashMap<>();
    runner.claim().forEach(c -> started.put(c.path("start").path("prompt").asString(), c));
    assertThat(started).containsOnlyKeys("Especifica", "Externo");

    // El outputSchema de la fase manda sobre el output del agente; el de un fichero va como $ref.
    JsonNode spec = started.get("Especifica");
    assertThat(spec.path("start").path("outputSchema").asString())
        .contains("\"summary\"")
        .doesNotContain("borrador");
    JsonNode externo = started.get("Externo");
    assertThat(externo.path("start").path("outputSchema").asString())
        .isEqualTo("{\"$ref\":\"schemas/externo.json\"}");

    complete(runner, spec, "/w/eng/spec", Map.of("summary", "Hacer X", "risks", List.of("a", "b")));
    complete(runner, externo, "/w/eng/externo", Map.of("nota", 7));

    JsonNode plan = runner.claim().getFirst();
    assertThat(plan.path("start").path("prompt").asString())
        .isEqualTo("Resumen: Hacer X\nRiesgos: [\"a\",\"b\"]\nExterno: SUCCEEDED 7\n");
    assertThat(plan.path("start").path("outputSchema").isNull()).isTrue();
    complete(runner, plan, "/w/eng/spec");
    assertThat(get("/api/workflow-runs/" + runId).path("status").asString()).isEqualTo("SUCCEEDED");
  }

  @Test
  void aSkippedStageLeavesItsDataEmptyInThePrompt() {
    String definitionId = publish(DATA);
    Runner runner = register("laptop");
    launch(definitionId, Map.of("conExterno", false));
    JsonNode spec = runner.claim().getFirst();
    complete(runner, spec, "/w/eng/spec", Map.of("summary", "Hacer X", "risks", List.of()));

    assertThat(runner.claim().getFirst().path("start").path("prompt").asString())
        .isEqualTo("Resumen: Hacer X\nRiesgos: []\nExterno: SKIPPED \n");
  }

  @Test
  void aStageWithoutItsDeclaredOutputFails() {
    String definitionId = publish(DATA);
    Runner runner = register("laptop");
    String runId = launch(definitionId, Map.of("conExterno", false)).path("id").asString();
    complete(runner, runner.claim().getFirst(), "/w/eng/spec");

    JsonNode run = get("/api/workflow-runs/" + runId);
    assertThat(run.path("status").asString()).isEqualTo("FAILED");
    assertThat(stageStatuses(runId)).containsEntry("spec", "FAILED");
  }

  @Test
  void aFalseConditionSkipsItsStageAndTheOptionalDependentRuns() {
    String definitionId = publish(FIX);
    Runner runner = register("laptop");
    String runId = launch(definitionId, Map.of()).path("id").asString();
    JsonNode review = runner.claim().getFirst();
    assertThat(review.path("start").path("outputSchema").asString())
        .contains("\"hasBlockingFindings\"");

    complete(runner, review, "/w/eng/review", Map.of("hasBlockingFindings", false));

    assertThat(stageStatuses(runId))
        .containsEntry("fix", "SKIPPED")
        .containsEntry("limpio", "SUCCEEDED");
    JsonNode skipped =
        jdbc.sql(
                "SELECT CAST(payload AS text) FROM event WHERE workflow_run_id = ?::uuid"
                    + " AND event_type = 'stage.skipped'")
            .param(runId)
            .query((rs, i) -> JsonMapper.shared().readTree(rs.getString(1)))
            .single();
    assertThat(skipped.path("reason").asString()).isEqualTo("condition-false");
    assertThat(skipped.path("condition").asString()).isEqualTo("review.hasBlockingFindings");
    assertThat(skipped.path("values").path("review.hasBlockingFindings").asBoolean(true)).isFalse();
    JsonNode agent = get("/api/workflow-runs/" + runId).path("stages").get(0).path("agents").get(0);
    assertThat(agent.path("structuredOutput").path("hasBlockingFindings").isBoolean()).isTrue();

    // final depende de fix?: arranca aunque fix se haya omitido.
    JsonNode last = runner.claim().getFirst();
    assertThat(last.path("start").path("command").asString()).isEqualTo("make test");
    complete(runner, last, "/w/eng/review");
    assertThat(get("/api/workflow-runs/" + runId).path("status").asString()).isEqualTo("SUCCEEDED");
  }

  @Test
  void aTrueConditionRunsItsStage() {
    String definitionId = publish(FIX);
    Runner runner = register("laptop");
    String runId = launch(definitionId, Map.of()).path("id").asString();
    complete(
        runner, runner.claim().getFirst(), "/w/eng/review", Map.of("hasBlockingFindings", true));

    assertThat(stageStatuses(runId))
        .containsEntry("limpio", "SKIPPED")
        .containsEntry("final", "PENDING");
    JsonNode fix = runner.claim().getFirst();
    assertThat(fix.path("start").path("prompt").asString()).isEqualTo("Corrige");
    assertThat(fix.path("start").path("outputSchema").isNull()).isTrue();
    assertThat(eventTypes(runId)).contains("stage.condition.evaluated");
  }

  @Test
  void anAgentThatDoesNotReturnItsOutputFails() {
    String definitionId = publish(FIX);
    Runner runner = register("laptop");
    String runId = launch(definitionId, Map.of()).path("id").asString();
    complete(runner, runner.claim().getFirst(), "/w/eng/review");

    JsonNode run = get("/api/workflow-runs/" + runId);
    assertThat(run.path("status").asString()).isEqualTo("FAILED");
    assertThat(run.path("stages").get(0).path("agents").get(0).path("error").asString())
        .isEqualTo("El agente no devolvió la salida estructurada que pide su `output`");
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
  void anApprovalGateWaitsForADecisionAndApprovingContinuesInTheApprovedWorktree() {
    String definitionId = publish(GATE);
    Runner runner = register("laptop");
    String runId = launch(definitionId, Map.of()).path("id").asString();
    JsonNode plan = runner.claim().getFirst();
    complete(runner, plan, "/w/eng/plan", Map.of("summary", "Hacer X"));

    // La puerta espera con su petición; implement no arranca.
    assertThat(stageStatuses(runId))
        .containsEntry("plan", "SUCCEEDED")
        .containsEntry("aprobar-plan", "WAITING_FOR_APPROVAL")
        .containsEntry("implement", "PENDING");
    assertThat(runner.claim()).isEmpty();
    JsonNode pending = get("/api/approvals?status=PENDING");
    assertThat(pending).hasSize(1);
    String approvalId = pending.get(0).path("id").asString();
    assertThat(pending.get(0).path("action").asString())
        .isEqualTo("Aprobar el plan de ENG-1: Hacer X");
    assertThat(pending.get(0).path("risk").asString()).isEqualTo("HIGH");
    assertThat(pending.get(0).path("round").asInt()).isOne();
    assertThat(pending.get(0).path("stageKey").asString()).isEqualTo("aprobar-plan");
    assertThat(pending.get(0).path("origin").path("workItem").path("key").asString())
        .isEqualTo("ENG-1");
    JsonNode gate = get("/api/workflow-runs/" + runId).path("stages").get(1);
    assertThat(gate.path("type").asString()).isEqualTo("human-approval");
    assertThat(gate.path("reads").get(0).asString()).isEqualTo("plan.output.summary");
    assertThat(gate.path("approval").path("id").asString()).isEqualTo(approvalId);
    assertThat(gate.path("approval").path("status").asString()).isEqualTo("PENDING");

    // La evidencia: lo que dejó plan, lo que se concede y qué pasa con cada decisión.
    JsonNode evidence = get("/api/approvals/" + approvalId).path("evidence");
    JsonNode planAgent = evidence.path("dependencies").get(0).path("agents").get(0);
    assertThat(evidence.path("dependencies").get(0).path("stageKey").asString()).isEqualTo("plan");
    assertThat(planAgent.path("structuredOutput").path("summary").asString()).isEqualTo("Hacer X");
    assertThat(planAgent.path("agentRunId").asString())
        .isEqualTo(plan.path("agentRunId").asString());
    assertThat(evidence.path("revise").path("stageKey").asString()).isEqualTo("plan");
    assertThat(evidence.path("grants")).hasSize(1);
    assertThat(evidence.path("grants").get(0).path("stage").asString()).isEqualTo("implement");
    assertThat(evidence.path("consequences").path("approve").asString())
        .isEqualTo("Pueden arrancar: implement.");
    assertThat(evidence.path("consequences").path("reject").asString())
        .isEqualTo("La ejecución falla y se cancelan implement, tests.");
    assertThat(evidence.path("origin").path("workflow").path("key").asString()).isEqualTo("puerta");

    // Comentar no decide; un worker que vuelve a evaluar la ejecución (un reinicio) tampoco.
    JsonNode commented =
        post(
            "/api/approvals/" + approvalId + "/decisions",
            Map.of("action", "COMMENT", "comment", "Mira el riesgo de X"));
    assertThat(commented.path("status").asString()).isEqualTo("PENDING");
    assertThat(commented.path("decisions")).hasSize(1);
    assertThat(commented.path("decisions").get(0).path("actor").asString()).isNotBlank();
    Instant now = Instant.now();
    jdbc.sql(
            "INSERT INTO workflow_job (workflow_run_id, run_after, created_at) VALUES (?::uuid,"
                + " ?, ?)")
        .params(runId, Timestamp.from(now), Timestamp.from(now))
        .update();
    await(() -> jobs() == 0);
    assertThat(stageStatuses(runId)).containsEntry("aprobar-plan", "WAITING_FOR_APPROVAL");
    assertThat(runner.claim()).isEmpty();

    JsonNode approved =
        post("/api/approvals/" + approvalId + "/decisions", Map.of("action", "APPROVE"));
    assertThat(approved.path("status").asString()).isEqualTo("APPROVED");
    assertThat(approved.path("decidedAt").isNull()).isFalse();
    assertThat(approved.path("decisions").get(1).path("action").asString()).isEqualTo("APPROVE");
    assertConflict(
        () -> post("/api/approvals/" + approvalId + "/decisions", Map.of("action", "APPROVE")));
    assertThat(get("/api/approvals?status=PENDING")).isEmpty();

    // implement continúa el worktree de plan, que la puerta pasa sin tocar.
    JsonNode implement = runner.claim().getFirst();
    assertThat(implement.path("start").path("workspace").path("path").asString())
        .isEqualTo("/w/eng/plan");
    complete(runner, implement, "/w/eng/plan");
    JsonNode tests = runner.claim().getFirst();
    assertThat(tests.path("start").path("workspace").path("path").asString())
        .isEqualTo("/w/eng/plan");
    complete(runner, tests, "/w/eng/plan");
    assertThat(get("/api/workflow-runs/" + runId).path("status").asString()).isEqualTo("SUCCEEDED");
    assertThat(eventTypes(runId))
        .contains("approval.requested", "approval.commented", "approval.granted");

    // Las decisiones y las peticiones decididas no se reescriben.
    assertThatThrownBy(() -> jdbc.sql("UPDATE approval_decision SET comment = 'otro'").update())
        .hasMessageContaining("no se modifica");
    assertThatThrownBy(() -> jdbc.sql("DELETE FROM approval_decision").update())
        .hasMessageContaining("no se modifica");
    assertThatThrownBy(() -> jdbc.sql("UPDATE approval_request SET status = 'REJECTED'").update())
        .hasMessageContaining("decidida");
  }

  @Test
  void rejectingAGateFailsItWithTheCommentAndCancelsTheRest() {
    String definitionId = publish(GATE);
    Runner runner = register("laptop");
    String runId = launch(definitionId, Map.of()).path("id").asString();
    complete(runner, runner.claim().getFirst(), "/w/eng/plan", Map.of("summary", "Hacer X"));
    String approvalId = get("/api/approvals?status=PENDING").get(0).path("id").asString();
    String decisions = "/api/approvals/" + approvalId + "/decisions";

    assertBadRequest(
        () -> post(decisions, Map.of("action", "REJECT")),
        "Para rechazar hace falta un comentario");
    assertBadRequest(() -> post(decisions, Map.of("action", "MERGE")), "Acción desconocida");

    JsonNode rejected = post(decisions, Map.of("action", "REJECT", "comment", "No es lo que pedí"));

    assertThat(rejected.path("status").asString()).isEqualTo("REJECTED");
    assertThat(get("/api/workflow-runs/" + runId).path("status").asString()).isEqualTo("FAILED");
    assertThat(stageStatuses(runId))
        .containsEntry("aprobar-plan", "FAILED")
        .containsEntry("implement", "CANCELLED")
        .containsEntry("tests", "CANCELLED");
    assertThat(runner.claim()).isEmpty();
    assertThat(eventTypes(runId)).contains("approval.rejected");
    assertThat(get("/api/approvals?status=REJECTED")).hasSize(1);
    assertConflict(() -> post(decisions, Map.of("action", "APPROVE")));
  }

  @Test
  void cancellingARunCancelsItsPendingApproval() {
    String definitionId = publish(GATE);
    Runner runner = register("laptop");
    String runId = launch(definitionId, Map.of()).path("id").asString();
    complete(runner, runner.claim().getFirst(), "/w/eng/plan", Map.of("summary", "Hacer X"));
    String approvalId = get("/api/approvals").get(0).path("id").asString();

    post("/api/workflow-runs/" + runId + "/cancel", Map.of());

    JsonNode approval = get("/api/approvals/" + approvalId);
    assertThat(approval.path("status").asString()).isEqualTo("CANCELLED");
    assertThat(approval.path("decisions").get(0).path("action").asString()).isEqualTo("CANCEL");
    assertThat(approval.path("decisions").get(0).path("actor").asString()).isEqualTo("skynet");
    assertThat(get("/api/workflow-runs/" + runId).path("status").asString()).isEqualTo("CANCELLED");
    assertThat(eventTypes(runId)).contains("approval.cancelled");
    assertConflict(
        () -> post("/api/approvals/" + approvalId + "/decisions", Map.of("action", "APPROVE")));
  }

  @Test
  void requestingChangesResumesThePlanSessionAndTheGateAsksAgainWithTheCorrection() {
    String definitionId = publish(GATE);
    Runner runner = register("laptop");
    String runId = launch(definitionId, Map.of()).path("id").asString();
    JsonNode plan = runner.claim().getFirst();
    String session = UUID.randomUUID().toString();
    complete(runner, plan, "/w/eng/plan", Map.of("summary", "Hacer X"), session);
    String first = get("/api/approvals?status=PENDING").get(0).path("id").asString();
    String decisions = "/api/approvals/" + first + "/decisions";

    assertBadRequest(
        () -> post(decisions, Map.of("action", "REQUEST_CHANGES")),
        "Para pedir cambios hace falta un comentario");
    JsonNode requested =
        post(decisions, Map.of("action", "REQUEST_CHANGES", "comment", "Añade los tests de X"));

    // La puerta queda «Corrigiendo» y plan reanuda su sesión con el comentario, en su worktree.
    assertThat(requested.path("status").asString()).isEqualTo("CHANGES_REQUESTED");
    assertThat(requested.path("decisions").get(0).path("action").asString())
        .isEqualTo("REQUEST_CHANGES");
    assertThat(stageStatuses(runId))
        .containsEntry("plan", "SUCCEEDED")
        .containsEntry("aprobar-plan", "RUNNING")
        .containsEntry("implement", "PENDING");
    assertConflict(() -> post(decisions, Map.of("action", "APPROVE")));
    JsonNode correction = runner.claim().getFirst();
    assertThat(correction.path("agentRunId").asString())
        .isEqualTo(requested.path("correctionAgentRunId").asString());
    JsonNode start = correction.path("start");
    assertThat(start.path("workflowRunId").asString()).isEqualTo(runId);
    assertThat(start.path("resume").path("sessionId").asString()).isEqualTo(session);
    assertThat(start.path("resume").path("fork").asBoolean()).isFalse();
    assertThat(start.path("resume").path("workspacePath").asString()).isEqualTo("/w/eng/plan");
    assertThat(start.path("prompt").asString())
        .contains("Aprobar el plan de ENG-1: Hacer X")
        .endsWith("Añade los tests de X");
    assertThat(start.path("outputSchema").asString()).contains("summary");
    assertThat(agentsOf(runId, "plan")).isEqualTo(2);
    assertThat(get("/api/approvals?status=PENDING")).isEmpty();

    complete(runner, correction, "/w/eng/plan", Map.of("summary", "Hacer X con tests"), session);

    // Segunda ronda, con lo corregido y lo que se pidió.
    assertThat(stageStatuses(runId)).containsEntry("aprobar-plan", "WAITING_FOR_APPROVAL");
    JsonNode pending = get("/api/approvals?status=PENDING");
    assertThat(pending).hasSize(1);
    String second = pending.get(0).path("id").asString();
    assertThat(second).isNotEqualTo(first);
    assertThat(pending.get(0).path("round").asInt()).isEqualTo(2);
    assertThat(pending.get(0).path("action").asString())
        .isEqualTo("Aprobar el plan de ENG-1: Hacer X con tests");
    JsonNode evidence = get("/api/approvals/" + second).path("evidence");
    JsonNode planAgent = evidence.path("dependencies").get(0).path("agents").get(0);
    assertThat(planAgent.path("agentRunId").asString())
        .isEqualTo(correction.path("agentRunId").asString());
    assertThat(planAgent.path("structuredOutput").path("summary").asString())
        .isEqualTo("Hacer X con tests");
    assertThat(evidence.path("revise").path("agentRunId").asString())
        .isEqualTo(correction.path("agentRunId").asString());
    assertThat(evidence.path("changesRequested").path("comment").asString())
        .isEqualTo("Añade los tests de X");
    assertThat(evidence.path("changesRequested").path("round").asInt()).isOne();
    assertThat(
            get("/api/workflow-runs/" + runId)
                .path("stages")
                .get(1)
                .path("approval")
                .path("round")
                .asInt())
        .isEqualTo(2);
    assertThat(get("/api/approvals/" + first).path("status").asString())
        .isEqualTo("CHANGES_REQUESTED");

    // Al aprobar, implement lee la salida corregida y sigue en el mismo worktree.
    post("/api/approvals/" + second + "/decisions", Map.of("action", "APPROVE"));
    JsonNode implement = runner.claim().getFirst();
    assertThat(implement.path("start").path("prompt").asString())
        .isEqualTo("Implementa: Hacer X con tests");
    assertThat(implement.path("start").path("workspace").path("path").asString())
        .isEqualTo("/w/eng/plan");
    assertThat(eventTypes(runId))
        .containsSubsequence(
            "approval.requested",
            "approval.changes_requested",
            "approval.requested",
            "approval.granted");
  }

  @Test
  void aCorrectionThatFailsFailsTheGateWithItsReason() {
    String definitionId = publish(GATE);
    Runner runner = register("laptop");
    String runId = launch(definitionId, Map.of()).path("id").asString();
    complete(runner, runner.claim().getFirst(), "/w/eng/plan", Map.of("summary", "Hacer X"));
    String approvalId = get("/api/approvals?status=PENDING").get(0).path("id").asString();
    post(
        "/api/approvals/" + approvalId + "/decisions",
        Map.of("action", "REQUEST_CHANGES", "comment", "Otra cosa"));
    JsonNode correction = runner.claim().getFirst();

    Events events = new Events(UUID.fromString(correction.path("agentRunId").asString()));
    events.add(AgentEventType.SESSION_STARTED, Map.of("sessionId", "c"));
    events.add(AgentEventType.PROCESS_EXITED, Map.of("exitCode", 1));
    runner.send(events);

    assertThat(stageStatuses(runId))
        .containsEntry("plan", "SUCCEEDED")
        .containsEntry("aprobar-plan", "FAILED")
        .containsEntry("implement", "CANCELLED");
    assertThat(get("/api/workflow-runs/" + runId).path("status").asString()).isEqualTo("FAILED");
    assertThat(eventTypes(runId)).contains("approval.correction.failed");
    assertThat(get("/api/approvals?status=PENDING")).isEmpty();
  }

  @Test
  void aCorrectionRepeatsTheChecksOfItsStageAndFailsTheGateIfTheyDoNotPass() {
    String definitionId = publish(VERIFIED_GATE);
    Runner runner = register("laptop");
    String runId = launch(definitionId, Map.of()).path("id").asString();
    complete(runner, runner.claim().getFirst(), "/w/eng/spec");
    verified(runner, runner.claim().getFirst(), true);
    String approvalId = get("/api/approvals?status=PENDING").get(0).path("id").asString();
    post(
        "/api/approvals/" + approvalId + "/decisions",
        Map.of("action", "REQUEST_CHANGES", "comment", "Falta el apartado de riesgos"));
    complete(runner, runner.claim().getFirst(), "/w/eng/spec");

    // La puerta sigue corrigiendo mientras se comprueba otra vez el worktree, desde el mismo
    // commit.
    assertThat(stageStatuses(runId)).containsEntry("aprobar-spec", "RUNNING");
    JsonNode verify = runner.claim().getFirst();
    assertThat(verify.path("type").asString()).isEqualTo("VERIFY");
    assertThat(verify.path("verify").path("baseCommit").asString()).isEqualTo("abc");
    verified(runner, verify, false);

    assertThat(stageStatuses(runId))
        .containsEntry("spec", "SUCCEEDED")
        .containsEntry("aprobar-spec", "FAILED")
        .containsEntry("plan", "CANCELLED");
    assertThat(eventTypes(runId))
        .containsSubsequence("stage.verification.completed", "approval.correction.failed");
  }

  @Test
  void aCorrectionThatPassesItsChecksAsksAgain() {
    String definitionId = publish(VERIFIED_GATE);
    Runner runner = register("laptop");
    String runId = launch(definitionId, Map.of()).path("id").asString();
    complete(runner, runner.claim().getFirst(), "/w/eng/spec");
    verified(runner, runner.claim().getFirst(), true);
    String approvalId = get("/api/approvals?status=PENDING").get(0).path("id").asString();
    post(
        "/api/approvals/" + approvalId + "/decisions",
        Map.of("action", "REQUEST_CHANGES", "comment", "Falta el apartado de riesgos"));
    complete(runner, runner.claim().getFirst(), "/w/eng/spec");
    verified(runner, runner.claim().getFirst(), true);

    assertThat(stageStatuses(runId)).containsEntry("aprobar-spec", "WAITING_FOR_APPROVAL");
    assertThat(get("/api/approvals?status=PENDING").get(0).path("round").asInt()).isEqualTo(2);
  }

  @Test
  void cancellingARunWhileCorrectingCancelsTheCorrectionAndTheGate() {
    String definitionId = publish(GATE);
    Runner runner = register("laptop");
    String runId = launch(definitionId, Map.of()).path("id").asString();
    complete(runner, runner.claim().getFirst(), "/w/eng/plan", Map.of("summary", "Hacer X"));
    String approvalId = get("/api/approvals?status=PENDING").get(0).path("id").asString();
    JsonNode requested =
        post(
            "/api/approvals/" + approvalId + "/decisions",
            Map.of("action", "REQUEST_CHANGES", "comment", "Otra cosa"));

    post("/api/workflow-runs/" + runId + "/cancel", Map.of());

    assertThat(get("/api/workflow-runs/" + runId).path("status").asString()).isEqualTo("CANCELLED");
    assertThat(stageStatuses(runId))
        .containsEntry("plan", "SUCCEEDED")
        .containsEntry("aprobar-plan", "CANCELLED");
    String correction = requested.path("correctionAgentRunId").asString();
    assertThat(
            jdbc.sql("SELECT status FROM agent_run WHERE id = ?::uuid")
                .param(correction)
                .query(String.class)
                .single())
        .isEqualTo("CANCELLED");
    assertThat(runner.claim()).isEmpty();
  }

  @Test
  void aGateWithoutAnAgentToReviseCannotRequestChanges() {
    String definitionId =
        publish(
            """
            id: sin-revise
            version: 1
            stages:
              - id: build
                type: command
                command: make
              - id: aprobar
                type: human-approval
                dependsOn: [build]
                action: Aprobar el build
            """);
    Runner runner = register("laptop");
    String runId = launch(definitionId, Map.of()).path("id").asString();
    complete(runner, runner.claim().getFirst(), "/w/eng/build");
    String approvalId = get("/api/approvals?status=PENDING").get(0).path("id").asString();
    JsonNode evidence = get("/api/approvals/" + approvalId).path("evidence");
    assertThat(evidence.path("revise").isNull()).isTrue();
    assertThat(evidence.path("consequences").path("requestChanges").isNull()).isTrue();

    assertConflict(
        () ->
            post(
                "/api/approvals/" + approvalId + "/decisions",
                Map.of("action", "REQUEST_CHANGES", "comment", "Otra cosa")));
    assertThat(stageStatuses(runId)).containsEntry("aprobar", "WAITING_FOR_APPROVAL");
    assertThat(get("/api/approvals/" + approvalId).path("status").asString()).isEqualTo("PENDING");
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
    complete(runner, command, worktree, null);
  }

  /** Igual, devolviendo {@code output} como salida estructurada. */
  private void complete(
      Runner runner, JsonNode command, String worktree, Map<String, Object> output) {
    complete(runner, command, worktree, output, UUID.randomUUID().toString());
  }

  /** Igual, con la sesión que confirma el proveedor. */
  private void complete(
      Runner runner,
      JsonNode command,
      String worktree,
      Map<String, Object> output,
      String session) {
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
    events.add(AgentEventType.SESSION_STARTED, Map.of("sessionId", session));
    Map<String, Object> result =
        new LinkedHashMap<>(Map.of("subtype", "success", "isError", false));
    if (output != null) {
      result.put("structuredOutput", output);
    }
    events.add(AgentEventType.RESULT, result);
    events.add(AgentEventType.PROCESS_EXITED, Map.of("exitCode", 0));
    runner.send(events);
  }

  /** El runner termina la verificación {@code verify}: pasan todas sus comprobaciones o ninguna. */
  private void verified(Runner runner, JsonNode verify, boolean passed) {
    Events events = new Events(UUID.fromString(verify.path("agentRunId").asString()));
    String id = verify.path("verify").path("verificationRunId").asString();
    events.add(AgentEventType.VERIFICATION_STARTED, Map.of("verificationRunId", id));
    Map<String, Object> result = new LinkedHashMap<>(Map.of("verificationRunId", id));
    if (!verify.path("verify").path("command").isNull()) {
      result.put("exitCode", passed ? 0 : 1);
    }
    List<Map<String, Object>> checks = new ArrayList<>();
    for (JsonNode check : verify.path("verify").path("checks")) {
      String name = check.path("name").asString();
      String reason =
          name.equals("artifacts")
              ? "`" + check.path("paths").get(0).asString() + "` no existe"
              : "no pasa";
      checks.add(
          passed
              ? Map.of("name", name, "passed", true)
              : Map.of("name", name, "passed", false, "reason", reason));
    }
    if (!checks.isEmpty()) {
      result.put("checks", checks);
    }
    events.add(AgentEventType.VERIFICATION_COMPLETED, result);
    runner.send(events);
  }

  /** Los intentos de la fase {@code stageKey}, del primero al último. */
  private static List<JsonNode> stagesOf(JsonNode run, String stageKey) {
    List<JsonNode> attempts = new ArrayList<>();
    run.path("stages")
        .forEach(
            s -> {
              if (s.path("stageKey").asString().equals(stageKey)) {
                attempts.add(s);
              }
            });
    return attempts;
  }

  /** Espera a que el runner reciba una orden (un reintento, pasada su espera). */
  private static JsonNode awaitCommand(Runner runner) {
    List<JsonNode> claimed = new ArrayList<>();
    await(
        () -> {
          claimed.addAll(runner.claim());
          return !claimed.isEmpty();
        });
    assertThat(claimed).hasSize(1);
    return claimed.getFirst();
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
    return register(name, null);
  }

  /** Runner con esos proveedores ({@code null}: solo Claude Code). */
  private Runner register(String name, List<ProviderInfo> providers) {
    JsonNode registered =
        http.post()
            .uri("/api/runner/register")
            .body(
                new RunnerRegistration(
                    name, RUNNER_REGISTRATION_TOKEN, "dev", 4, "2.1.288", providers))
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
