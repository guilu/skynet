package dev.skynet.controlplane.github;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skynet.controlplane.support.IntegrationTest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * Los webhooks de la App: firma, idempotencia, la cola con sus reintentos, los eventos {@code
 * github.*} de los repositorios enlazados, las instalaciones que cambian por webhook y la red de
 * seguridad que pide otra vez lo que no llegó.
 */
class GitHubWebhooksIT extends IntegrationTest {

  private static final FakeGitHub GITHUB = new FakeGitHub();
  private static final String SECRET = "secreto-de-prueba";
  private static final Path FIXTURES = Path.of("../fixtures/github");

  @DynamicPropertySource
  static void github(DynamicPropertyRegistry registry) {
    registry.add("skynet.github.app-id", () -> FakeGitHub.APP_ID);
    registry.add("skynet.github.private-key-file", () -> FakeGitHub.writeKey().toString());
    registry.add("skynet.github.webhook-secret", () -> SECRET);
    registry.add("skynet.github.api-url", GITHUB::url);
    registry.add("skynet.github.web-url", () -> "https://github.com");
    registry.add("skynet.github.webhooks.poll-interval", () -> "100ms");
    registry.add("skynet.github.webhooks.retry-delay", () -> "50ms");
    registry.add("skynet.github.webhooks.max-attempts", () -> "3");
    // La red de seguridad se prueba llamándola; que no salte sola en medio de otro test.
    registry.add("skynet.github.webhooks.redelivery-initial-delay", () -> "1h");
  }

  @AfterAll
  static void stop() {
    GITHUB.close();
  }

  @Autowired private WebhookRedelivery redelivery;

  private final HttpClient client = HttpClient.newHttpClient();
  private String projectId;

  @BeforeEach
  void setUp() {
    GITHUB.reset();
    projectId =
        http.post()
            .uri("/api/projects")
            .body(Map.of("key", "HOOK", "name", "Webhooks"))
            .retrieve()
            .body(JsonNode.class)
            .path("id")
            .asString();
  }

  @Test
  void aBadOrMissingSignatureIsRejectedAndNotStored() throws Exception {
    byte[] body = fixture("pull_request-opened");

    assertThat(send("pull_request", "d-bad", body, "sha256=" + "0".repeat(64)).statusCode())
        .isEqualTo(401);
    assertThat(send("pull_request", "d-none", body, null).statusCode()).isEqualTo(401);
    // Firmado con otro secreto.
    assertThat(
            send("pull_request", "d-other", body, WebhookSignature.sign("otro", body)).statusCode())
        .isEqualTo(401);

    assertThat(count("SELECT count(*) FROM github_webhook_delivery")).isZero();
  }

  @Test
  void aPullRequestIsPublishedOnceOnEachLinkedRepository() throws Exception {
    UUID https = repository("demo", "https://github.com/guilu/demo-repo.git");
    UUID ssh = repository("demo-ssh", "git@github.com:Guilu/Demo-Repo.git");
    repository("ajeno", "https://github.com/guilu/ajeno");
    byte[] body = fixture("pull_request-opened");

    assertThat(signed("pull_request", "d-pr-1", body).statusCode()).isEqualTo(202);
    // GitHub la repite: se reconoce y no se procesa otra vez.
    assertThat(signed("pull_request", "d-pr-1", body).statusCode()).isEqualTo(200);

    JsonNode delivery = awaitDelivery("d-pr-1", "PROCESSED");
    assertThat(delivery.path("event").asString()).isEqualTo("pull_request");
    assertThat(delivery.path("action").asString()).isEqualTo("opened");
    assertThat(delivery.path("repository").asString()).isEqualTo("guilu/demo-repo");
    assertThat(delivery.path("installationId").asLong()).isEqualTo(7);
    assertThat(delivery.path("attempts").asInt()).isEqualTo(1);
    assertThat(delivery.path("events").asInt()).isEqualTo(2);

    for (UUID repository : new UUID[] {https, ssh}) {
      JsonNode events = events(repository);
      assertThat(events).hasSize(1);
      JsonNode event = events.get(0);
      assertThat(event.path("type").asString()).isEqualTo("github.pull_request.opened");
      assertThat(event.path("aggregateType").asString()).isEqualTo("repository");
      JsonNode payload = event.path("payload");
      assertThat(payload.path("deliveryId").asString()).isEqualTo("d-pr-1");
      assertThat(payload.path("number").asLong()).isEqualTo(12);
      assertThat(payload.path("title").asString()).isEqualTo("Corrige add() con negativos");
      assertThat(payload.path("headRef").asString()).isEqualTo("skynet/demo-7");
      assertThat(payload.path("headSha").asString()).startsWith("4f1c2a9e");
      assertThat(payload.path("baseRef").asString()).isEqualTo("main");
      assertThat(payload.path("url").asString())
          .isEqualTo("https://github.com/guilu/demo-repo/pull/12");
      // Un resumen, no el cuerpo entero.
      assertThat(payload.has("pull_request")).isFalse();
    }
  }

