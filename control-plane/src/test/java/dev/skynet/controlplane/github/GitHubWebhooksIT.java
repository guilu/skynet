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
