package dev.skynet.runner.provider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

/**
 * Salida estructurada para los proveedores cuyo CLI no la admite (Gemini): el runner pide al agente
 * que termine con un JSON, lo saca de su último mensaje y lo valida contra el schema del {@code
 * output} del agente. El schema es el que admite el YAML: un objeto con campos de tipo {@code
 * boolean}, {@code string}, {@code number}, {@code integer}, {@code array} u {@code object}; se
 * comprueban los tipos, recursivamente si el campo describe sus {@code properties} o sus {@code
 * items}, y que estén todos los campos.
 */
public final class StructuredOutput {

  private static final ObjectMapper JSON = JsonMapper.builder().build();

  private final JsonNode schema;

  public StructuredOutput(String schema) {
    this.schema = JSON.readTree(schema);
  }

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

  /** Instrucción que se añade al prompt para que el agente termine con el JSON. */
  public String instruction() {
    return "\n\n---\nCuando termines, acaba tu respuesta con un único bloque ```json``` que contenga"
        + " un objeto JSON que cumpla este JSON Schema, con todos sus campos y nada más fuera de"
        + " él en ese bloque:\n```json\n"
        + JSON.writeValueAsString(schema)
        + "\n```";
  }

  /** Resultado de buscar y validar la salida en el texto del agente. */
  public record Result(Map<String, Object> value, String error) {}

  /** Busca el último objeto JSON del texto y lo valida. */
  public Result read(String text) {
    Optional<ObjectNode> found = lastObject(text);
    if (found.isEmpty()) {
      return new Result(null, "El agente no terminó con un objeto JSON");
    }
    List<String> problems = new ArrayList<>();
    validate(found.get(), schema, "", problems);
    if (!problems.isEmpty()) {
      return new Result(
          null, "La salida no cumple el schema de `output`: " + String.join("; ", problems));
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> value = JSON.treeToValue(found.get(), Map.class);
    return new Result(value, null);
  }

  /**
   * Último objeto JSON del texto: el de un bloque {@code ```json} si lo hay o, si no, el que acaba
   * en la última llave de cierre.
   */
  static Optional<ObjectNode> lastObject(String text) {
    if (text == null || text.isBlank()) {
      return Optional.empty();
    }
    int fence = text.lastIndexOf("```json");
    if (fence >= 0) {
      int start = text.indexOf('\n', fence);
      int end = start < 0 ? -1 : text.indexOf("```", start);
      if (start >= 0) {
        Optional<ObjectNode> block =
            parse(end < 0 ? text.substring(start) : text.substring(start, end));
        if (block.isPresent()) {
          return block;
        }
      }
    }
    int close = text.lastIndexOf('}');
    for (int open = text.lastIndexOf('{', close);
        open >= 0 && close >= 0;
        open = open == 0 ? -1 : text.lastIndexOf('{', open - 1)) {
      Optional<ObjectNode> candidate = parse(text.substring(open, close + 1));
      if (candidate.isPresent()) {
        return candidate;
      }
    }
    return Optional.empty();
  }

  private static Optional<ObjectNode> parse(String json) {
    try {
      return JSON.readTree(json.strip()) instanceof ObjectNode object
          ? Optional.of(object)
          : Optional.empty();
    } catch (JacksonException e) {
      return Optional.empty();
    }
  }

  private static void validate(JsonNode value, JsonNode schema, String path, List<String> out) {
    String type = schema.path("type").asString(null);
    if (type != null && !hasType(value, type)) {
      out.add((path.isEmpty() ? "la salida" : "`" + path + "`") + " debe ser " + type);
      return;
    }
    JsonNode properties = schema.get("properties");
    if (value.isObject() && properties != null && properties.isObject()) {
      for (String name : properties.propertyNames()) {
        String field = path.isEmpty() ? name : path + "." + name;
        JsonNode child = value.get(name);
        if (child == null || child.isNull()) {
          out.add("falta `" + field + "`");
        } else {
          validate(child, properties.get(name), field, out);
        }
      }
    }
    JsonNode items = schema.get("items");
    if (value.isArray() && items != null && items.isObject()) {
      for (int i = 0; i < value.size(); i++) {
        validate(value.get(i), items, path + "[" + i + "]", out);
      }
    }
  }

  private static boolean hasType(JsonNode value, String type) {
    return switch (type) {
      case "object" -> value.isObject();
      case "array" -> value.isArray();
      case "string" -> value.isString();
      case "boolean" -> value.isBoolean();
      case "integer" -> value.isIntegralNumber();
      case "number" -> value.isNumber();
      case "null" -> value.isNull();
      default -> true;
    };
  }
}
