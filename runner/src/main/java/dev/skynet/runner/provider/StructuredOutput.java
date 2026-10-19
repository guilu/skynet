package dev.skynet.runner.provider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

/**
 * El schema de la salida estructurada que recibe el proveedor. Los tres CLIs la validan ellos
 * mismos ({@code --json-schema} o {@code --output-schema}); el runner solo resuelve el schema que
 * vive en un fichero del repositorio.
 */
public final class StructuredOutput {

  private static final ObjectMapper JSON = JsonMapper.builder().build();

  private StructuredOutput() {}

  /**
   * El schema que recibe el proveedor: el mismo o, si es {@code {"$ref": "<ruta>"}} (el {@code
   * outputSchema} de una fase en un fichero del repositorio), el contenido de ese fichero en el
   * worktree.
   *
   * @throws IllegalArgumentException con el motivo si el fichero no existe, se sale del worktree o
   *     no es el JSON Schema de un objeto con sus campos en {@code properties}
   */
  public static String resolve(String schema, Path worktree) throws IOException {
    if (schema == null) {
      return null;
    }
    JsonNode node = JSON.readTree(schema);
    if (!(node instanceof ObjectNode object)
        || object.size() != 1
        || !(object.get("$ref") instanceof StringNode ref)) {
      return schema;
    }
    String path = ref.stringValue();
    Path root = worktree.toAbsolutePath().normalize();
    Path file = root.resolve(path).normalize();
    if (!file.startsWith(root)) {
      throw new IllegalArgumentException("`" + path + "` está fuera del worktree");
    }
    if (!Files.isRegularFile(file)) {
      throw new IllegalArgumentException("No existe `" + path + "` en el worktree");
    }
    JsonNode content;
    try {
      content = JSON.readTree(Files.readString(file));
    } catch (JacksonException e) {
      throw new IllegalArgumentException(
          "`" + path + "` no es JSON válido: " + e.getOriginalMessage());
    }
    if (!content.path("type").asString("").equals("object")
        || !(content.get("properties") instanceof ObjectNode properties)
        || properties.isEmpty()) {
      throw new IllegalArgumentException(
          "`"
              + path
              + "` no es el JSON Schema de un objeto: necesita `\"type\": \"object\"` y sus"
              + " campos en `properties`");
    }
    return JSON.writeValueAsString(content);
  }
}
