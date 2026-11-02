package dev.skynet.controlplane.github;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skynet.controlplane.support.IntegrationTest;
import dev.skynet.protocol.AgentEventType;
import dev.skynet.protocol.NormalizedEvent;
import dev.skynet.protocol.runner.EventBatch;
import dev.skynet.protocol.runner.RunnerRegistration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Las órdenes {@code /skynet} en comentarios y las revisiones que piden cambios (DS-B), contra el
 * GitHub falso: corregir en la puerta, cancelar, el estado, lanzar con un comentario y los
 * rechazos.
 */
class TriggerCommandIT extends IntegrationTest {

  private static final FakeGitHub GITHUB = new FakeGitHub();
  private static final String SECRET = "secreto-de-prueba";
  private static final ObjectMapper JSON = JsonMapper.builder().build();

  /** Un plan, su puerta (que corrige el plan) y lo que sigue. */
  static final String GATE =
      """
      id: puerta
      name: Plan con puerta
      version: 1
      stages:
        - id: plan
          type: agent
          prompt: "Planifica {{workItem.title}}"
        - id: aprobar-plan
          name: Aprobar el plan
          type: human-approval
          dependsOn: [plan]
          action: "Aprobar el plan de {{workItem.key}}"
        - id: implement
          type: agent
          prompt: Implementa
          dependsOn: [aprobar-plan]
      """;

  @DynamicPropertySource
  static void github(DynamicPropertyRegistry registry) {
    registry.add("skynet.github.app-id", () -> FakeGitHub.APP_ID);
    registry.add("skynet.github.private-key-file", () -> FakeGitHub.writeKey().toString());
    registry.add("skynet.github.webhook-secret", () -> SECRET);
    registry.add("skynet.github.api-url", GITHUB::url);
    registry.add("skynet.github.web-url", () -> "https://github.com");
    registry.add("skynet.github.webhooks.poll-interval", () -> "100ms");
    registry.add("skynet.github.webhooks.redelivery-initial-delay", () -> "1h");
    registry.add("skynet.github.pull-requests.public-url", () -> "https://skynet.example");
    registry.add("skynet.triggers.poll-interval", () -> "100ms");
    registry.add("skynet.triggers.notice-interval", () -> "100ms");
  }

  @AfterAll
  static void stop() {
    GITHUB.close();
  }

  private final HttpClient client = HttpClient.newHttpClient();
  private String projectId;
  private String repositoryId;
  private int deliveries;
  private long commentIds = 1000;

  @BeforeEach
  void setUp() {
    GITHUB.reset();
    GITHUB.install(7, "guilu", "guilu/demo-repo");
    projectId = post("/api/projects", Map.of("key", "DEMO", "name", "Demo")).path("id").asString();
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
    publish(GATE);
    publish(IssueTriggerIT.WORKFLOW);
    post("/api/repositories/" + repositoryId + "/triggers", Map.of("workflowKey", "puerta"));
    put("/api/triggers/settings", Map.of("enabled", true));
  }

