package dev.skynet.runner.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StructuredOutputTest {

  private final StructuredOutput output =
      new StructuredOutput(
          """
          {"type":"object","properties":{
            "hasBlockingFindings":{"type":"boolean"},
            "issues":{"type":"array","items":{"type":"object","properties":{"line":{"type":"integer"}}}}}}
          """);

  @Test
  void readsTheLastJsonBlockOfTheMessage() {
    StructuredOutput.Result r =
        output.read(
            """
            Revisado. Un ejemplo: {"no": "este"}.
            ```json
            {"hasBlockingFindings": true, "issues": [{"line": 3}]}
            ```
            """);

    assertThat(r.error()).isNull();
    assertThat(r.value())
        .isEqualTo(Map.of("hasBlockingFindings", true, "issues", List.of(Map.of("line", 3))));
  }

  @Test
  void withoutABlockTakesTheLastObject() {
    StructuredOutput.Result r =
        output.read("Listo: {\"hasBlockingFindings\": false, \"issues\": []} y nada más");

    assertThat(r.value()).containsEntry("hasBlockingFindings", false);
  }

  @Test
  void aMissingFieldOrAWrongTypeIsAnError() {
    assertThat(output.read("{\"issues\": []}").error())
        .isEqualTo("La salida no cumple el schema de `output`: falta `hasBlockingFindings`");
    assertThat(
            output
                .read("{\"hasBlockingFindings\": \"no\", \"issues\": [{\"line\": \"x\"}]}")
                .error())
        .isEqualTo(
            "La salida no cumple el schema de `output`: `hasBlockingFindings` debe ser boolean;"
                + " `issues[0].line` debe ser integer");
  }

  @Test
  void textWithoutJsonIsAnError() {
    assertThat(output.read("He terminado.").error())
        .isEqualTo("El agente no terminó con un objeto JSON");
    assertThat(output.read(null).error()).isEqualTo("El agente no terminó con un objeto JSON");
  }

  @Test
  void theInstructionCarriesTheSchema() {
    assertThat(output.instruction()).contains("```json").contains("\"hasBlockingFindings\"");
  }
}
