package dev.skynet.runner.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

  @Test
  void aStageSchemaInARepositoryFileIsReadFromTheWorktree(@TempDir Path worktree)
      throws IOException {
    Files.createDirectories(worktree.resolve("schemas"));
    Files.writeString(
        worktree.resolve("schemas/spec.json"),
        "{\"type\": \"object\", \"properties\": {\"summary\": {\"type\": \"string\"}}}");

    assertThat(StructuredOutput.resolve("{\"$ref\":\"schemas/spec.json\"}", worktree))
        .isEqualTo("{\"type\":\"object\",\"properties\":{\"summary\":{\"type\":\"string\"}}}");
    // Un schema en línea, o uno con más claves que $ref, pasa tal cual.
    String inline = "{\"type\":\"object\",\"properties\":{\"ok\":{\"type\":\"boolean\"}}}";
    assertThat(StructuredOutput.resolve(inline, worktree)).isSameAs(inline);
    assertThat(StructuredOutput.resolve(null, worktree)).isNull();
  }

  @Test
  void aMissingOrWrongSchemaFileSaysWhy(@TempDir Path worktree) throws IOException {
    Files.writeString(worktree.resolve("roto.json"), "{no es json");
    Files.writeString(worktree.resolve("lista.json"), "{\"type\": \"array\"}");

    assertThatThrownBy(() -> StructuredOutput.resolve("{\"$ref\":\"schemas/spec.json\"}", worktree))
        .hasMessage("No existe `schemas/spec.json` en el worktree");
    assertThatThrownBy(() -> StructuredOutput.resolve("{\"$ref\":\"../fuera.json\"}", worktree))
        .hasMessage("`../fuera.json` está fuera del worktree");
    assertThatThrownBy(() -> StructuredOutput.resolve("{\"$ref\":\"roto.json\"}", worktree))
        .hasMessageStartingWith("`roto.json` no es JSON válido: ");
    assertThatThrownBy(() -> StructuredOutput.resolve("{\"$ref\":\"lista.json\"}", worktree))
        .hasMessage(
            "`lista.json` no es el JSON Schema de un objeto: necesita `\"type\": \"object\"` y sus"
                + " campos en `properties`");
  }
}
