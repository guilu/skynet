package dev.skynet.controlplane.chat;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skynet.controlplane.support.IntegrationTest;
import dev.skynet.protocol.AgentEventType;
import dev.skynet.protocol.NormalizedEvent;
import dev.skynet.protocol.runner.ChatTurn;
import dev.skynet.protocol.runner.EventBatch;
import dev.skynet.protocol.runner.ProviderInfo;
import dev.skynet.protocol.runner.RunnerRegistration;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * El turno de Skyny (CH-C): cada mensaje lo contesta un agente en un runner del chat, sin fase ni
 * repositorio; el primero empieza una sesión y los siguientes la continúan en el mismo runner.
 */
class ChatTurnsIT extends IntegrationTest {

  @Test
  void withoutAChatRunnerNobodyAnswers() {
    // Un runner que no sabe ejecutar turnos del chat no cuenta, ni recibe nada.
    Runner old = register("viejo", List.of(new ProviderInfo("claude-code", "2.1.288")));
    JsonNode created = post("/api/conversations", Map.of("message", "¿Qué hay en marcha?"));
    assertThat(created.path("conversation").path("state").asString()).isEqualTo("OFFLINE");
    assertThat(old.claim()).isEmpty();
    assertThat(count("SELECT count(*) FROM agent_run")).isZero();
  }

  @Test
  void eachMessageIsATurnThatAnswersInTheConversation() {
    Runner runner = chatRunner("portatil");
    String project =
        post("/api/projects", Map.of("key", "AKA", "name", "akademia")).path("id").asString();
    JsonNode created =
        post(
            "/api/conversations",
            Map.of("projectId", project, "message", "¿Qué hay en marcha en akademia?"));
    String conversation = created.path("conversation").path("id").asString();
    assertThat(created.path("conversation").path("state").asString()).isEqualTo("ANSWERING");

    // El primer turno: una sesión nueva, sin fase, ni ejecución, ni repositorio.
    JsonNode start = only(runner.claim());
    assertThat(start.path("type").asString()).isEqualTo("START");
    JsonNode order = start.path("start");
    assertThat(order.path("chat").path("conversationId").asString()).isEqualTo(conversation);
    assertThat(order.path("workflowRunId").isNull()).isTrue();
    assertThat(order.path("repositoryPath").isNull()).isTrue();
    assertThat(order.path("model").asString()).isEqualTo("haiku");
    assertThat(order.path("allowedTools").toString()).isEqualTo("[\"mcp__skynet\"]");
    assertThat(order.path("prompt").asString())
        .startsWith("Eres Skyny")
        .contains("AKA «akademia» (id " + project + ")")
        .endsWith("La persona te escribe:\n¿Qué hay en marcha en akademia?");
    String turn = start.path("agentRunId").asString();
    assertThat(
            jdbc.sql(
                    "SELECT role FROM agent_run WHERE id = ? AND stage_run_id IS NULL"
                        + " AND repository_id IS NULL")
                .param(UUID.fromString(turn))
                .query(String.class)
                .single())
        .isEqualTo("chat");

    // El runner pide el token de la conversación para el MCP de Skynet.
    String token = runner.chatToken(turn).path("token").asString();
    assertThat(token).startsWith(ChatTokens.PREFIX);
    assertThat(status(bearer(token), "/api/dashboard")).isEqualTo(200);

    // Mientras contesta, no se puede escribir otro mensaje.
    assertThat(postStatus("/api/conversations/" + conversation + "/messages", "Y otra cosa"))
        .isEqualTo(409);

    runner.answer(turn, "6f1c1d1e-0000-4000-8000-000000000001", "Hay dos ejecuciones en marcha.");
    JsonNode thread = get("/api/conversations/" + conversation);
    assertThat(thread.path("conversation").path("state").asString()).isEqualTo("IDLE");
    JsonNode reply = thread.path("messages").get(1);
    assertThat(reply.path("author").asString()).isEqualTo("SKYNY");
    assertThat(reply.path("body").asString()).isEqualTo("Hay dos ejecuciones en marcha.");
    // Al terminar el turno, su token deja de valer.
    assertThat(status(bearer(token), "/api/dashboard")).isEqualTo(401);

    // El segundo continúa la sesión en el mismo runner, solo con el mensaje nuevo.
    post("/api/conversations/" + conversation + "/messages", Map.of("body", "¿Y cuál va peor?"));
    JsonNode resume = only(runner.claim());
    assertThat(resume.path("type").asString()).isEqualTo("RESUME");
    assertThat(resume.path("start").path("resume").path("sessionId").asString())
        .isEqualTo("6f1c1d1e-0000-4000-8000-000000000001");
    assertThat(resume.path("start").path("prompt").asString()).isEqualTo("¿Y cuál va peor?");
    assertThat(
            jdbc.sql("SELECT kind, parent_agent_run_id FROM agent_run WHERE id = ?")
                .param(UUID.fromString(resume.path("agentRunId").asString()))
                .query((rs, n) -> rs.getString(1) + " " + rs.getString(2))
                .single())
        .isEqualTo("RESUME " + turn);
  }

