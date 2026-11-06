package dev.skynet.controlplane.github;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skynet.controlplane.support.IntegrationTest;
import dev.skynet.protocol.runner.DiscoveredRepository;
import dev.skynet.protocol.runner.RepositoryCatalog;
import dev.skynet.protocol.runner.RunnerRegistration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/** El catálogo de repositorios dice lo que GitHub sabe de cada remoto (IP-B). */
class CatalogGitHubIT extends IntegrationTest {

  private static final FakeGitHub GITHUB = new FakeGitHub();

  @DynamicPropertySource
  static void github(DynamicPropertyRegistry registry) {
    registry.add("skynet.github.app-id", () -> FakeGitHub.APP_ID);
    registry.add("skynet.github.private-key-file", () -> FakeGitHub.writeKey().toString());
    registry.add("skynet.github.webhook-secret", () -> "secreto-de-prueba");
    registry.add("skynet.github.api-url", GITHUB::url);
    registry.add("skynet.github.web-url", () -> "https://github.com");
  }

  @AfterAll
  static void stop() {
    GITHUB.close();
  }

  @BeforeEach
  void setUp() {
    GITHUB.reset();
  }

  private static DiscoveredRepository repo(String name, String remote) {
    return new DiscoveredRepository(
        "/home/diego/code/" + name,
        name,
        name,
        "directory",
        null,
        null,
        remote,
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
  }

  @Test
  void coveredRemotesBringTheirGitHubDescription() {
    GITHUB.install(7, "guilu", "guilu/forma", "guilu/akademia");
    GITHUB.descriptions.put("guilu/forma", "Forms engine");
    http.post().uri("/api/github/sync").retrieve().toBodilessEntity();
    String token =
        http.post()
            .uri("/api/runner/register")
            .body(new RunnerRegistration("omarchy", RUNNER_REGISTRATION_TOKEN, "dev", 1, null))
            .retrieve()
            .body(JsonNode.class)
            .path("token")
            .asString();
    http.post()
        .uri("/api/runner/repository-catalog")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .body(
            new RepositoryCatalog(
                Instant.now(),
                List.of("/home/diego/code"),
                List.of(
                    repo("akademia", "https://github.com/guilu/akademia.git"),
                    repo("ajeno", "git@github.com:otro/ajeno.git"),
                    repo("forma", "git@github.com:guilu/forma.git"),
                    repo("gitlab", "https://gitlab.com/guilu/x.git"),
                    repo("notas", null)),
                false))
        .retrieve()
        .toBodilessEntity();

    Map<String, JsonNode> github = new HashMap<>();
    http.get()
        .uri("/api/repository-catalog")
        .retrieve()
        .body(JsonNode.class)
        .get(0)
        .path("repositories")
        .forEach(e -> github.put(e.path("repository").path("name").asString(), e.path("github")));

    assertThat(github.get("forma").path("covered").asBoolean()).isTrue();
    assertThat(github.get("forma").path("description").asString()).isEqualTo("Forms engine");
    assertThat(github.get("forma").path("htmlUrl").asString())
        .isEqualTo("https://github.com/guilu/forma");
    assertThat(github.get("akademia").path("covered").asBoolean()).isTrue();
    assertThat(github.get("akademia").path("description").isNull()).isTrue();
    assertThat(github.get("ajeno").path("covered").asBoolean()).isFalse();
    assertThat(github.get("ajeno").path("fullName").asString()).isEqualTo("otro/ajeno");
    assertThat(github.get("gitlab").isNull()).isTrue();
    assertThat(github.get("notas").isNull()).isTrue();
  }
}
