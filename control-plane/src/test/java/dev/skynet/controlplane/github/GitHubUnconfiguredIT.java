package dev.skynet.controlplane.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skynet.controlplane.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;

/** Sin las variables de la App, Skynet arranca, dice qué falta y no llama a GitHub. */
class GitHubUnconfiguredIT extends IntegrationTest {

  @Test
  void statusSaysWhatIsMissing() {
    JsonNode status = http.get().uri("/api/github").retrieve().body(JsonNode.class);

    assertThat(status.path("configured").asBoolean()).isFalse();
    assertThat(status.path("missing").toString())
        .contains("SKYNET_GITHUB_APP_ID")
        .contains("SKYNET_GITHUB_PRIVATE_KEY_FILE")
        .contains("SKYNET_GITHUB_WEBHOOK_SECRET");
    assertThat(status.path("apiUrl").asString()).isEqualTo("https://api.github.com");
    assertThat(status.path("app").isNull()).isTrue();
    assertThat(status.path("error").isNull()).isTrue();
    assertThat(status.path("installations")).isEmpty();
  }

  @Test
  void syncIsAConflict() {
    assertThatThrownBy(() -> http.post().uri("/api/github/sync").retrieve().toBodilessEntity())
        .isInstanceOfSatisfying(
            HttpClientErrorException.Conflict.class,
            e -> assertThat(e.getResponseBodyAsString()).contains("SKYNET_GITHUB_APP_ID"));
  }

  @Test
  void aWebhookIsUnavailable() throws Exception {
    java.net.http.HttpResponse<String> response =
        java.net.http.HttpClient.newHttpClient()
            .send(
                java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("http://localhost:" + port + "/api/github/webhooks"))
                    .header("X-GitHub-Event", "ping")
                    .header("X-GitHub-Delivery", "d-1")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}"))
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(response.body()).contains("no está configurada");
  }
}
