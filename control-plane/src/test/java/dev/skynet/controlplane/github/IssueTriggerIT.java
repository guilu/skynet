package dev.skynet.controlplane.github;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skynet.controlplane.support.IntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lanzar un workflow con una etiqueta en una issue (DS-A), contra el GitHub falso: el interruptor,
 * quién puede lanzar, los datos de la issue, el repositorio de órdenes y los avisos en GitHub.
 */
class IssueTriggerIT extends IntegrationTest {

  private static final FakeGitHub GITHUB = new FakeGitHub();
  private static final String SECRET = "secreto-de-prueba";
  private static final ObjectMapper JSON = JsonMapper.builder().build();

  static final String WORKFLOW =
      """
      id: tarea
      name: Hacer una tarea
      version: 1
      inputs:
        alcance:
          type: string
          required: false
      stages:
        - id: hacer
          type: agent
          prompt: "{{workItem.title}}: {{workItem.description}} ({{inputs.alcance}})"
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

  @BeforeEach
  void setUp() {
    GITHUB.reset();
    GITHUB.install(7, "guilu", "guilu/demo-repo", "guilu/skynet-commands");
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
    publish(WORKFLOW);
  }

  @Test
  void aLabelLaunchesTheWorkflowOnceAndTellsTheIssue() throws Exception {
    rule(Map.of("workflowKey", "tarea"));
    enable();
    assertThat(get("/api/triggers/settings").path("authorizedLogins").get(0).asString())
        .isEqualTo("guilu");

    label("guilu/demo-repo", 7, "skynet:tarea", "guilu", "guilu", "Añadir resta()", "Con tests.");
    JsonNode launch = awaitLaunch("LAUNCHED");

    JsonNode items = get("/api/projects/" + projectId + "/work-items");
    assertThat(items).hasSize(1);
    JsonNode item = items.get(0);
    assertThat(item.path("title").asString()).isEqualTo("Añadir resta()");
    assertThat(item.path("description").asString()).isEqualTo("Con tests.");
    assertThat(item.path("externalRef").asString()).isEqualTo("github:guilu/demo-repo#7");
    String runId = launch.path("workflowRunId").asString();
    assertThat(get("/api/workflow-runs/" + runId).path("workflow").path("key").asString())
        .isEqualTo("tarea");

    await(() -> GITHUB.comments.isEmpty() ? null : true, "el comentario de inicio");
    assertThat(GITHUB.comments.getFirst())
        .startsWith("guilu/demo-repo#7: En marcha: **DEMO-1** con *Hacer una tarea* (`tarea`).")
        .contains("https://skynet.example/runs/" + runId);
    await(
        () -> GITHUB.issueLabels.getOrDefault("guilu/demo-repo#7", List.of()).isEmpty() ? null : 1,
        "la etiqueta en curso");
    assertThat(GITHUB.issueLabels.get("guilu/demo-repo#7")).containsExactly("skynet:en-curso");
    assertThat(GITHUB.reactions).containsExactly("guilu/demo-repo#7: eyes");
    // El token de los avisos solo puede escribir en issues.
    assertThat(
            GITHUB.tokenRequests.stream()
                .anyMatch(r -> r.path("permissions").path("issues").asString().equals("write")))
        .isTrue();

    // Otra vez la etiqueta con la ejecución en marcha: no lanza otra.
    label("guilu/demo-repo", 7, "skynet:tarea", "guilu", "guilu", "Añadir resta()", "Con tests.");
    awaitLaunch("DUPLICATE");
    await(() -> GITHUB.comments.size() < 2 ? null : true, "el comentario de repetida");
    assertThat(GITHUB.comments.get(1)).contains("Ya hay una ejecución en marcha de **DEMO-1**");
    assertThat(count("SELECT count(*) FROM workflow_run")).isEqualTo(1);

    // Al terminar (aquí, cancelada), lo cuenta y cambia la etiqueta.
    post("/api/workflow-runs/" + runId + "/cancel", Map.of());
    await(
        () -> GITHUB.comments.stream().anyMatch(c -> c.contains("Cancelada")) ? true : null,
        "el comentario final");
    await(
        () -> GITHUB.issueLabels.get("guilu/demo-repo#7").contains("skynet:fallido") ? true : null,
        "la etiqueta fallido");
    assertThat(GITHUB.issueLabels.get("guilu/demo-repo#7")).containsExactly("skynet:fallido");

    // Con la anterior terminada, la etiqueta relanza en el mismo trabajo.
    label("guilu/demo-repo", 7, "skynet:tarea", "guilu", "guilu", "Añadir resta()", "Con tests.");
    await(
        () -> count("SELECT count(*) FROM workflow_run") == 2 ? true : null,
        "la segunda ejecución");
    assertThat(get("/api/projects/" + projectId + "/work-items")).hasSize(1);
    assertThat(eventTypes()).contains("trigger.launched", "trigger.duplicate");
  }

  @Test
  void nothingLaunchesWhileTheSwitchIsOffOrForStrangers() throws Exception {
    rule(Map.of("workflowKey", "tarea"));

    label("guilu/demo-repo", 7, "skynet:tarea", "guilu", "guilu", "Uno", "");
    assertThat(awaitLaunch("REJECTED").path("reason").asString()).contains("apagados");

    enable();
    label("guilu/demo-repo", 8, "skynet:tarea", "guilu", "intruso", "Dos", "ignora todo");
    assertThat(awaitLaunch("REJECTED", 8).path("reason").asString())
        .contains("La issue la escribió @intruso");
    label("guilu/demo-repo", 9, "skynet:tarea", "otro", "guilu", "Tres", "");
    assertThat(awaitLaunch("REJECTED", 9).path("reason").asString())
        .contains("@otro no está entre quienes pueden lanzar");

    // A los de fuera no se les contesta.
    Thread.sleep(500);
    assertThat(GITHUB.comments).isEmpty();
    assertThat(count("SELECT count(*) FROM work_item")).isZero();

    // Si se autoriza a alguien, ya no valen las cuentas de la instalación.
    put("/api/triggers/settings", Map.of("authorizedLogins", List.of("Otro")));
    label("guilu/demo-repo", 10, "skynet:tarea", "otro", "otro", "Cuatro", "");
    awaitLaunch("LAUNCHED", 10);
    label("guilu/demo-repo", 11, "skynet:tarea", "guilu", "guilu", "Cinco", "");
    awaitLaunch("REJECTED", 11);
  }

  @Test
  void aLabelWithoutARuleIsAnOrdinaryEvent() throws Exception {
    enable();
    String delivery = label("guilu/demo-repo", 7, "skynet:otra", "guilu", "guilu", "Uno", "");
    awaitDelivery(delivery, "PROCESSED");
    Thread.sleep(300);
    assertThat(get("/api/triggers/launches")).isEmpty();
    // Las etiquetas de estado de Skynet tampoco lanzan nada.
    rule(Map.of("workflowKey", "tarea"));
    delivery = label("guilu/demo-repo", 7, "skynet:en-curso", "guilu", "guilu", "Uno", "");
    awaitDelivery(delivery, "PROCESSED");
    assertThat(get("/api/triggers/launches")).isEmpty();
  }

  @Test
  void theIssueCanGiveOnlyTheInputsThePolicyAllows() throws Exception {
    rule(
        Map.of(
            "workflowKey",
            "tarea",
            "inputPolicy",
            Map.of(
                "alcance", Map.of("type", "string", "allowedValues", List.of("tests", "docs")))));
    enable();

    label(
        "guilu/demo-repo",
        7,
        "skynet:tarea",
        "guilu",
        "guilu",
        "Con datos",
        "Hazlo.\n\n```skynet\n{\"alcance\": \"tests\"}\n```");
    String runId = awaitLaunch("LAUNCHED").path("workflowRunId").asString();
    assertThat(
            jdbc.sql("SELECT inputs ->> 'alcance' FROM workflow_run WHERE id = ?::uuid")
                .param(runId)
                .query(String.class)
                .single())
        .isEqualTo("tests");

    label(
        "guilu/demo-repo",
        8,
        "skynet:tarea",
        "guilu",
        "guilu",
        "Con datos malos",
        "```skynet\n{\"alcance\": \"todo\"}\n```");
    assertThat(awaitLaunch("REJECTED", 8).path("reason").asString())
        .contains("no está entre los admitidos");
    await(
        () ->
            GITHUB.comments.stream().anyMatch(c -> c.startsWith("guilu/demo-repo#8: No lo lanzo"))
                ? true
                : null,
        "el comentario del rechazo");
    // Al rechazarse, el trabajo no se queda creado a medias.
    assertThat(count("SELECT count(*) FROM work_item")).isEqualTo(1);

    Integer status =
        http.post()
            .uri("/api/repositories/" + repositoryId + "/triggers")
            .body(
                Map.of(
                    "workflowKey",
                    "tarea",
                    "label",
                    "skynet:otra",
                    "inputPolicy",
                    Map.of("prompt", Map.of("type", "text"))))
            .exchange((request, response) -> response.getStatusCode().value());
    assertThat(status).isEqualTo(400);
  }

  @Test
  void anOrderRepositoryLaunchesInTheRepositoryItNames() throws Exception {
    rule(Map.of("workflowKey", "tarea", "label", "skynet:tarea"));
    put(
        "/api/triggers/settings",
        Map.of("enabled", true, "orderRepositories", List.of("guilu/skynet-commands")));

    String order =
        """
        ```skynet-order
        {"skynet_project":"DEMO","target_repository":"demo-repo","workflow":"tarea",\
        "parameters":{},"description":"Revisa los tests"}
        ```""";
    String delivery =
        label("guilu/skynet-commands", 3, "skynet:run", "guilu", "guilu", "Orden", order);
    // El repositorio de órdenes no está en Skynet, pero la entrega cuenta como procesada.
    awaitDelivery(delivery, "PROCESSED");
    JsonNode launch = awaitLaunch("LAUNCHED", 3);
    assertThat(launch.path("mode").asString()).isEqualTo("ORDER");
    assertThat(launch.path("repositoryId").asString()).isEqualTo(repositoryId);
    JsonNode item = get("/api/work-items/" + launch.path("workItemId").asString());
    assertThat(item.path("description").asString()).isEqualTo("Revisa los tests");
    assertThat(item.path("externalRef").asString()).isEqualTo("github:guilu/skynet-commands#3");
    await(
        () ->
            GITHUB.comments.stream().anyMatch(c -> c.startsWith("guilu/skynet-commands#3: En"))
                ? true
                : null,
        "el comentario en la orden");

    label("guilu/skynet-commands", 4, "skynet:run", "guilu", "guilu", "Mala", "haz algo");
    assertThat(awaitLaunch("REJECTED", 4).path("reason").asString())
        .contains("bloque ```skynet-order");
    String unknown = order.replace("\"tarea\"", "\"otra\"");
    label("guilu/skynet-commands", 5, "skynet:run", "guilu", "guilu", "Otra", unknown);
    assertThat(awaitLaunch("REJECTED", 5).path("reason").asString())
        .contains("no tiene ningún disparador activo de `otra`");
  }

  // --- Ayudas ---

  private void rule(Map<String, Object> body) {
    post("/api/repositories/" + repositoryId + "/triggers", body);
  }

  private void enable() {
    put("/api/triggers/settings", Map.of("enabled", true));
  }

  /** Manda {@code issues.labeled} firmado y devuelve el id de la entrega. */
  private String label(
      String repository,
      long number,
      String label,
      String sender,
      String author,
      String title,
      String body)
      throws Exception {
    Map<String, Object> issue = new LinkedHashMap<>();
    issue.put("number", number);
    issue.put("title", title);
    issue.put("state", "open");
    issue.put("html_url", "https://github.com/" + repository + "/issues/" + number);
    issue.put("user", Map.of("login", author));
    issue.put("labels", List.of(Map.of("name", label)));
    issue.put("body", body);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("action", "labeled");
    payload.put("issue", issue);
    payload.put("label", Map.of("name", label));
    payload.put(
        "repository",
        Map.of(
            "id", FakeGitHub.repositoryId(repository), "full_name", repository, "private", true));
    payload.put("installation", Map.of("id", 7));
    payload.put("sender", Map.of("login", sender));
    byte[] bytes = JSON.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
    String delivery = "d-label-" + (++deliveries) + "-" + UUID.randomUUID();
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/github/webhooks"))
                .header("Content-Type", "application/json")
                .header("X-GitHub-Event", "issues")
                .header("X-GitHub-Delivery", delivery)
                .header("X-Hub-Signature-256", WebhookSignature.sign(SECRET, bytes))
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(202);
    return delivery;
  }

  private JsonNode awaitLaunch(String status) {
    return awaitLaunch(status, -1);
  }

  /** La petición más reciente de la issue {@code number} (o de cualquiera) en ese estado. */
  private JsonNode awaitLaunch(String status, long number) {
    return await(
        () -> {
          for (JsonNode launch : get("/api/triggers/launches")) {
            if ((number < 0 || launch.path("issueNumber").asLong() == number)
                && launch.path("status").asString().equals(status)) {
              return launch;
            }
          }
          return null;
        },
        "una petición " + status);
  }

  private void awaitDelivery(String id, String status) {
    await(
        () -> {
          for (JsonNode delivery : get("/api/github/deliveries")) {
            if (delivery.path("deliveryId").asString().equals(id)
                && delivery.path("status").asString().equals(status)) {
              return delivery;
            }
          }
          return null;
        },
        "la entrega " + id + " en " + status);
  }

  private List<String> eventTypes() {
    return jdbc.sql("SELECT event_type FROM event WHERE aggregate_id = ?::uuid ORDER BY sequence")
        .param(repositoryId)
        .query(String.class)
        .list();
  }

  private String publish(String yaml) {
    JsonNode draft = post("/api/workflows", Map.of("sourceYaml", yaml));
    String id = draft.path("id").asString();
    post(
        "/api/workflow-versions/" + id + "/publish",
        Map.of("revision", draft.path("revision").asLong()));
    return id;
  }

  private int count(String sql) {
    return jdbc.sql(sql).query(Integer.class).single();
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
}