  @Test
  void checkRunsAndCommentsCarryWhatEnAndDsNeedWithoutSecrets() throws Exception {
    UUID repository = repository("demo", "https://github.com/guilu/demo-repo");

    signed("check_run", "d-check", fixture("check_run-completed"));
    signed("issue_comment", "d-comment", fixture("issue_comment-created"));
    awaitDelivery("d-check", "PROCESSED");
    awaitDelivery("d-comment", "PROCESSED");

    Map<String, JsonNode> byType = new HashMap<>();
    events(repository).forEach(e -> byType.put(e.path("type").asString(), e.path("payload")));
    JsonNode check = byType.get("github.check_run.completed");
    assertThat(check.path("name").asString()).isEqualTo("Java");
    assertThat(check.path("conclusion").asString()).isEqualTo("failure");
    assertThat(check.path("pullRequests").get(0).asLong()).isEqualTo(12);
    JsonNode comment = byType.get("github.issue_comment.created");
    assertThat(comment.path("isPullRequest").asBoolean()).isTrue();
    assertThat(comment.path("labels").get(0).asString()).isEqualTo("skynet");
    assertThat(comment.path("commentBody").asString())
        .startsWith("/skynet revisa esto")
        .doesNotContain("super-secreto-123");
  }

  @Test
  void aDeliveryForAnUnknownRepositoryIsIgnoredWithTheReason() throws Exception {
    repository("otro", "https://github.com/guilu/otro");

    signed("pull_request", "d-unknown", fixture("pull_request-opened"));

    JsonNode delivery = awaitDelivery("d-unknown", "IGNORED");
    assertThat(delivery.path("error").asString())
        .isEqualTo("Ningún repositorio de Skynet es guilu/demo-repo");
    assertThat(delivery.path("events").asInt()).isZero();
  }

  @Test
  void aPingIsStoredAsIgnored() throws Exception {
    assertThat(signed("ping", "d-ping", fixture("ping")).statusCode()).isEqualTo(202);
    JsonNode delivery = deliveries().get(0);
    assertThat(delivery.path("status").asString()).isEqualTo("IGNORED");
    assertThat(delivery.path("error").asString()).startsWith("ping");
  }

  @Test
  void installationWebhooksKeepTheInstallationsUpToDate() throws Exception {
    UUID otro = repository("otro", "https://github.com/guilu/otro");

    signed("installation", "d-inst", fixture("installation-created"));
    awaitDelivery("d-inst", "PROCESSED");
    JsonNode status = http.get().uri("/api/github").retrieve().body(JsonNode.class);
    assertThat(status.path("installations").get(0).path("id").asLong()).isEqualTo(9);
    assertThat(
            status.path("installations").get(0).path("repositories").findValuesAsString("fullName"))
        .containsExactly("guilu/demo-repo", "guilu/otro");
    assertThat(link(status, otro).path("access").asString()).isEqualTo("CONNECTED");

    signed("installation_repositories", "d-removed", fixture("installation_repositories-removed"));
    awaitDelivery("d-removed", "PROCESSED");
    status = http.get().uri("/api/github").retrieve().body(JsonNode.class);
    assertThat(
            status.path("installations").get(0).path("repositories").findValuesAsString("fullName"))
        .containsExactly("guilu/demo-repo");
    assertThat(link(status, otro).path("access").asString()).isEqualTo("NOT_INSTALLED");

    byte[] deleted =
        new String(fixture("installation-created"), StandardCharsets.UTF_8)
            .replace("\"created\"", "\"deleted\"")
            .getBytes(StandardCharsets.UTF_8);
    signed("installation", "d-deleted", deleted);
    awaitDelivery("d-deleted", "PROCESSED");
    assertThat(count("SELECT count(*) FROM github_installation")).isZero();
    assertThat(count("SELECT count(*) FROM github_installation_repository")).isZero();
  }

