package dev.skynet.controlplane.chat;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skynet.controlplane.support.IntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Las acciones por confirmar del chat (CH-B): Skyny las propone con el token de la conversación, la
 * persona las confirma o las rechaza desde la web, y se hacen a nombre de «admin vía chat».
 */
class ChatActionsIT extends IntegrationTest {

  private static final String CHAIN =
      """
      id: chain
      version: 1
      stages:
        - id: build
          type: agent
          prompt: construye
      """;

  @Autowired ChatTokens tokens;

  private String project;
  private String repository;
  private String workItem;
  private String conversation;
  private RestClient skyny;

  @BeforeEach
  void setUp() {
    project = post("/api/projects", Map.of("key", "CHA", "name", "akademia")).path("id").asString();
    repository =
        post(
                "/api/projects/" + project + "/repositories",
                Map.of("name", "demo", "localPath", "/repos/cha"))
            .path("id")
            .asString();
    workItem =
        post(
                "/api/projects/" + project + "/work-items",
                Map.of("title", "Exportar notas", "type", "FEATURE", "repositoryId", repository))
            .path("id")
            .asString();
    conversation =
        post("/api/conversations", Map.of("projectId", project, "title", "Acciones"))
            .path("conversation")
            .path("id")
            .asString();
    skyny = bearer(tokens.issue(UUID.fromString(conversation)).token());
  }

  @Test
  void skynyProposesAndThePersonConfirmsALaunch() {
    publish(CHAIN);
    JsonNode proposed = propose("LAUNCH", Map.of("workItemId", workItem, "workflow", "chain"));
    assertThat(proposed.path("status").asString()).isEqualTo("PENDING");
    assertThat(proposed.path("summary").asString())
        .isEqualTo("Lanzar chain (v1) en CHA-1 «Exportar notas»");
    // Proponer no lanza nada.
    assertThat(runCount()).isZero();

    JsonNode confirmed = confirm(proposed);
    assertThat(confirmed.path("status").asString()).isEqualTo("CONFIRMED");
    assertThat(confirmed.path("decidedBy").asString()).isEqualTo("admin vía chat");
    String run = confirmed.path("result").path("id").asString();
    assertThat(confirmed.path("result").path("url").asString()).isEqualTo("/runs/" + run);
    JsonNode launched = get("/api/workflow-runs/" + run);
    assertThat(launched.path("workItemId").asString()).isEqualTo(workItem);
    assertThat(runCount()).isEqualTo(1);

    // Ya no se puede confirmar otra vez, ni rechazar.
    assertThat(status(http, HttpMethod.POST, confirmPath(proposed))).isEqualTo(409);
    assertThat(status(http, HttpMethod.POST, rejectPath(proposed))).isEqualTo(409);
    assertThat(eventTypes()).contains("chat.action.proposed", "chat.action.confirmed");
  }

  @Test
  void creatingAWorkItemCancellingARunAndSavingMemory() {
    JsonNode created =
        confirm(
            propose(
                "CREATE_WORK_ITEM",
                Map.of("projectId", project, "title", "Notas en PDF", "type", "bug")));
    assertThat(created.path("result").path("label").asString()).isEqualTo("CHA-2 «Notas en PDF»");
    assertThat(
            get("/api/work-items/" + created.path("result").path("id").asString())
                .path("type")
                .asString())
        .isEqualTo("BUG");

    String run =
        post(
                "/api/work-items/" + workItem + "/runs",
                Map.of("repositoryId", repository, "prompt", "haz algo"))
            .path("id")
            .asString();
    JsonNode cancel = propose("CANCEL_RUN", Map.of("workflowRunId", run));
    assertThat(cancel.path("summary").asString())
        .isEqualTo("Cancelar la ejecución de CHA-1 «Exportar notas»");
    confirm(cancel);
    assertThat(get("/api/workflow-runs/" + run).path("status").asString()).isEqualTo("CANCELLED");
    // Una ejecución terminada ya no se propone cancelar.
    assertThat(proposeStatus("CANCEL_RUN", Map.of("workflowRunId", run))).isEqualTo(409);

    JsonNode saved =
        confirm(
            propose(
                "SAVE_MEMORY",
                Map.of(
                    "projectId",
                    project,
                    "kind",
                    "decision",
                    "title",
                    "Autenticación con sesión",
                    "what",
                    "La web usa la sesión de Spring, no JWT")));
    String entry = saved.path("result").path("id").asString();
    JsonNode memory = get("/api/memory/" + entry);
    assertThat(memory.path("title").asString()).isEqualTo("Autenticación con sesión");
    // La escribe Skyny: entra sin confirmar, como lo que guarda un agente.
    assertThat(memory.path("trust").asString()).isEqualTo("UNCONFIRMED");
  }

