package dev.skynet.runner.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class MemoryMcpServerTest {

  private static final ObjectMapper JSON = JsonMapper.builder().build();

  private final List<String> operations = new ArrayList<>();
  private final List<ObjectNode> bodies = new ArrayList<>();

  private final MemoryMcpServer.Api api =
      (operation, body) -> {
        operations.add(operation);
        bodies.add(body);
        return switch (operation) {
          case "context" -> JSON.readTree("{\"text\":\"Memoria del proyecto…\"}");
          case "get" -> throw new MemoryMcpServer.ApiException("La memoria respondió 404: no");
          default -> JSON.readTree("{\"id\":\"x\",\"version\":2,\"created\":false}");
        };
      };

  @Test
  void answersInitializeWithTheRequestedProtocolAndInstructions() throws Exception {
    JsonNode reply =
        server(true)
            .handle(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
                    + "{\"protocolVersion\":\"2024-11-05\"}}");
    assertThat(reply.path("id").asInt()).isEqualTo(1);
    assertThat(reply.path("result").path("protocolVersion").asString()).isEqualTo("2024-11-05");
    assertThat(reply.path("result").path("capabilities").has("tools")).isTrue();
    assertThat(reply.path("result").path("instructions").asString()).contains("mem_context");
    // Una versión que no conoce: la suya.
    assertThat(
            server(true)
                .handle(
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\",\"params\":"
                        + "{\"protocolVersion\":\"1999-01-01\"}}")
                .path("result")
                .path("protocolVersion")
                .asString())
        .isEqualTo(MemoryMcpServer.DEFAULT_PROTOCOL);
  }

  @Test
  void readOnlyOffersNoToolThatSaves() throws Exception {
    assertThat(names(server(true).tools()))
        .containsExactly("mem_context", "mem_search", "mem_get", "mem_save", "mem_session_summary");
    assertThat(names(server(false).tools()))
        .containsExactly("mem_context", "mem_search", "mem_get");
    // Las de lectura se anuncian como tales, para que el CLI las deje usar en modo plan.
    assertThat(server(true).tools().path("tools"))
        .extracting(
            t -> t.path("name").asString() + "=" + t.path("annotations").path("readOnlyHint"))
        .containsExactly(
            "mem_context=true",
            "mem_search=true",
            "mem_get=true",
            "mem_save=false",
            "mem_session_summary=false");

    JsonNode refused = call(server(false), "mem_save", "{\"kind\":\"DECISION\"}");
    assertThat(refused.path("isError").asBoolean()).isTrue();
    assertThat(operations).isEmpty();
  }

  @Test
  void toolsCallTheControlPlaneWithEngramStyleArguments() throws Exception {
    MemoryMcpServer server = server(true);

    JsonNode context = call(server, "mem_context", "{}");
    assertThat(text(context)).isEqualTo("Memoria del proyecto…");

    JsonNode saved =
        call(
            server,
            "mem_save",
            "{\"kind\":\"DECISION\",\"title\":\"t\",\"what\":\"w\",\"topic_key\":\"a/b\","
                + "\"inventado\":1}");
    assertThat(saved.path("isError").asBoolean()).isFalse();
    assertThat(text(saved)).contains("\"version\" : 2");
    call(
        server,
        "mem_session_summary",
        "{\"goal\":\"g\",\"accomplished\":\"a\",\"next_steps\":\"n\"}");

    assertThat(operations).containsExactly("context", "save", "session-summary");
    assertThat(bodies.get(1).path("topicKey").asString()).isEqualTo("a/b");
    assertThat(bodies.get(1).has("topic_key")).isFalse();
    assertThat(bodies.get(1).has("inventado")).isFalse();
    assertThat(bodies.get(2).path("nextSteps").asString()).isEqualTo("n");
  }

  @Test
  void anErrorOfTheControlPlaneIsAToolErrorNotAProtocolError() throws Exception {
    JsonNode reply = call(server(true), "mem_get", "{\"id\":\"x\"}");
    assertThat(reply.path("isError").asBoolean()).isTrue();
    assertThat(text(reply)).contains("404");
    assertThat(text(call(server(true), "mem_otra", "{}"))).contains("desconocida");
  }

  @Test
  void notificationsGetNoReplyAndUnknownMethodsAnError() throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    String input =
        String.join(
            "\n",
            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
            "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"resources/list\"}",
            "no es json",
            "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"ping\"}");
    server(true)
        .serve(
            new BufferedReader(new StringReader(input)),
            new PrintStream(bytes, true, StandardCharsets.UTF_8));
    List<JsonNode> replies =
        bytes.toString(StandardCharsets.UTF_8).lines().map(JSON::readTree).toList();
    assertThat(replies).hasSize(3);
    assertThat(replies.get(0).path("error").path("code").asInt()).isEqualTo(-32601);
    assertThat(replies.get(1).path("error").path("code").asInt()).isEqualTo(-32700);
    assertThat(replies.get(2).path("id").asInt()).isEqualTo(8);
    assertThat(replies.get(2).has("result")).isTrue();
  }

  private MemoryMcpServer server(boolean writable) {
    return new MemoryMcpServer(api, writable, "test");
  }

  private static JsonNode call(MemoryMcpServer server, String tool, String arguments)
      throws Exception {
    return server
        .handle(
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\""
                + tool
                + "\",\"arguments\":"
                + arguments
                + "}}")
        .path("result");
  }

  private static String text(JsonNode result) {
    return result.path("content").get(0).path("text").asString();
  }

  private static List<String> names(JsonNode tools) {
    List<String> names = new ArrayList<>();
    tools.path("tools").forEach(t -> names.add(t.path("name").asString()));
    return names;
  }
}