  @Test
  void correctingAPullRequestRequestsChangesAtTheGate() throws Exception {
    Runner runner = register();
    label(7, "skynet:puerta");
    String runId = awaitLaunch("LAUNCHED", "skynet:puerta").path("workflowRunId").asString();
    JsonNode plan = awaitClaim(runner);
    String session = UUID.randomUUID().toString();
    complete(runner, plan, "/w/demo/plan", session);
    String first =
        await(
            () -> {
              JsonNode pending = get("/api/approvals?status=PENDING");
              return pending.isEmpty() ? null : pending.get(0).path("id").asString();
            },
            "la puerta");
    pullRequest(runId, plan, 12);
    // La puerta que espera se avisa en la issue (DS-C).
    assertThat(awaitComment("guilu/demo-repo#7: Esperando tu aprobación en Skynet"))
        .contains("**Aprobar el plan** de **DEMO-1**")
        .contains("https://skynet.example/approvals/" + first);

    long comment = comment(12, true, "guilu", "/skynet corrige Añade los tests de resta()");
    JsonNode done = awaitLaunch("DONE", "/skynet corrige");
    assertThat(done.path("mode").asString()).isEqualTo("COMMAND");
    assertThat(done.path("workflowRunId").asString()).isEqualTo(runId);
    JsonNode approval = get("/api/approvals/" + first);
    assertThat(approval.path("status").asString()).isEqualTo("CHANGES_REQUESTED");
    assertThat(approval.path("decisions").get(0).path("actor").asString())
        .isEqualTo("github:guilu");
    assertThat(approval.path("decisions").get(0).path("comment").asString())
        .isEqualTo("Añade los tests de resta()");
    awaitComment("guilu/demo-repo#12: Pido los cambios en **DEMO-1**");
    await(
        () -> GITHUB.reactions.contains("guilu/demo-repo comentario " + comment + ": eyes"),
        "la reacción al comentario");

    // Mientras corrige, la puerta no espera: otra corrección no vale.
    comment(12, true, "guilu", "/skynet corrige Y la documentación");
    assertThat(awaitLaunch("REJECTED", "/skynet corrige").path("reason").asString())
        .contains("ahora no se puede corregir");
    awaitComment("guilu/demo-repo#12: No puedo: ahora no se puede corregir");

    // Terminada la corrección, la puerta vuelve a preguntar y una revisión que pide cambios
    // corrige.
    complete(runner, awaitClaim(runner), "/w/demo/plan", session);
    String second =
        await(
            () -> {
              JsonNode pending = get("/api/approvals?status=PENDING");
              return pending.isEmpty() ? null : pending.get(0).path("id").asString();
            },
            "la segunda pregunta");
    String delivery = review(12, "guilu", "Falta el caso negativo.");
    JsonNode reviewed =
        await(
            () -> {
              for (JsonNode launch : get("/api/triggers/launches")) {
                if (launch.path("deliveryId").asString().equals(delivery)
                    && !launch.path("status").asString().equals("QUEUED")) {
                  return launch;
                }
              }
              return null;
            },
            "la revisión decidida");
    assertThat(reviewed.path("status").asString()).as(reviewed.toString()).isEqualTo("DONE");
    await(
        () ->
            get("/api/approvals/" + second).path("status").asString().equals("CHANGES_REQUESTED")
                ? true
                : null,
        "los cambios de la revisión");
    assertThat(get("/api/approvals/" + second).path("decisions").get(0).path("comment").asString())
        .isEqualTo("Falta el caso negativo.");
    assertThat(eventTypes()).contains("trigger.command.corrige", "trigger.command.rejected");

    // Una revisión en una PR que no es de Skynet no es para Skynet.
    int launches = get("/api/triggers/launches").size();
    String other = review(99, "guilu", "Cambia esto");
    awaitDelivery(other);
    assertThat(get("/api/triggers/launches")).hasSize(launches);
  }

  @Test
  void statusCancelAndTheRejectionsOnAnIssue() throws Exception {
    label(7, "skynet:puerta");
    String runId = awaitLaunch("LAUNCHED", "skynet:puerta").path("workflowRunId").asString();

    comment(7, false, "guilu", "/skynet estado");
    awaitLaunch("DONE", "/skynet estado");
    String status = awaitComment("guilu/demo-repo#7: **DEMO-1** con *Plan con puerta*");
    assertThat(status).contains("- plan: ").contains("https://skynet.example/runs/" + runId);

    comment(7, false, "guilu", "/skynet aprueba");
    assertThat(awaitLaunch("REJECTED", "/skynet aprueba").path("reason").asString())
        .contains("no se puede aprobar ni fusionar");
    comment(7, false, "guilu", "/skynet baila");
    assertThat(awaitLaunch("REJECTED", "/skynet baila").path("reason").asString())
        .contains("no conozco la orden `baila`")
        .contains("o el id de un workflow");
    awaitComment("guilu/demo-repo#7: No puedo: no conozco la orden `baila`");

    // A quien no está autorizado no se le contesta.
    int comments = GITHUB.comments.size();
    comment(7, false, "intruso", "/skynet cancela");
    assertThat(awaitLaunch("REJECTED", "/skynet cancela").path("reason").asString())
        .contains("@intruso no está entre quienes pueden lanzar");
    Thread.sleep(400);
    assertThat(GITHUB.comments).hasSize(comments);

    comment(7, false, "guilu", "/skynet cancela");
    awaitLaunch("DONE", "/skynet cancela");
    awaitComment("guilu/demo-repo#7: Cancelando **DEMO-1**");
    await(
        () ->
            get("/api/workflow-runs/" + runId).path("status").asString().equals("CANCELLED")
                ? true
                : null,
        "la ejecución cancelada");
    // Y lo cuenta la petición que la lanzó, como al terminar.
    awaitComment("guilu/demo-repo#7: Cancelada");

    // Un comentario que no es una orden no es para Skynet.
    int launches = get("/api/triggers/launches").size();
    comment(7, false, "guilu", "Gracias, /skynet");
    awaitDelivery(lastDelivery);
    assertThat(get("/api/triggers/launches")).hasSize(launches);
  }