  @Test
  void aDeliveryThatCannotBeProcessedIsRetriedAndThenFails() throws Exception {
    // Una instalación sin id: el procesamiento falla en cada intento.
    byte[] broken =
        "{\"action\":\"created\",\"installation\":{\"account\":{\"login\":\"x\"}}}"
            .getBytes(StandardCharsets.UTF_8);
    assertThat(signed("installation", "d-broken", broken).statusCode()).isEqualTo(202);

    JsonNode delivery = awaitDelivery("d-broken", "FAILED");
    assertThat(delivery.path("attempts").asInt()).isEqualTo(3);
    assertThat(delivery.path("error").asString()).contains("no trae el id");
  }

  @Test
  void aBodyThatIsNotJsonIsABadRequest() throws Exception {
    byte[] form = "payload=%7B%7D".getBytes(StandardCharsets.UTF_8);
    HttpResponse<String> response = signed("pull_request", "d-form", form);
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("application/json");
  }

  @Test
  void theSafetyNetAsksAgainOnlyForDeliveriesThatNeverArrived() throws Exception {
    signed("ping", "llego-a-skynet", fixture("ping"));
    // Falló una vez y luego llegó bien: no se pide.
    GITHUB.hookDelivery(100, "reintentada", 502);
    GITHUB.hookDelivery(101, "reintentada", 202);
    // GitHub dio error pero Skynet la tiene (p. ej. respondió tarde).
    GITHUB.hookDelivery(102, "llego-a-skynet", 0);
    // No llegó nunca: se pide su intento más reciente.
    GITHUB.hookDelivery(103, "perdida", 0);
    GITHUB.hookDelivery(104, "perdida", 503);

    assertThat(redelivery.redeliverMissing()).isEqualTo(1);
    assertThat(GITHUB.redelivered).containsExactly(104L);

    // En la siguiente pasada ya se pidió: no se repite.
    assertThat(redelivery.redeliverMissing()).isZero();
    assertThat(GITHUB.redelivered).containsExactly(104L);
  }

  // DL-B: los despliegues de GitHub Deployments. Van aquí, y no en su propia clase, para
  // compartir el contexto de Spring (y sus conexiones a la base de datos) con el resto de webhooks.

  private static final String DEPLOYED_SHA = "4f1c2a9e0b7d3c5e8f6a1b2c3d4e5f60718293a4";

