package dev.skynet.runner.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StructuredOutputTest {

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

  @Test
  void aSymlinkThatLeavesTheWorktreeIsRefused(@TempDir Path dir) throws IOException {
    Path worktree = Files.createDirectories(dir.resolve("worktree"));
    Path secret = dir.resolve("secreto.json");
    Files.writeString(
        secret, "{\"type\": \"object\", \"properties\": {\"token\": {\"type\": \"string\"}}}");
    Files.createDirectories(worktree.resolve("schemas"));
    Files.createSymbolicLink(worktree.resolve("schemas/spec.json"), secret);
    Files.createSymbolicLink(worktree.resolve("fuera"), dir);

    assertThatThrownBy(() -> StructuredOutput.resolve("{\"$ref\":\"schemas/spec.json\"}", worktree))
        .hasMessage("`schemas/spec.json` está fuera del worktree");
    assertThatThrownBy(
            () -> StructuredOutput.resolve("{\"$ref\":\"fuera/secreto.json\"}", worktree))
        .hasMessage("`fuera/secreto.json` está fuera del worktree");
  }

  @Test
  void aSymlinkInsideTheWorktreeIsFollowed(@TempDir Path worktree) throws IOException {
    Files.createDirectories(worktree.resolve("schemas"));
    Files.writeString(
        worktree.resolve("schemas/real.json"),
        "{\"type\": \"object\", \"properties\": {\"ok\": {\"type\": \"boolean\"}}}");
    Files.createSymbolicLink(worktree.resolve("schemas/spec.json"), Path.of("real.json"));

    assertThat(StructuredOutput.resolve("{\"$ref\":\"schemas/spec.json\"}", worktree))
        .isEqualTo("{\"type\":\"object\",\"properties\":{\"ok\":{\"type\":\"boolean\"}}}");
  }
}
