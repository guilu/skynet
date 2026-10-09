package dev.skynet.controlplane.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skynet.controlplane.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;

/** La paleta de la web: por defecto vacía, se guarda entera y valida los colores. */
class AppearanceIT extends IntegrationTest {

  @Test
  void withoutAPaletteEveryColorIsTheDefault() {
    JsonNode appearance = get();
    assertThat(appearance.path("colors").path("primary").isNull()).isTrue();
    assertThat(appearance.path("updatedAt").isNull()).isTrue();
  }

  @Test
  void savingReplacesTheWholePaletteAndNormalizesTheColors() {
    put(Map.of("primary", "#0E8A8C", "bad", "#d6336c"));
    JsonNode saved = get();
    assertThat(saved.path("colors").path("primary").asString()).isEqualTo("#0e8a8c");
    assertThat(saved.path("colors").path("bad").asString()).isEqualTo("#d6336c");
    assertThat(saved.path("updatedAt").isNull()).isFalse();

    put(Map.of("ok", "#1f9d55"));
    JsonNode replaced = get().path("colors");
    assertThat(replaced.path("primary").isNull()).isTrue();
    assertThat(replaced.path("ok").asString()).isEqualTo("#1f9d55");

    put(Map.of());
    assertThat(get().path("colors").path("ok").isNull()).isTrue();
  }

  @Test
  void aColorThatIsNotHexIsRejected() {
    assertThatThrownBy(() -> put(Map.of("primary", "red")))
        .isInstanceOf(HttpClientErrorException.BadRequest.class);
    assertThatThrownBy(() -> put(Map.of("primary", "#12345")))
        .isInstanceOf(HttpClientErrorException.BadRequest.class);
    assertThat(get().path("colors").path("primary").isNull()).isTrue();
  }

  private JsonNode get() {
    return http.get().uri("/api/settings/appearance").retrieve().body(JsonNode.class);
  }

  private void put(Map<String, String> colors) {
    http.put()
        .uri("/api/settings/appearance")
        .body(Map.of("colors", colors))
        .retrieve()
        .toBodilessEntity();
  }
}
