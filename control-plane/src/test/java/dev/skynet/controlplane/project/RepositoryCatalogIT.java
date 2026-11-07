package dev.skynet.controlplane.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skynet.controlplane.support.IntegrationTest;
import dev.skynet.protocol.runner.DiscoveredRepository;
import dev.skynet.protocol.runner.HeartbeatReply;
import dev.skynet.protocol.runner.RepositoryCatalog;
import dev.skynet.protocol.runner.RunnerHeartbeat;
import dev.skynet.protocol.runner.RunnerRegistration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;

/** Catálogo de repositorios de los runners (IP-A). */
class RepositoryCatalogIT extends IntegrationTest {

  private static DiscoveredRepository repo(String path, String name) {
    return new DiscoveredRepository(
        path,
        name,
        name,
        "directory",
        "Gestor de formularios",
        "README.md",
        "git@github.com:guilu/" + name + ".git",
        "main",
        "origin/HEAD",
        "gradle",
        "./gradlew check",
        List.of("**/build/test-results/**/*.xml"),
        List.of(".github/workflows/**"),
        "a41c9e2a41c9e2a41c9e2a41c9e2a41c9e2a41c9",
        "Exportar a PDF",
        Instant.parse("2026-11-04T10:00:00Z"),
        false,
        true);
  }

  @Test
  void theLatestCatalogOfEachRunnerIsListedAndCrossedWithRegisteredRepositories() {
    String token = register("omarchy");
    String project =
        post("/api/projects", Map.of("key", "SKY", "name", "Skynet")).path("id").asString();
    // Registrada a mano con barra final: cuenta como la misma ruta.
    post(
        "/api/projects/" + project + "/repositories",
        Map.of("name", "skynet", "localPath", "/home/diego/code/skynet/"));

    send(token, catalog(repo("/home/diego/code/viejo", "viejo")));
    send(
        token,
        catalog(
            repo("/home/diego/code/forma", "forma"), repo("/home/diego/code/skynet", "skynet")));

    JsonNode runners = get("/api/repository-catalog");
    assertThat(runners).hasSize(1);
    JsonNode runner = runners.get(0);
    assertThat(runner.path("runnerName").asString()).isEqualTo("omarchy");
    assertThat(runner.path("roots").get(0).asString()).isEqualTo("/home/diego/code");
    assertThat(runner.path("scannedAt").asString()).isEqualTo("2026-11-06T22:00:00Z");
    List<String> paths = new ArrayList<>();
    runner
        .path("repositories")
        .forEach(e -> paths.add(e.path("repository").path("path").asString()));
    assertThat(paths).containsExactly("/home/diego/code/forma", "/home/diego/code/skynet");

    JsonNode forma = runner.path("repositories").get(0);
    assertThat(forma.path("registered").isNull()).isTrue();
    assertThat(forma.path("repository").path("validationCommand").asString())
        .isEqualTo("./gradlew check");
    assertThat(forma.path("repository").path("lastCommitAt").asString())
        .isEqualTo("2026-11-04T10:00:00Z");
    JsonNode skynet = runner.path("repositories").get(1).path("registered");
    assertThat(skynet.path("projectKey").asString()).isEqualTo("SKY");
    assertThat(skynet.path("projectId").asString()).isEqualTo(project);
  }

  @Test
  void textIsSanitizedForJsonb() {
    String token = register("omarchy");
    DiscoveredRepository dirty =
        new DiscoveredRepository(
            "/code/x",
            "x",
            "x\u0000y",
            "directory",
            null,
            null,
            null,
            "main",
            "default",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            false);
    send(token, catalog(dirty));
    assertThat(
            get("/api/repository-catalog")
                .get(0)
                .path("repositories")
                .get(0)
                .path("repository")
                .path("projectName")
                .asString())
        .isEqualTo("x\\u0000y");
  }

  @Test
  void refreshReachesEveryRunnerOnceInItsNextHeartbeat() {
    String a = register("omarchy");
    String b = register("portatil");
    assertThat(heartbeat(a).refreshRepositories()).isFalse();

    http.post().uri("/api/repository-catalog/refresh").retrieve().toBodilessEntity();

    assertThat(heartbeat(a).refreshRepositories()).isTrue();
    assertThat(heartbeat(a).refreshRepositories()).isFalse();
    assertThat(heartbeat(b).refreshRepositories()).isTrue();
    assertThat(heartbeat(b).refreshRepositories()).isFalse();
  }

  @Test
  void runnersMustAuthenticateAndRespectTheLimits() {
    assertStatus(
        HttpStatus.UNAUTHORIZED,
        () ->
            http.post()
                .uri("/api/runner/repository-catalog")
                .body(catalog())
                .retrieve()
                .toBodilessEntity());
    String token = register("omarchy");
    assertStatus(HttpStatus.BAD_REQUEST, () -> send(token, catalog(repo("relativa", "x"))));
    List<DiscoveredRepository> many = new ArrayList<>();
    for (int i = 0; i <= RepositoryCatalogs.MAX_REPOSITORIES; i++) {
      many.add(repo("/code/r" + i, "r" + i));
    }
    assertStatus(
        HttpStatus.BAD_REQUEST,
        () -> send(token, new RepositoryCatalog(Instant.now(), List.of("/code"), many, true)));
    assertThat(get("/api/repository-catalog")).isEmpty();
  }

  private static RepositoryCatalog catalog(DiscoveredRepository... repositories) {
    return new RepositoryCatalog(
        Instant.parse("2026-11-06T22:00:00Z"),
        List.of("/home/diego/code"),
        List.of(repositories),
        false);
  }

  private String register(String name) {
    return http.post()
        .uri("/api/runner/register")
        .body(new RunnerRegistration(name, RUNNER_REGISTRATION_TOKEN, "dev", 1, null))
        .retrieve()
        .body(JsonNode.class)
        .path("token")
        .asString();
  }

  private void send(String token, RepositoryCatalog catalog) {
    http.post()
        .uri("/api/runner/repository-catalog")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .body(catalog)
        .retrieve()
        .toBodilessEntity();
  }

  private HeartbeatReply heartbeat(String token) {
    return http.post()
        .uri("/api/runner/heartbeat")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .body(new RunnerHeartbeat(1, List.of()))
        .retrieve()
        .body(HeartbeatReply.class);
  }

  private JsonNode post(String path, Object body) {
    return http.post().uri(path).body(body).retrieve().body(JsonNode.class);
  }

  private JsonNode get(String path) {
    return http.get().uri(path).retrieve().body(JsonNode.class);
  }

  private static void assertStatus(HttpStatus expected, Runnable call) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            HttpClientErrorException.class,
            e -> assertThat(e.getStatusCode().value()).isEqualTo(expected.value()));
  }
}