  @Test
  void aDeploymentAndItsStatusesAreRecordedOnce() throws Exception {
    UUID repository = repository("demo", "https://github.com/guilu/demo-repo");

    assertThat(signed("deployment", "dl-1", fixture("deployment-created")).statusCode())
        .isEqualTo(202);
    awaitDelivery("dl-1", "PROCESSED");
    JsonNode created = deployments(repository).get(0);
    assertThat(created.path("externalId").asString()).isEqualTo("900001");
    assertThat(created.path("environment").asString()).isEqualTo("omarchy");
    assertThat(created.path("commitSha").asString()).isEqualTo(DEPLOYED_SHA);
    assertThat(created.path("ref").asString()).isEqualTo("skynet/demo-7");
    assertThat(created.path("pullRequestNumber").asInt()).isEqualTo(12);
    assertThat(created.path("creator").asString()).isEqualTo("guilu");
    assertThat(created.path("status").asString()).isEqualTo("PENDING");
    assertThat(created.path("requestedAt").asString()).isEqualTo("2026-11-05T18:00:00Z");

    signed("deployment_status", "dl-2", status(950001, "in_progress", "2026-11-05T18:00:05Z"));
    signed("deployment_status", "dl-3", fixture("deployment_status-created"));
    awaitDelivery("dl-3", "PROCESSED");
    awaitDelivery("dl-2", "PROCESSED");
    // GitHub repite la entrega con otro id: el estado ya está.
    signed("deployment_status", "dl-3b", fixture("deployment_status-created"));
    awaitDelivery("dl-3b", "PROCESSED");

    JsonNode deployment = deployments(repository).get(0);
    assertThat(deployments(repository)).hasSize(1);
    assertThat(deployment.path("status").asString()).isEqualTo("SUCCEEDED");
    assertThat(deployment.path("health").asString()).isEqualTo("UNKNOWN");
    assertThat(deployment.path("url").asString()).isEqualTo("http://omarchy.local:8081");
    assertThat(deployment.path("startedAt").asString()).isEqualTo("2026-11-05T18:00:05Z");
    assertThat(deployment.path("finishedAt").asString()).isEqualTo("2026-11-05T18:01:30Z");
    JsonNode detail = get("/api/deployments/" + deployment.path("id").asString());
    assertThat(detail.path("statuses")).hasSize(2);

    // El evento github.* del repositorio lleva el resumen.
    JsonNode events =
        http.get()
            .uri(
                "/api/events?aggregateId=" + repository + "&types=github.deployment_status.created")
            .retrieve()
            .body(JsonNode.class);
    assertThat(events.findValuesAsString("state")).contains("in_progress", "success");
    assertThat(events.get(0).path("payload").path("sha").asString()).isEqualTo(DEPLOYED_SHA);
  }

  @Test
  void aStatusWithoutItsDeploymentAndOutOfOrderStillEndsRight() throws Exception {
    UUID repository = repository("demo", "https://github.com/guilu/demo-repo");

    // Llega antes el final que el «en curso», y nunca el deployment.
    signed("deployment_status", "dl-a", fixture("deployment_status-created"));
    awaitDelivery("dl-a", "PROCESSED");
    signed("deployment_status", "dl-b", status(950001, "in_progress", "2026-11-05T18:00:05Z"));
    awaitDelivery("dl-b", "PROCESSED");
    signed("deployment", "dl-c", fixture("deployment-created"));
    awaitDelivery("dl-c", "PROCESSED");

    JsonNode deployment = deployments(repository).get(0);
    assertThat(deployments(repository)).hasSize(1);
    assertThat(deployment.path("status").asString()).isEqualTo("SUCCEEDED");
    assertThat(deployment.path("pullRequestNumber").asInt()).isEqualTo(12);
    JsonNode statuses =
        get("/api/deployments/" + deployment.path("id").asString()).path("statuses");
    assertThat(statuses.findValuesAsString("state")).containsExactly("IN_PROGRESS", "SUCCEEDED");
    assertThat(statuses.get(0).path("applied").asBoolean()).isFalse();
  }

  @Test
  void aFailureKeepsItsRedactedErrorAndInactiveIsIgnored() throws Exception {
    UUID repository = repository("demo", "https://github.com/guilu/demo-repo");
    byte[] failure =
        new String(fixture("deployment_status-created"), StandardCharsets.UTF_8)
            .replace("\"success\"", "\"error\"")
            .replace(
                "docker compose up -d: OK",
                "pull falló con token=ghp_abcdefghijklmnopqrstuvwxyz0123456789")
            .getBytes(StandardCharsets.UTF_8);
    signed("deployment_status", "dl-f", failure);
    awaitDelivery("dl-f", "PROCESSED");
    signed("deployment_status", "dl-i", status(950009, "inactive", "2026-11-05T19:00:00Z"));
    awaitDelivery("dl-i", "PROCESSED");

    JsonNode deployment = deployments(repository).get(0);
    assertThat(deployment.path("status").asString()).isEqualTo("FAILED");
    assertThat(deployment.path("error").asString())
        .startsWith("pull falló")
        .doesNotContain("ghp_abcdef");
    assertThat(get("/api/deployments/" + deployment.path("id").asString()).path("statuses"))
        .hasSize(1);
    String body =
        jdbc.sql("SELECT payload::text FROM event WHERE event_type LIKE 'github.deployment%'")
            .query(String.class)
            .list()
            .toString();
    assertThat(body).doesNotContain("ghp_abcdef");
  }