  @Test
  void anApprovalIsDecidedInTheNameOfThePerson() {
    UUID approval = pendingApproval();
    // Rechazar sin motivo no se propone.
    assertThat(
            proposeStatus("DECIDE_APPROVAL", Map.of("approvalId", approval, "decision", "reject")))
        .isEqualTo(400);
    JsonNode decided =
        confirm(
            propose(
                "DECIDE_APPROVAL",
                Map.of("approvalId", approval, "decision", "reject", "comment", "Aún no")));
    assertThat(decided.path("status").asString()).isEqualTo("CONFIRMED");
    assertThat(
            jdbc.sql("SELECT actor FROM approval_decision WHERE approval_request_id = ?")
                .param(approval)
                .query(String.class)
                .single())
        .isEqualTo("admin vía chat");
  }

  @Test
  void whatTheServiceRefusesEndsFailedWithItsReason() {
    String run =
        post(
                "/api/work-items/" + workItem + "/runs",
                Map.of("repositoryId", repository, "prompt", "haz algo"))
            .path("id")
            .asString();
    String stage =
        get("/api/workflow-runs/" + run).path("stages").get(0).path("stageKey").asString();
    assertThat(proposeStatus("RETRY_STAGE", Map.of("workflowRunId", run, "stageKey", "no-existe")))
        .isEqualTo(400);
    // Reintentar una fase que no ha terminado: se propone, pero el servicio la rechaza al hacerla.
    JsonNode retry = propose("RETRY_STAGE", Map.of("workflowRunId", run, "stageKey", stage));
    JsonNode failed = confirm(retry);
    assertThat(failed.path("status").asString()).isEqualTo("FAILED");
    assertThat(failed.path("error").asString()).isNotBlank();
    assertThat(eventTypes()).contains("chat.action.failed");
  }

  @Test
  void rejectedExpiredAndLimited() {
    JsonNode rejected =
        reject(propose("CREATE_WORK_ITEM", Map.of("projectId", project, "title", "Otra")));
    assertThat(rejected.path("status").asString()).isEqualTo("REJECTED");
    assertThat(rejected.path("decidedBy").asString()).isEqualTo("admin vía chat");
    assertThat(eventTypes()).contains("chat.action.rejected");

    JsonNode old = propose("CREATE_WORK_ITEM", Map.of("projectId", project, "title", "Vieja"));
    jdbc.sql("UPDATE conversation_action SET created_at = ? WHERE id = ?")
        .params(
            Timestamp.from(Instant.now().minus(ChatActions.EXPIRES_AFTER).minusSeconds(1)),
            UUID.fromString(old.path("id").asString()))
        .update();
    assertThat(status(http, HttpMethod.POST, confirmPath(old))).isEqualTo(409);
    assertThat(statusOf(get("/api/conversations/" + conversation + "/actions"), old))
        .isEqualTo("EXPIRED");

    for (int i = 0; i < ChatActions.MAX_PENDING; i++) {
      propose("CREATE_WORK_ITEM", Map.of("projectId", project, "title", "Pendiente " + i));
    }
    assertThat(proposeStatus("CREATE_WORK_ITEM", Map.of("projectId", project, "title", "Una más")))
        .isEqualTo(409);
    // Nada de eso creó trabajos.
    assertThat(get("/api/projects/" + project + "/work-items")).hasSize(1);
  }

