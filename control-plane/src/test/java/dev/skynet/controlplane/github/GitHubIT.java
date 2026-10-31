package dev.skynet.controlplane.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skynet.controlplane.support.IntegrationTest;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpServerErrorException;
import tools.jackson.databind.JsonNode;

/**
 * La GitHub App contra un GitHub falso: comprobar la App, sincronizar instalaciones y repositorios,
 * enlazar cada repositorio de Skynet por su URL remota y los tokens de instalación.
 */
class GitHubIT extends IntegrationTest {

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

  @Autowired private GitHubTokens tokens;

  private String projectId;

  @BeforeEach
  void setUp() {
    GITHUB.reset();
    projectId =
        http.post()
            .uri("/api/projects")
            .body(Map.of("key", "GH", "name", "GitHub"))
            .retrieve()
            .body(JsonNode.class)
            .path("id")
            .asString();
  }

  @Test
  void syncLinksEachRepositoryByItsRemoteUrl() {
    GITHUB.install(7, "guilu", "guilu/demo-repo", "guilu/otro-priv");
    repository("demo-https", "https://github.com/Guilu/Demo-Repo.git");
    repository("demo-ssh", "git@github.com:guilu/demo-repo.git");
    repository("sin-instalar", "https://github.com/guilu/ajeno");
    repository("gitlab", "https://gitlab.com/guilu/demo-repo");
    repository("local", null);

    JsonNode status = sync();

    assertThat(status.path("configured").asBoolean()).isTrue();
    assertThat(status.path("missing")).isEmpty();
    assertThat(status.path("appId").asString()).isEqualTo(FakeGitHub.APP_ID);
    assertThat(status.path("app").path("slug").asString()).isEqualTo("skynet-de-prueba");
    assertThat(status.path("app").path("owner").asString()).isEqualTo("guilu");
    assertThat(status.path("error").isNull()).isTrue();

    JsonNode installation = status.path("installations").get(0);
    assertThat(installation.path("id").asLong()).isEqualTo(7);
    assertThat(installation.path("account").asString()).isEqualTo("guilu");
    assertThat(installation.path("suspendedAt").isNull()).isTrue();
    assertThat(installation.path("repositories").findValuesAsString("fullName"))
        .containsExactly("guilu/demo-repo", "guilu/otro-priv");
    assertThat(installation.path("repositories").get(1).path("private").asBoolean()).isTrue();

    Map<String, JsonNode> links = links(status);
    assertThat(links.get("demo-https").path("access").asString()).isEqualTo("CONNECTED");
    assertThat(links.get("demo-https").path("fullName").asString()).isEqualTo("Guilu/Demo-Repo");
    assertThat(links.get("demo-https").path("installationId").asLong()).isEqualTo(7);
    assertThat(links.get("demo-https").path("installationAccount").asString()).isEqualTo("guilu");
    assertThat(links.get("demo-https").path("githubRepositoryId").asLong())
        .isEqualTo(FakeGitHub.repositoryId("guilu/demo-repo"));
    assertThat(links.get("demo-https").path("projectKey").asString()).isEqualTo("GH");
    assertThat(links.get("demo-ssh").path("access").asString()).isEqualTo("CONNECTED");
    assertThat(links.get("sin-instalar").path("access").asString()).isEqualTo("NOT_INSTALLED");
    assertThat(links.get("sin-instalar").path("installationId").isNull()).isTrue();
    assertThat(links.get("gitlab").path("access").asString()).isEqualTo("NOT_GITHUB");
    assertThat(links.get("gitlab").path("fullName").isNull()).isTrue();
    assertThat(links.get("local").path("access").asString()).isEqualTo("NOT_GITHUB");

    // Lo que se lista solo pide leer metadatos, y para toda la instalación.
    assertThat(GITHUB.tokenRequests).hasSize(1);
    assertThat(GITHUB.tokenRequests.getFirst().path("permissions").path("metadata").asString())
        .isEqualTo("read");
    assertThat(GITHUB.tokenRequests.getFirst().has("repository_ids")).isFalse();
    // El token no se guarda en ninguna tabla.
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM github_installation i"
                        + " JOIN github_installation_repository r ON r.installation_id = i.id"
                        + " WHERE i::text LIKE '%ghs_%' OR r::text LIKE '%ghs_%'")
                .query(Integer.class)
                .single())
        .isZero();
  }

  @Test
  void resyncDropsUninstalledAndKeepsSuspendedInstallations() {
    GITHUB.install(7, "guilu", "guilu/demo-repo");
    GITHUB.install(8, "otra-cuenta", "otra-cuenta/lib");
    repository("demo", "https://github.com/guilu/demo-repo");
    repository("lib", "https://github.com/otra-cuenta/lib");
    sync();

    GITHUB.uninstall(8);
    GITHUB.suspend(7);
    JsonNode status = sync();

    assertThat(status.path("installations")).hasSize(1);
    JsonNode suspended = status.path("installations").get(0);
    assertThat(suspended.path("id").asLong()).isEqualTo(7);
    assertThat(suspended.path("suspendedAt").isNull()).isFalse();
    // Suspendida: GitHub no da tokens, así que se conservan los repositorios de antes.
    assertThat(suspended.path("repositories").findValuesAsString("fullName"))
        .containsExactly("guilu/demo-repo");
    Map<String, JsonNode> links = links(status);
    assertThat(links.get("demo").path("access").asString()).isEqualTo("NOT_INSTALLED");
    assertThat(links.get("demo").path("installationId").asLong()).isEqualTo(7);
    assertThat(links.get("lib").path("access").asString()).isEqualTo("NOT_INSTALLED");
    assertThat(links.get("lib").path("installationId").isNull()).isTrue();
  }

  @Test
  void aRepositoryTokenIsLimitedToItAndReusedUntilItIsAboutToExpire() {
    GITHUB.install(7, "guilu", "guilu/demo-repo");
    long repositoryId = FakeGitHub.repositoryId("guilu/demo-repo");

    String first = tokens.forRepository(7, repositoryId, Map.of("contents", "write"));
    String again = tokens.forRepository(7, repositoryId, Map.of("contents", "write"));
    String readOnly = tokens.forRepository(7, repositoryId, Map.of("contents", "read"));

    assertThat(first).startsWith("ghs_").isEqualTo(again);
    assertThat(readOnly).isNotEqualTo(first);
    assertThat(GITHUB.tokenRequests).hasSize(2);
    JsonNode request = GITHUB.tokenRequests.getFirst();
    assertThat(request.path("repository_ids").get(0).asLong()).isEqualTo(repositoryId);
    assertThat(request.path("repository_ids")).hasSize(1);
    assertThat(request.path("permissions").path("contents").asString()).isEqualTo("write");

    GITHUB.suspend(7);
    tokens.forget(7);
    assertThatThrownBy(() -> tokens.forRepository(7, repositoryId, Map.of()))
        .isInstanceOf(GitHubException.class)
        .hasMessageContaining("403")
        .hasMessageNotContaining("ghs_");
  }

  @Test
  void badCredentialsAreAGatewayErrorAndShowInTheStatus() {
    GITHUB.install(7, "guilu", "guilu/demo-repo");
    sync();
    GITHUB.appStatus = 401;

    assertThatThrownBy(this::sync)
        .isInstanceOfSatisfying(
            HttpServerErrorException.class,
            e -> {
              assertThat(e.getStatusCode().value()).isEqualTo(502);
              assertThat(e.getResponseBodyAsString()).contains("GitHub respondió 401");
            });

    JsonNode status = get();
    assertThat(status.path("app").isNull()).isTrue();
    assertThat(status.path("error").asString()).contains("401").contains("Bad credentials");
    // Lo que había no se borra porque GitHub falle.
    assertThat(status.path("installations")).hasSize(1);
  }

  private void repository(String name, String remoteUrl) {
    Map<String, Object> body = new HashMap<>();
    body.put("name", name);
    body.put("localPath", "/repos/" + name);
    body.put("remoteUrl", remoteUrl);
    http.post()
        .uri("/api/projects/" + projectId + "/repositories")
        .body(body)
        .retrieve()
        .toBodilessEntity();
  }

  private JsonNode sync() {
    return http.post().uri("/api/github/sync").retrieve().body(JsonNode.class);
  }

  private JsonNode get() {
    return http.get().uri("/api/github").retrieve().body(JsonNode.class);
  }

  private static Map<String, JsonNode> links(JsonNode status) {
    Map<String, JsonNode> links = new HashMap<>();
    for (JsonNode link : status.path("repositories")) {
      links.put(link.path("name").asString(), link);
    }
    assertThat(links.keySet()).doesNotContainNull();
    return links;
  }
}