  @Test
  void aNewerDeploymentSupersedesTheOlderOne() throws Exception {
    UUID repository = repository("demo", "https://github.com/guilu/demo-repo");
    signed("deployment_status", "dl-old", fixture("deployment_status-created"));
    awaitDelivery("dl-old", "PROCESSED");
    String newer =
        new String(fixture("deployment_status-created"), StandardCharsets.UTF_8)
            .replace("900001", "900002")
            .replace("950002", "950102")
            .replace(DEPLOYED_SHA, "b".repeat(40))
            .replace("2026-11-05T18:00:00Z", "2026-11-05T19:00:00Z")
            .replace("2026-11-05T18:01:30Z", "2026-11-05T19:01:30Z");
    signed("deployment_status", "dl-new", newer.getBytes(StandardCharsets.UTF_8));
    awaitDelivery("dl-new", "PROCESSED");

    JsonNode environments = get("/api/repositories/" + repository + "/environments");
    assertThat(environments.get(0).path("deployed").path("commitSha").asString())
        .isEqualTo("b".repeat(40));
    assertThat(deployments(repository).findValuesAsString("status"))
        .containsExactly("SUCCEEDED", "SUPERSEDED");
  }

  @Test
  void aDeploymentOfAnUnknownRepositoryIsIgnoredAndABadSignatureRejected() throws Exception {
    repository("otro", "https://github.com/guilu/otro");
    byte[] body = fixture("deployment-created");
    assertThat(send("deployment", "dl-bad", body, "sha256=" + "0".repeat(64)).statusCode())
        .isEqualTo(401);
    signed("deployment", "dl-unknown", body);
    awaitDelivery("dl-unknown", "IGNORED");
    assertThat(jdbc.sql("SELECT count(*) FROM deployment").query(Integer.class).single()).isZero();
  }

  @Test
  void anEnvironmentNameIsMadeValid() throws Exception {
    UUID repository = repository("demo", "https://github.com/guilu/demo-repo");
    byte[] body =
        new String(fixture("deployment-created"), StandardCharsets.UTF_8)
            .replace("\"omarchy\"", "\"Omarchy (compartido)\"")
            .getBytes(StandardCharsets.UTF_8);
    signed("deployment", "dl-env", body);
    awaitDelivery("dl-env", "PROCESSED");
    assertThat(deployments(repository).get(0).path("environment").asString())
        .isEqualTo("Omarchy-compartido-");
  }

  private byte[] status(long id, String state, String at) throws IOException {
    return new String(fixture("deployment_status-created"), StandardCharsets.UTF_8)
        .replace("950002", Long.toString(id))
        .replace("\"success\"", "\"" + state + "\"")
        .replace("\"created_at\": \"2026-11-05T18:01:30Z\"", "\"created_at\": \"" + at + "\"")
        .getBytes(StandardCharsets.UTF_8);
  }

  private JsonNode deployments(UUID repository) {
    return get("/api/repositories/" + repository + "/deployments");
  }

  private JsonNode get(String uri) {
    return http.get().uri(uri).retrieve().body(JsonNode.class);
  }

  @Test
  void reconciliationRecoversDeploymentsThatNeverArrived() throws Exception {
    UUID repository = repository("demo", "https://github.com/guilu/demo-repo");
    // Sin la App instalada no se puede reconciliar.
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                http.post()
                    .uri("/api/repositories/" + repository + "/deployments/reconcile")
                    .retrieve()
                    .body(JsonNode.class))
        .isInstanceOf(org.springframework.web.client.HttpClientErrorException.Conflict.class);