  @Test
  void skynyOnlyProposes() {
    JsonNode proposed = propose("CREATE_WORK_ITEM", Map.of("projectId", project, "title", "x"));
    // Con el token del chat no se confirma, ni se rechaza, ni se ve la lista de la web.
    assertThat(status(skyny, HttpMethod.POST, confirmPath(proposed))).isEqualTo(403);
    assertThat(status(skyny, HttpMethod.POST, rejectPath(proposed))).isEqualTo(403);
    assertThat(status(skyny, HttpMethod.GET, "/api/conversations/" + conversation + "/actions"))
        .isEqualTo(403);
    // Sin token, o con uno que no vale, no se propone.
    RestClient anonymous = RestClient.builder().baseUrl("http://localhost:" + port).build();
    assertThat(status(anonymous, HttpMethod.POST, "/api/runner/chat/actions")).isEqualTo(401);
    assertThat(
            status(
                bearer(ChatTokens.PREFIX + "inventado"),
                HttpMethod.POST,
                "/api/runner/chat/actions"))
        .isEqualTo(401);
    // Lo que no existe se dice al proponer, para que Skyny lo corrija.
    assertThat(proposeStatus("LAUNCH", Map.of("workItemId", workItem, "workflow", "no-existe")))
        .isIn(404, 409);
    assertThat(proposeStatus("CANCEL_RUN", Map.of("workflowRunId", "../runners"))).isEqualTo(400);
    assertThat(proposeStatus("BORRAR", Map.of())).isEqualTo(400);
    // Una conversación archivada no confirma nada.
    post("/api/conversations/" + conversation + "/archive", Map.of());
    assertThat(status(http, HttpMethod.POST, confirmPath(proposed))).isEqualTo(409);
  }

  private UUID pendingApproval() {
    String run =
        post(
                "/api/work-items/" + workItem + "/runs",
                Map.of("repositoryId", repository, "prompt", "haz algo"))
            .path("id")
            .asString();
    UUID stage =
        jdbc.sql("SELECT id FROM stage_run WHERE workflow_run_id = ? LIMIT 1")
            .param(UUID.fromString(run))
            .query(UUID.class)
            .single();
    UUID request = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO approval_request (id, workflow_run_id, stage_run_id, round, action, risk,"
                + " evidence, status, created_at) VALUES (?, ?, ?, 1, 'merge', 'LOW',"
                + " '{}'::jsonb, 'PENDING', ?)")
        .params(request, UUID.fromString(run), stage, Timestamp.from(Instant.now()))
        .update();
    return request;
  }

  private JsonNode propose(String kind, Map<String, ?> input) {
    return skyny
        .post()
        .uri("/api/runner/chat/actions")
        .body(Map.of("kind", kind, "input", input))
        .retrieve()
        .body(JsonNode.class);
  }

  private int proposeStatus(String kind, Map<String, ?> input) {
    Map<String, Object> body = new HashMap<>();
    body.put("kind", kind);
    body.put("input", input);
    return skyny
        .post()
        .uri("/api/runner/chat/actions")
        .body(body)
        .exchange((request, response) -> response.getStatusCode().value());
  }

  private JsonNode confirm(JsonNode action) {
    return post(confirmPath(action), Map.of());
  }

  private JsonNode reject(JsonNode action) {
    return post(rejectPath(action), Map.of());
  }

  private String confirmPath(JsonNode action) {
    return "/api/conversations/"
        + conversation
        + "/actions/"
        + action.path("id").asString()
        + "/confirm";
  }

  private String rejectPath(JsonNode action) {
    return "/api/conversations/"
        + conversation
        + "/actions/"
        + action.path("id").asString()
        + "/reject";
  }

  private static String statusOf(JsonNode list, JsonNode action) {
    return list.valueStream()
        .filter(a -> a.path("id").asString().equals(action.path("id").asString()))
        .findFirst()
        .orElseThrow()
        .path("status")
        .asString();
  }

  private int runCount() {
    return jdbc.sql("SELECT count(*) FROM workflow_run").query(Integer.class).single();
  }

  private List<String> eventTypes() {
    return jdbc.sql(
            "SELECT event_type FROM event WHERE aggregate_type = 'conversation'"
                + " AND aggregate_id = ? ORDER BY sequence")
        .param(UUID.fromString(conversation))
        .query(String.class)
        .list();
  }

  private void publish(String yaml) {
    JsonNode draft = post("/api/workflows", Map.of("sourceYaml", yaml));
    post(
        "/api/workflow-versions/" + draft.path("id").asString() + "/publish",
        Map.of("revision", draft.path("revision").asLong()));
  }

  private RestClient bearer(String token) {
    return RestClient.builder()
        .baseUrl("http://localhost:" + port)
        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .build();
  }

  private static int status(RestClient client, HttpMethod method, String path) {
    return client
        .method(method)
        .uri(path)
        .exchange((request, response) -> response.getStatusCode().value());
  }

  private JsonNode post(String path, Object body) {
    return http.post().uri(path).body(body).retrieve().body(JsonNode.class);
  }

  private JsonNode get(String path) {
    return http.get().uri(path).retrieve().body(JsonNode.class);
  }
}
