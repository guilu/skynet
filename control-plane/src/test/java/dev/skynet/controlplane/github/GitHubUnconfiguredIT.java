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
}
