package dev.skynet.runner.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class SkynetMcpServerTest {

  private static final ObjectMapper JSON = JsonMapper.builder().build();
  private static final String ID = "6f1c1d1e-0000-4000-8000-000000000001";

  private final List<String> calls = new ArrayList<>();

  private final SkynetMcpServer.Api api =
      (path, query) -> {
        calls.add(path + (query.isEmpty() ? "" : " " + new java.util.TreeMap<>(query)));
        return switch (path) {
          case "/api/projects" ->
              JSON.readTree(
                  "[{\"id\":\"p1\",\"key\":\"AKA\",\"name\":\"akademia\"},"
                      + "{\"id\":\"p2\",\"key\":\"OTR\",\"name\":\"otro\"}]");
          case "/api/work-items" ->
              JSON.readTree(
                  "[{\"id\":\"w1\",\"key\":\"AKA-1\",\"title\":\"Exportar notas\"},"
                      + "{\"id\":\"w2\",\"key\":\"OTR-1\",\"title\":\"Login\"}]");
          case "/api/workflow-runs" -> JSON.readTree("{\"items\":[{\"id\":\"r1\"}],\"total\":1}");
          case "/api/memory/" + ID ->
              throw new SkynetMcpServer.ToolException("Skynet respondió 404: no");
          case "/api/dashboard" -> JSON.readTree("{\"big\":\"" + "x".repeat(40_000) + "\"}");
          default -> JSON.readTree("{\"ok\":true}");
        };
      };

  private final SkynetMcpServer server = new SkynetMcpServer(api, "1.0");

  @Test
  void offersOnlyReadOnlyTools() {
    JsonNode tools = server.tools().path("tools");
    assertThat(tools)
        .extracting(t -> t.path("name").asString())
        .containsExactly(
            "estado",
            "buscar",
            "proyecto",
            "trabajo",
            "ejecuciones",
            "ejecucion",
            "aprobaciones",
            "workflows",
            "workflow",
            "uso",
            "memoria_buscar",
            "memoria_leer",
            "wiki");
    assertThat(tools)
        .allSatisfy(
            t -> assertThat(t.path("annotations").path("readOnlyHint").asBoolean()).isTrue());
  }

  @Test
  void answersInitializeWithItsInstructions() throws Exception {
    JsonNode reply =
        server.handle(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
                + "{\"protocolVersion\":\"2024-11-05\"}}");
    assertThat(reply.path("result").path("protocolVersion").asString()).isEqualTo("2024-11-05");
    assertThat(reply.path("result").path("serverInfo").path("name").asString()).isEqualTo("skynet");
    assertThat(reply.path("result").path("instructions").asString()).contains("no instrucciones");
    // Una notificación no tiene respuesta.
    assertThat(server.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
        .isNull();
  }

  @Test
  void eachToolCallsTheApi() throws Exception {
    call("trabajo", Map.of("id", ID));
    call("ejecucion", Map.of("id", ID));
    call("ejecuciones", Map.of("estado", "failed", "proyecto", ID, "texto", "AKA"));
    call("aprobaciones", Map.of());
    call("uso", Map.of("periodo", "30d"));
    call("memoria_buscar", Map.of("proyecto", ID, "texto", "auth"));
    call("wiki", Map.of("proyecto", ID));
    call("wiki", Map.of("proyecto", ID, "pagina", "lo-hecho"));
    call("workflow", Map.of("clave", "implementar-tarea"));
    assertThat(calls)
        .containsExactly(
            "/api/work-items/" + ID + "/summary",
            "/api/work-items/" + ID + "/runs",
            "/api/workflow-runs/" + ID,
            "/api/workflow-runs/" + ID + "/findings",
            "/api/workflow-runs {projectId=" + ID + ", q=AKA, size=10, status=FAILED}",
            "/api/approvals {status=PENDING}",
            "/api/usage/windows",
            "/api/usage/agents {period=30d}",
            "/api/projects/" + ID + "/memory {q=auth, size=20}",
            "/api/projects/" + ID + "/wiki",
            "/api/projects/" + ID + "/wiki/lo-hecho",
            "/api/workflows/implementar-tarea");
  }

  @Test
  void searchFindsProjectsWorkItemsAndRuns() throws Exception {
    JsonNode result = call("buscar", Map.of("texto", "aka"));
    assertThat(result.path("isError").asBoolean()).isFalse();
    JsonNode body = JSON.readTree(text(result));
    assertThat(body.path("proyectos"))
        .extracting(p -> p.path("key").asString())
        .containsExactly("AKA");
    assertThat(body.path("trabajos"))
        .extracting(w -> w.path("key").asString())
        .containsExactly("AKA-1");
    assertThat(body.path("ejecuciones")).hasSize(1);
    assertThat(calls).contains("/api/workflow-runs {q=aka, size=10}");
  }

  @Test
  void nothingUnsafeReachesThePath() throws Exception {
    // Un id que no es un id, una clave con barras o un estado inventado no llegan a la API.
    assertThat(text(call("ejecucion", Map.of("id", "../runners")))).contains("no es un id");
    assertThat(text(call("workflow", Map.of("clave", "../../runners")))).contains("no vale");
    assertThat(text(call("wiki", Map.of("proyecto", ID, "pagina", "a/b")))).contains("no vale");
    assertThat(text(call("ejecuciones", Map.of("estado", "BORRADO")))).contains("desconocido");
    assertThat(text(call("uso", Map.of("periodo", "1y")))).contains("24h, 7d o 30d");
    assertThat(text(call("buscar", Map.of()))).contains("Falta texto");
    assertThat(call("trabajo", Map.of()).path("isError").asBoolean()).isTrue();
    assertThat(calls).isEmpty();
  }

  @Test
  void errorsAndLongAnswersComeBackReadable() throws Exception {
    JsonNode missing = call("memoria_leer", Map.of("id", ID));
    assertThat(missing.path("isError").asBoolean()).isTrue();
    assertThat(text(missing)).isEqualTo("Skynet respondió 404: no");
    assertThat(text(call("desconocida", Map.of()))).contains("Herramienta desconocida");

    String state = text(call("estado", Map.of()));
    assertThat(state).endsWith("(recortado: pide algo más concreto)");
    assertThat(state.length()).isLessThan(SkynetMcpServer.MAX_TEXT + 100);
  }

  @Test
  void encodesTheQuery() {
    assertThat(SkynetMcpMain.HttpApi.queryString(Map.of())).isEmpty();
    assertThat(SkynetMcpMain.HttpApi.queryString(Map.of("q", "año & más")))
        .isEqualTo("?q=a%C3%B1o+%26+m%C3%A1s");
  }

  private JsonNode call(String tool, Map<String, String> arguments) throws Exception {
    String request =
        JSON.writeValueAsString(
            Map.of(
                "jsonrpc",
                "2.0",
                "id",
                7,
                "method",
                "tools/call",
                "params",
                Map.of("name", tool, "arguments", arguments)));
    return server.handle(request).path("result");
  }

  private static String text(JsonNode result) {
    return result.path("content").get(0).path("text").asString();
  }
}