  @Test
  void aFailedTurnSaysSoAndProposalsBecomeCards() {
    Runner runner = chatRunner("portatil");
    String conversation =
        post("/api/conversations", Map.of("message", "Crea un trabajo para exportar notas"))
            .path("conversation")
            .path("id")
            .asString();
    String project =
        post("/api/projects", Map.of("key", "EXP", "name", "exportar")).path("id").asString();
    JsonNode start = only(runner.claim());
    String turn = start.path("agentRunId").asString();
    String token = runner.chatToken(turn).path("token").asString();
    // Durante el turno, Skyny propone una acción con el token.
    bearer(token)
        .post()
        .uri("/api/runner/chat/actions")
        .body(
            Map.of(
                "kind",
                "CREATE_WORK_ITEM",
                "input",
                Map.of("projectId", project, "title", "Exportar notas")))
        .retrieve()
        .toBodilessEntity();
    runner.answer(
        turn,
        "6f1c1d1e-0000-4000-8000-000000000002",
        "Te lo dejo propuesto: confírmalo en la tarjeta.");
    JsonNode reply = get("/api/conversations/" + conversation).path("messages").get(1);
    assertThat(reply.path("cards")).hasSize(1);
    assertThat(reply.path("cards").get(0).path("kind").asString()).isEqualTo("action");
    assertThat(reply.path("cards").get(0).path("label").asString())
        .isEqualTo("Crear el trabajo «Exportar notas» (FEATURE) en EXP");

    // Un turno que falla deja dicho por qué, y la conversación sigue.
    post("/api/conversations/" + conversation + "/messages", Map.of("body", "¿Y ahora?"));
    String second = only(runner.claim()).path("agentRunId").asString();
    runner.fail(second, "Se agotó el tiempo máximo (PT5M)");
    JsonNode thread = get("/api/conversations/" + conversation);
    assertThat(thread.path("conversation").path("state").asString()).isEqualTo("IDLE");
    assertThat(thread.path("messages").get(3).path("body").asString())
        .isEqualTo("No he podido contestar: Se agotó el tiempo máximo (PT5M)");
  }

