package dev.skynet.runner.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Lo común de los servidores MCP del runner por stdio (el de memoria y el de Skynet): JSON-RPC 2.0,
 * un mensaje por línea, con {@code initialize}, {@code ping}, {@code tools/list} y {@code
 * tools/call}. Cada servidor pone sus herramientas.
 */
public abstract class StdioMcpServer {

  public static final String DEFAULT_PROTOCOL = "2025-06-18";
  public static final List<String> PROTOCOLS =
      List.of("2024-11-05", "2025-03-26", DEFAULT_PROTOCOL);

  protected static final ObjectMapper JSON = JsonMapper.builder().build();

  private final String name;
  private final String version;
  private final String instructions;

  protected StdioMcpServer(String name, String version, String instructions) {
    this.name = name;
    this.version = version;
    this.instructions = instructions;
  }

  /** La lista de herramientas, como la pide {@code tools/list}. */
  public abstract ObjectNode tools();

  /** El resultado de llamar a una herramienta; un fallo va como resultado con error. */
  protected abstract ObjectNode call(String tool, JsonNode arguments) throws InterruptedException;

  /** Atiende mensajes hasta que se cierra la entrada. */
  public void serve(BufferedReader in, PrintStream out) throws IOException, InterruptedException {
    String line;
    while ((line = in.readLine()) != null) {
      if (line.isBlank()) {
        continue;
      }
      JsonNode response = handle(line);
      if (response != null) {
        out.println(JSON.writeValueAsString(response));
        out.flush();
      }
    }
  }

  /** La respuesta a un mensaje, o {@code null} si es una notificación. */
  public JsonNode handle(String line) throws InterruptedException {
    JsonNode message;
    try {
      message = JSON.readTree(line);
    } catch (JacksonException e) {
      return error(null, -32700, "JSON no válido");
    }
    JsonNode id = message.get("id");
    if (id == null || id.isNull()) {
      return null;
    }
    String method = message.path("method").asString("");
    JsonNode params = message.path("params");
    return switch (method) {
      case "initialize" -> result(id, initialize(params));
      case "ping" -> result(id, JSON.createObjectNode());
      case "tools/list" -> result(id, tools());
      case "tools/call" -> {
        JsonNode args = params.path("arguments");
        yield result(
            id,
            call(
                params.path("name").asString(""),
                args.isObject() ? args : JSON.createObjectNode()));
      }
      default -> error(id, -32601, "Método no admitido: " + method);
    };
  }

  private ObjectNode initialize(JsonNode params) {
    String requested = params.path("protocolVersion").asString("");
    ObjectNode result = JSON.createObjectNode();
    result.put("protocolVersion", PROTOCOLS.contains(requested) ? requested : DEFAULT_PROTOCOL);
    result.putObject("capabilities").putObject("tools").put("listChanged", false);
    result.putObject("serverInfo").put("name", name).put("version", version);
    result.put("instructions", instructions);
    return result;
  }

  /** Un resultado de texto. */
  protected static ObjectNode text(String text) {
    ObjectNode result = JSON.createObjectNode();
    result.putArray("content").addObject().put("type", "text").put("text", text);
    result.put("isError", false);
    return result;
  }

  /** Un resultado con error, que el agente ve como tal. */
  protected static ObjectNode failure(String message) {
    ObjectNode result = text(message);
    result.put("isError", true);
    return result;
  }

  /** El esquema vacío de las entradas de una herramienta. */
  protected static ObjectNode schema() {
    ObjectNode schema = JSON.createObjectNode();
    schema.put("type", "object");
    schema.putObject("properties");
    return schema;
  }

  protected static ObjectNode property(ObjectNode schema, String name, String type, String text) {
    ObjectNode property = ((ObjectNode) schema.get("properties")).putObject(name);
    property.put("type", type);
    property.put("description", text);
    return property;
  }

  /**
   * Una herramienta con sus anotaciones de MCP: las de lectura van con {@code readOnlyHint}, para
   * que el CLI las trate como de solo lectura (también en {@code permissionMode: plan}).
   */
  protected static void tool(
      ArrayNode tools, String name, String description, ObjectNode schema, boolean readOnly) {
    ObjectNode tool = tools.addObject().put("name", name).put("description", description);
    tool.set("inputSchema", schema);
    tool.putObject("annotations")
        .put("readOnlyHint", readOnly)
        .put("destructiveHint", false)
        .put("openWorldHint", false);
  }

  /** El {@code detail} de un ProblemDetail del control plane, o el cuerpo tal cual. */
  public static String problemDetail(String body) {
    try {
      String detail = JSON.readTree(body).path("detail").asString("");
      return detail.isBlank() ? body : detail;
    } catch (JacksonException e) {
      return body;
    }
  }

  private static ObjectNode result(JsonNode id, JsonNode result) {
    ObjectNode response = JSON.createObjectNode();
    response.put("jsonrpc", "2.0");
    response.set("id", id);
    response.set("result", result);
    return response;
  }

  private static ObjectNode error(JsonNode id, int code, String message) {
    ObjectNode response = JSON.createObjectNode();
    response.put("jsonrpc", "2.0");
    response.set("id", id);
    response.putObject("error").put("code", code).put("message", message);
    return response;
  }
}
