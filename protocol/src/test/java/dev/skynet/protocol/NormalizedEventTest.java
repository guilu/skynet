package dev.skynet.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class NormalizedEventTest {

  /** Lo que imprime {@code head -c 40 app.jar} y luego llega en un tool_result. */
  private static final String JAR =
      "PK\u0003\u0004\u0014\u0000\b\b\b\u0000\u0000\u0000META-INF/MANIFEST.MF\u0000�";

  @Test
  void nulAndIncompatibleControlsBecomeVisibleInNestedPayloads() {
    Map<String, Object> inner = new LinkedHashMap<>();
    inner.put("content", JAR);
    inner.put("k\u0000ey", List.of("a\u0000b", Map.of("deep", "x\u0001y"), 3, true));
    List<Object> blocks = new ArrayList<>();
    blocks.add(inner);
    blocks.add("tab\tnewline\ncr\r");

    NormalizedEvent event = event(Map.of("blocks", blocks, "text", "fin\u0000"));

    String json = JsonMapper.shared().writeValueAsString(event);
    // Un NUL real sale escapado (barra, u, 0000); el saneado lleva además la barra escapada.
    assertThat(json.replace("\\\\", "")).doesNotContain("\\u0000").doesNotContain("\\u0001");
    assertThat(event.payload().get("text")).isEqualTo("fin\\u0000");
    @SuppressWarnings("unchecked")
    List<Object> stored = (List<Object>) event.payload().get("blocks");
    @SuppressWarnings("unchecked")
    Map<String, Object> first = (Map<String, Object>) stored.get(0);
    assertThat(first.get("content"))
        .isEqualTo(
            "PK\\u0003\\u0004\\u0014\\u0000\\u0008\\u0008\\u0008\\u0000\\u0000\\u0000"
                + "META-INF/MANIFEST.MF\\u0000�");
    assertThat(first.get("k\\u0000ey"))
        .isEqualTo(List.of("a\\u0000b", Map.of("deep", "x\\u0001y"), 3, true));
    // Tabuladores y saltos de línea se quedan como están.
    assertThat(stored.get(1)).isEqualTo("tab\tnewline\ncr\r");
  }

  @Test
  void loneSurrogatesAreReplaced() {
    NormalizedEvent event = event(Map.of("text", "a\uD800b\uDC00c😀"));

    assertThat(event.payload().get("text")).isEqualTo("a�b�c😀");
  }

  @Test
  void anEventReadFromJsonIsSanitizedToo() {
    String json =
        "{\"eventId\":\""
            + UUID.randomUUID()
            + "\",\"agentRunId\":\""
            + UUID.randomUUID()
            + "\",\"seq\":1,\"occurredAt\":\"2026-10-21T22:00:00Z\",\"type\":\"TOOL_COMPLETED\","
            + "\"payload\":{\"output\":[{\"text\":\"PK\\u0003\\u0004\\u0000\"}]}}";

    NormalizedEvent event = JsonMapper.shared().readValue(json, NormalizedEvent.class);

    assertThat(event.payload())
        .isEqualTo(Map.of("output", List.of(Map.of("text", "PK\\u0003\\u0004\\u0000"))));
  }

  @Test
  void cleanPayloadsAreUnchanged() {
    Map<String, Object> payload = Map.of("text", "hola\tmundo\n", "n", 1, "list", List.of("ñ"));

    assertThat(event(payload).payload()).isEqualTo(payload);
  }

  private static NormalizedEvent event(Map<String, Object> payload) {
    return new NormalizedEvent(
        UUID.randomUUID(),
        UUID.randomUUID(),
        1,
        Instant.now(),
        AgentEventType.TOOL_COMPLETED,
        payload);
  }
}