    GITHUB.install(9, "guilu", "guilu/demo-repo");
    signed("installation", "d-inst-dl", fixture("installation-created"));
    awaitDelivery("d-inst-dl", "PROCESSED");
    // Uno terminado y otro a medias, y ningún webhook.
    GITHUB.deployment("guilu/demo-repo", 900001, DEPLOYED_SHA, "omarchy", "2026-11-05T18:00:00Z");
    GITHUB.deploymentStatus(900001, 950001, "in_progress", "2026-11-05T18:00:05Z");
    GITHUB.deploymentStatus(900001, 950002, "success", "2026-11-05T18:01:30Z");
    GITHUB.deployment("guilu/demo-repo", 900002, "b".repeat(40), "omarchy", "2026-11-05T19:00:00Z");
    GITHUB.deploymentStatus(900002, 950101, "in_progress", "2026-11-05T19:00:05Z");

    JsonNode result =
        http.post()
            .uri("/api/repositories/" + repository + "/deployments/reconcile")
            .retrieve()
            .body(JsonNode.class);
    assertThat(result.path("deployments").asInt()).isEqualTo(2);
    assertThat(result.path("statuses").asInt()).isEqualTo(3);
    JsonNode found =
        http.get()
            .uri("/api/repositories/" + repository + "/deployments")
            .retrieve()
            .body(JsonNode.class);
    assertThat(found.findValuesAsString("externalId")).containsExactly("900002", "900001");
    assertThat(found.findValuesAsString("status")).containsExactly("IN_PROGRESS", "SUCCEEDED");

    // Otra vez: el terminado ya no pide sus estados y no cambia nada.
    GITHUB.repoRequests.clear();
    JsonNode again =
        http.post()
            .uri("/api/repositories/" + repository + "/deployments/reconcile")
            .retrieve()
            .body(JsonNode.class);
    assertThat(again.path("changed").asInt()).isZero();
    assertThat(GITHUB.repoRequests)
        .noneMatch(r -> r.contains("/deployments/900001/statuses"))
        .anyMatch(r -> r.contains("/deployments/900002/statuses"));
  }

  private UUID repository(String name, String remoteUrl) {
    Map<String, Object> body = new HashMap<>();
    body.put("name", name);
    body.put("localPath", "/repos/" + name);
    body.put("remoteUrl", remoteUrl);
    return UUID.fromString(
        http.post()
            .uri("/api/projects/" + projectId + "/repositories")
            .body(body)
            .retrieve()
            .body(JsonNode.class)
            .path("id")
            .asString());
  }

  private HttpResponse<String> signed(String event, String delivery, byte[] body)
      throws IOException, InterruptedException {
    return send(event, delivery, body, WebhookSignature.sign(SECRET, body));
  }

  /** Sin sesión ni HTTP Basic, como llega de GitHub. */
  private HttpResponse<String> send(String event, String delivery, byte[] body, String signature)
      throws IOException, InterruptedException {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/github/webhooks"))
            .header("Content-Type", "application/json")
            .header("X-GitHub-Event", event)
            .header("X-GitHub-Delivery", delivery)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body));
    if (signature != null) {
      request.header("X-Hub-Signature-256", signature);
    }
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private JsonNode deliveries() {
    return http.get().uri("/api/github/deliveries").retrieve().body(JsonNode.class);
  }

  private JsonNode awaitDelivery(String id, String status) {
    return await(
        () -> {
          for (JsonNode delivery : deliveries()) {
            if (delivery.path("deliveryId").asString().equals(id)
                && delivery.path("status").asString().equals(status)) {
              return delivery;
            }
          }
          return null;
        },
        "la entrega " + id + " en " + status);
  }

  private JsonNode events(UUID repository) {
    return http.get()
        .uri("/api/events?aggregateId={id}&types=github.*", repository)
        .retrieve()
        .body(JsonNode.class);
  }

  private static JsonNode link(JsonNode status, UUID repository) {
    for (JsonNode link : status.path("repositories")) {
      if (link.path("id").asString().equals(repository.toString())) {
        return link;
      }
    }
    throw new AssertionError("Sin enlace para " + repository);
  }

  private int count(String sql) {
    return jdbc.sql(sql).query(Integer.class).single();
  }

  private static byte[] fixture(String name) throws IOException {
    return Files.readAllBytes(FIXTURES.resolve(name + ".json"));
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