  @Test
  void aTurnCanBeStoppedAndOneNobodyClaimsDoesNotBlock() {
    Runner runner = chatRunner("portatil");
    String conversation =
        post("/api/conversations", Map.of("message", "Hola"))
            .path("conversation")
            .path("id")
            .asString();
    // Parado en cola: se cancela en el acto y lo dice.
    post("/api/conversations/" + conversation + "/stop", Map.of());
    JsonNode thread = get("/api/conversations/" + conversation);
    assertThat(thread.path("conversation").path("state").asString()).isEqualTo("IDLE");
    assertThat(thread.path("messages").get(1).path("body").asString())
        .isEqualTo("No he podido contestar: lo paraste antes de que empezara");
    assertThat(runner.claim()).isEmpty();

    // Uno que nadie reclama en dos minutos no bloquea el mensaje siguiente.
    post("/api/conversations/" + conversation + "/messages", Map.of("body", "¿Sigues ahí?"));
    jdbc.sql("UPDATE agent_run SET created_at = ? WHERE status = 'QUEUED'")
        .param(Timestamp.from(Instant.now().minus(Conversations.QUEUE_TIMEOUT).minusSeconds(1)))
        .update();
    post("/api/conversations/" + conversation + "/messages", Map.of("body", "¿Hola?"));
    thread = get("/api/conversations/" + conversation);
    assertThat(thread.path("messages"))
        .extracting(m -> m.path("body").asString())
        .containsExactly(
            "Hola",
            "No he podido contestar: lo paraste antes de que empezara",
            "¿Sigues ahí?",
            "No he podido contestar: ningún runner del chat lo recogió a tiempo",
            "¿Hola?");
    assertThat(thread.path("conversation").path("state").asString()).isEqualTo("ANSWERING");

    // En marcha: se le ordena parar y el fin llega con el proceso.
    JsonNode start = only(runner.claim());
    String turn = start.path("agentRunId").asString();
    runner.started(turn, "6f1c1d1e-0000-4000-8000-000000000003");
    post("/api/conversations/" + conversation + "/stop", Map.of());
    assertThat(runner.claim()).extracting(c -> c.path("type").asString()).containsExactly("CANCEL");
    runner.exited(turn, 143);
    thread = get("/api/conversations/" + conversation);
    assertThat(thread.path("conversation").path("state").asString()).isEqualTo("IDLE");
    assertThat(thread.path("messages").get(5).path("body").asString())
        .isEqualTo("No he podido contestar: lo paraste");
  }

  @Test
  void aTurnCountsInUsageAsChatWithSkyny() {
    Runner runner = chatRunner("portatil");
    post("/api/conversations", Map.of("message", "Hola"));
    String turn = only(runner.claim()).path("agentRunId").asString();
    runner.answer(turn, "6f1c1d1e-0000-4000-8000-000000000004", "Hola.");
    JsonNode agents = get("/api/usage/agents?period=24h");
    assertThat(agents)
        .anySatisfy(
            a -> {
              assertThat(a.path("workflowName").asString()).isEqualTo("Chat con Skyny");
              assertThat(a.path("agent").asString()).isEqualTo("skyny");
              assertThat(a.path("runs").asInt()).isEqualTo(1);
              assertThat(a.path("succeeded").asInt()).isEqualTo(1);
            });
    // Y no sale entre las ejecuciones de un workflow ni en «En curso».
    assertThat(get("/api/workflow-runs").path("total").asInt()).isZero();
  }

  @Test
  void onlyTheRunnerOfTheTurnGetsItsToken() {
    Runner runner = chatRunner("portatil");
    Runner other = chatRunner("sobremesa");
    post("/api/conversations", Map.of("message", "Hola"));
    List<JsonNode> claimed = new ArrayList<>(runner.claim());
    claimed.addAll(other.claim());
    JsonNode start = only(claimed);
    String turn = start.path("agentRunId").asString();
    Runner mine = runner.has(turn) ? runner : other;
    Runner notMine = mine == runner ? other : runner;
    assertThat(notMine.chatTokenStatus(turn)).isEqualTo(404);
    assertThat(mine.chatTokenStatus(UUID.randomUUID().toString())).isEqualTo(404);
    mine.answer(turn, "6f1c1d1e-0000-4000-8000-000000000005", "Hola.");
    // Terminado, ya no recibe token.
    assertThat(mine.chatTokenStatus(turn)).isEqualTo(404);
  }

  private static JsonNode only(List<JsonNode> commands) {
    assertThat(commands).hasSize(1);
    return commands.getFirst();
  }

  private Runner chatRunner(String name) {
    return register(
        name,
        List.of(
            new ProviderInfo("claude-code", "2.1.288"),
            new ProviderInfo(ChatTurn.RUNNER_FEATURE, "dev")));
  }

  private Runner register(String name, List<ProviderInfo> providers) {
    JsonNode registered =
        http.post()
            .uri("/api/runner/register")
            .body(
                new RunnerRegistration(
                    name, RUNNER_REGISTRATION_TOKEN, "dev", 2, "2.1.288", providers))
            .retrieve()
            .body(JsonNode.class);
    return new Runner(registered.path("token").asString());
  }

