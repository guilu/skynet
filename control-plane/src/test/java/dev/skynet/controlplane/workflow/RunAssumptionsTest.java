package dev.skynet.controlplane.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RunAssumptionsTest {

  @Test
  void readsTheTextsAndSkipsWhatIsNotOne() {
    assertThat(
            RunAssumptions.parse(
                "{\"summary\":\"x\",\"assumptions\":[\"  El límite\\n es por usuario \",\"\","
                    + " 3, null, \"Sin caché\"]}"))
        .containsExactly("El límite es por usuario", "Sin caché");
    assertThat(RunAssumptions.parse("{\"summary\":\"x\"}")).isEmpty();
    assertThat(RunAssumptions.parse("{\"assumptions\":\"uno\"}")).isEmpty();
    assertThat(RunAssumptions.parse("no es json")).isEmpty();
    assertThat(RunAssumptions.parse(null)).isEmpty();
  }

  @Test
  void aVeryLongAssumptionIsCut() {
    String text = RunAssumptions.parse("{\"assumptions\":[\"" + "a".repeat(1000) + "\"]}").get(0);
    assertThat(text).hasSize(RunAssumptions.MAX_LENGTH).endsWith("…");
  }
}