  @Test
  void aCommentWithAWorkflowLaunchesItWithItsInputs() throws Exception {
    post(
        "/api/repositories/" + repositoryId + "/triggers",
        Map.of(
            "workflowKey",
            "tarea",
            "inputPolicy",
            Map.of("alcance", Map.of("type", "string", "allowedValues", List.of("tests")))));

    long comment = comment(8, false, "guilu", "/skynet tarea alcance=tests\nGracias");
    JsonNode launch = awaitLaunch("LAUNCHED", "skynet:tarea");
    assertThat(launch.path("mode").asString()).isEqualTo("ISSUE");
    String runId = launch.path("workflowRunId").asString();
    assertThat(
            jdbc.sql("SELECT inputs ->> 'alcance' FROM workflow_run WHERE id = ?::uuid")
                .param(runId)
                .query(String.class)
                .single())
        .isEqualTo("tests");
    awaitComment("guilu/demo-repo#8: En marcha: **DEMO-1**");
    await(
        () -> GITHUB.reactions.contains("guilu/demo-repo comentario " + comment + ": eyes"),
        "la reacción al comentario");
    await(
        () -> GITHUB.issueLabels.getOrDefault("guilu/demo-repo#8", List.of()).isEmpty() ? null : 1,
        "la etiqueta en curso");

    comment(9, false, "guilu", "/skynet tarea alcance=docs");
    assertThat(awaitLaunch("REJECTED", "skynet:tarea").path("reason").asString())
        .contains("no está entre los admitidos");
    // Lanzar con un comentario también pide que la issue sea de un autorizado.
    comment(10, false, "guilu", "intruso", "/skynet tarea");
    assertThat(awaitLaunch("REJECTED", "skynet:tarea", 10).path("reason").asString())
        .contains("La issue la escribió @intruso");
  }

  // --- Ayudas ---

  /** La fila de la PR que habría abierto la fase github-pr de la ejecución. */
  private void pullRequest(String runId, JsonNode agent, int number) {
    Timestamp now = Timestamp.from(Instant.now());
    jdbc.sql(
            "INSERT INTO github_pull_request (id, workflow_run_id, repository_id, stage_run_id,"
                + " agent_run_id, full_name, branch, title, body, status, number, url,"
                + " created_at, updated_at) SELECT ?, ?::uuid, ?::uuid, stage_run_id, id,"
                + " 'guilu/demo-repo', 'skynet/demo-1', 'DEMO-1', '', 'OPEN', ?, ?, ?, ?"
                + " FROM agent_run WHERE id = ?::uuid")
        .params(
            UUID.randomUUID(),
            runId,
            repositoryId,
            number,
            "https://github.com/guilu/demo-repo/pull/" + number,
            now,
            now,
            agent.path("agentRunId").asString())
        .update();
  }

  private String label(long number, String label) throws Exception {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("action", "labeled");
    payload.put("issue", issue(number, "guilu", false));
    payload.put("label", Map.of("name", label));
    payload.put("sender", Map.of("login", "guilu"));
    return send("issues", payload);
  }

  private long comment(long number, boolean pullRequest, String sender, String text)
      throws Exception {
    return comment(number, pullRequest, sender, "guilu", text);
  }

  /** Manda {@code issue_comment.created} firmado y devuelve el id del comentario. */
  private long comment(long number, boolean pullRequest, String sender, String author, String text)
      throws Exception {
    long id = ++commentIds;
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("action", "created");
    payload.put("issue", issue(number, author, pullRequest));
    payload.put("comment", Map.of("id", id, "body", text, "user", Map.of("login", sender)));
    payload.put("sender", Map.of("login", sender));
    lastDelivery = send("issue_comment", payload);
    return id;
  }

  /** La última entrega de un comentario. */
  private String lastDelivery;

  /** Manda una revisión que pide cambios en la PR {@code number}. */
  private String review(long number, String sender, String text) throws Exception {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("action", "submitted");
    payload.put(
        "review",
        Map.of(
            "state",
            "changes_requested",
            "body",
            text,
            "user",
            Map.of("login", sender),
            "html_url",
            "https://github.com/guilu/demo-repo/pull/" + number + "#pullrequestreview-1"));
    payload.put(
        "pull_request",
        Map.of(
            "number",
            number,
            "title",
            "DEMO-1",
            "body",
            "",
            "user",
            Map.of("login", "skynet-guilu[bot]"),
            "html_url",
            "https://github.com/guilu/demo-repo/pull/" + number));
    payload.put("sender", Map.of("login", sender));
    return send("pull_request_review", payload);
  }

  private static Map<String, Object> issue(long number, String author, boolean pullRequest) {
    Map<String, Object> issue = new LinkedHashMap<>();
    issue.put("number", number);
    issue.put("title", "Añadir resta()");
    issue.put("state", "open");
    issue.put(
        "html_url",
        "https://github.com/guilu/demo-repo/" + (pullRequest ? "pull/" : "issues/") + number);
    issue.put("user", Map.of("login", author));
    issue.put("body", "Con tests.");
    if (pullRequest) {
      issue.put("pull_request", Map.of("url", "https://api.github.com/x"));
    }
    return issue;
  }