  private final class Runner {
    private final String token;
    private final List<String> assigned = new ArrayList<>();
    private final Map<String, Integer> seq = new LinkedHashMap<>();

    Runner(String token) {
      this.token = token;
    }

    boolean has(String turn) {
      return assigned.contains(turn);
    }

    List<JsonNode> claim() {
      JsonNode commands =
          http.get()
              .uri("/api/runner/commands?waitSeconds=1")
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
              .retrieve()
              .body(JsonNode.class);
      List<JsonNode> claimed = new ArrayList<>();
      for (JsonNode command : commands) {
        http.post()
            .uri("/api/runner/commands/" + command.path("id").asString() + "/ack")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .retrieve()
            .toBodilessEntity();
        assigned.add(command.path("agentRunId").asString());
        claimed.add(command);
      }
      return claimed;
    }

    JsonNode chatToken(String turn) {
      return http.post()
          .uri("/api/runner/agent-runs/" + turn + "/chat-token")
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
          .retrieve()
          .body(JsonNode.class);
    }

    int chatTokenStatus(String turn) {
      return http.post()
          .uri("/api/runner/agent-runs/" + turn + "/chat-token")
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
          .exchange((request, response) -> response.getStatusCode().value());
    }

    void started(String turn, String session) {
      send(turn, List.of(event(AgentEventType.SESSION_STARTED, Map.of("sessionId", session))));
    }

    void answer(String turn, String session, String text) {
      send(
          turn,
          List.of(
              event(AgentEventType.SESSION_STARTED, Map.of("sessionId", session)),
              event(
                  AgentEventType.RESULT,
                  Map.of(
                      "subtype",
                      "success",
                      "isError",
                      false,
                      "result",
                      text,
                      "costUsd",
                      0.002,
                      "usage",
                      Map.of("input_tokens", 1200, "output_tokens", 80))),
              event(AgentEventType.PROCESS_EXITED, Map.of("exitCode", 0))));
    }

    void fail(String turn, String error) {
      send(
          turn,
          List.of(
              event(
                  AgentEventType.SESSION_STARTED,
                  Map.of("sessionId", "6f1c1d1e-0000-4000-8000-0000000000ff")),
              event(
                  AgentEventType.PROCESS_EXITED,
                  Map.of("exitCode", 1, "error", error, "failure", "timeout"))));
    }

    void exited(String turn, int code) {
      send(turn, List.of(event(AgentEventType.PROCESS_EXITED, Map.of("exitCode", code))));
    }

    private Map.Entry<AgentEventType, Map<String, Object>> event(
        AgentEventType type, Map<String, Object> payload) {
      return Map.entry(type, payload);
    }

    private void send(String turn, List<Map.Entry<AgentEventType, Map<String, Object>>> events) {
      UUID id = UUID.fromString(turn);
      List<NormalizedEvent> batch = new ArrayList<>();
      for (Map.Entry<AgentEventType, Map<String, Object>> e : events) {
        int next = seq.merge(turn, 1, Integer::sum);
        batch.add(
            new NormalizedEvent(
                UUID.randomUUID(),
                id,
                next,
                Instant.now(),
                e.getKey(),
                new LinkedHashMap<>(e.getValue())));
      }
      http.post()
          .uri("/api/runner/events")
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
          .body(new EventBatch(batch))
          .retrieve()
          .toBodilessEntity();
    }
  }

  private int count(String sql) {
    return jdbc.sql(sql).query(Integer.class).single();
  }

  private RestClient bearer(String token) {
    return RestClient.builder()
        .baseUrl("http://localhost:" + port)
        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .build();
  }

  private static int status(RestClient client, String path) {
    return client
        .method(HttpMethod.GET)
        .uri(path)
        .exchange((request, response) -> response.getStatusCode().value());
  }

  private int postStatus(String path, String body) {
    return http.post()
        .uri(path)
        .body(Map.of("body", body))
        .exchange((request, response) -> response.getStatusCode().value());
  }

  private JsonNode post(String path, Object body) {
    return http.post().uri(path).body(body).retrieve().body(JsonNode.class);
  }

  private JsonNode get(String path) {
    return http.get().uri(path).retrieve().body(JsonNode.class);
  }
}