  private String send(String event, Map<String, Object> payload) throws Exception {
    payload.put(
        "repository",
        Map.of(
            "id",
            FakeGitHub.repositoryId("guilu/demo-repo"),
            "full_name",
            "guilu/demo-repo",
            "private",
            true));
    payload.put("installation", Map.of("id", 7));
    byte[] bytes = JSON.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
    String delivery =
        "d-" + event.replace('_', '-') + "-" + (++deliveries) + "-" + UUID.randomUUID();
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/github/webhooks"))
                .header("Content-Type", "application/json")
                .header("X-GitHub-Event", event)
                .header("X-GitHub-Delivery", delivery)
                .header("X-Hub-Signature-256", WebhookSignature.sign(SECRET, bytes))
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(202);
    return delivery;
  }

  private JsonNode awaitLaunch(String status, String label) {
    return awaitLaunch(status, label, -1);
  }

  /** La petición más reciente con esa etiqueta (o que empieza así) en ese estado. */
  private JsonNode awaitLaunch(String status, String label, long number) {
    return await(
        () -> {
          for (JsonNode launch : get("/api/triggers/launches")) {
            if (launch.path("label").asString().startsWith(label)
                && (number < 0 || launch.path("issueNumber").asLong() == number)
                && launch.path("status").asString().equals(status)) {
              return launch;
            }
          }
          return null;
        },
        "una petición " + label + " en " + status);
  }

  private String awaitComment(String prefix) {
    return await(
        () -> GITHUB.comments.stream().filter(c -> c.startsWith(prefix)).findFirst().orElse(null),
        "el comentario «" + prefix + "»");
  }

  private String awaitDelivery(String id) {
    return await(
        () -> {
          for (JsonNode delivery : get("/api/github/deliveries")) {
            if (delivery.path("deliveryId").asString().equals(id)
                && delivery.path("status").asString().equals("PROCESSED")) {
              return id;
            }
          }
          return null;
        },
        "la entrega " + id);
  }

  private List<String> eventTypes() {
    return jdbc.sql("SELECT event_type FROM event WHERE aggregate_id = ?::uuid ORDER BY sequence")
        .param(repositoryId)
        .query(String.class)
        .list();
  }

  private void publish(String yaml) {
    JsonNode draft = post("/api/workflows", Map.of("sourceYaml", yaml));
    post(
        "/api/workflow-versions/" + draft.path("id").asString() + "/publish",
        Map.of("revision", draft.path("revision").asLong()));
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

  private static <T> T await(Supplier<T> probe, String what) {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
    while (Instant.now().isBefore(deadline)) {
      T value = probe.get();
      if (value != null && !Boolean.FALSE.equals(value)) {
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

  // --- Un runner simulado, como en WorkflowEngineIT ---

  private Runner register() {
    JsonNode registered =
        http.post()
            .uri("/api/runner/register")
            .body(
                new RunnerRegistration(
                    "laptop", RUNNER_REGISTRATION_TOKEN, "dev", 4, "2.1.288", null))
            .retrieve()
            .body(JsonNode.class);
    return new Runner(registered.path("token").asString());
  }

  private JsonNode awaitClaim(Runner runner) {
    return await(
        () -> {
          List<JsonNode> claimed = runner.claim();
          return claimed.isEmpty() ? null : claimed.getFirst();
        },
        "una orden para el runner");
  }

  /** El agente prepara su worktree y termina bien en la sesión {@code session}. */
  private void complete(Runner runner, JsonNode command, String worktree, String session) {
    UUID agentRunId = UUID.fromString(command.path("agentRunId").asString());
    List<NormalizedEvent> events = new ArrayList<>();
    add(
        events,
        agentRunId,
        AgentEventType.WORKSPACE_READY,
        Map.of("path", worktree, "branch", "skynet/demo-1", "baseCommit", "abc1234"));
    add(events, agentRunId, AgentEventType.SESSION_STARTED, Map.of("sessionId", session));
    add(events, agentRunId, AgentEventType.RESULT, Map.of("subtype", "success", "isError", false));
    add(events, agentRunId, AgentEventType.PROCESS_EXITED, Map.of("exitCode", 0));
    runner.send(new EventBatch(events));
  }

  private static void add(
      List<NormalizedEvent> events, UUID agentRunId, AgentEventType type, Map<String, ?> payload) {
    events.add(
        new NormalizedEvent(
            UUID.randomUUID(),
            agentRunId,
            events.size() + 1,
            Instant.now(),
            type,
            new LinkedHashMap<>(payload)));
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

    void send(EventBatch batch) {
      http.post()
          .uri("/api/runner/events")
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
          .body(batch)
          .retrieve()
          .toBodilessEntity();
    }
  }
}
