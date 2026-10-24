package dev.skynet.controlplane.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skynet.controlplane.support.IntegrationTest;
import dev.skynet.protocol.runner.RunnerRegistration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;

/** La memoria desde los agentes (ME-B): token por invocación, ámbito, límites y caducidad. */
class MemoryAgentIT extends IntegrationTest {

  @Test
  void anAgentSavesAndAnotherAgentOfTheSameProjectFindsIt() {
    Runner runner = register("laptop");
    Project alpha = project("ALPHA");
    JsonNode start = alpha.launchAndClaim(runner);
    assertThat(start.path("start").path("memory").asString()).isEqualTo("write");
    String first = token(runner, start);

    JsonNode saved =
        call(
            first,
            "save",
            Map.of(
                "kind", "DECISION",
                "title", "Fusionar con squash",
                "topicKey", "git/merge",
                "what", "Las PRs van con squash"));
    assertThat(saved.path("created").asBoolean()).isTrue();
    String id = saved.path("id").asString();
    call(
        first,
        "session-summary",
        Map.of(
            "goal",
            "Arreglar la CI",
            "accomplished",
            "Cambiado el workflow",
            "files",
            List.of("a.yml")));

    // Otra ejecución nueva del mismo proyecto la encuentra y la lee.
    JsonNode other = alpha.launchAndClaim(runner);
    String second = token(runner, other);
    JsonNode found = call(second, "search", Map.of("query", "squash"));
    assertThat(found.path("total").asLong()).isEqualTo(1);
    assertThat(found.path("results").get(0).path("id").asString()).isEqualTo(id);
    assertThat(found.path("results").get(0).path("scope").asString()).isEqualTo("repository");
    JsonNode full = call(second, "get", Map.of("id", id));
    assertThat(full.path("what").asString()).isEqualTo("Las PRs van con squash");
    assertThat(full.path("origin").asString()).isEqualTo("AGENT");

    JsonNode context = call(second, "context", Map.of());
    assertThat(context.path("text").asString())
        .startsWith(MemoryContext.HEADER)
        .contains("Fusionar con squash")
        .contains("Sesión: Arreglar la CI");
    assertThat(context.path("observations")).hasSize(2);

    // Procedencia: de qué invocación salió y qué leyó la otra.
    UUID firstAgent = agentId(start);
    assertThat(
            jdbc.sql("SELECT source_agent_run_id FROM memory_observation WHERE id = ?")
                .param(UUID.fromString(id))
                .query(UUID.class)
                .single())
        .isEqualTo(firstAgent);
    assertThat(
            jdbc.sql(
                    "SELECT via FROM memory_access WHERE agent_run_id = ? AND observation_id = ?"
                        + " ORDER BY id")
                .params(agentId(other), UUID.fromString(id))
                .query(String.class)
                .list())
        .containsExactly("PROMPT", "SEARCH", "GET", "CONTEXT");
  }

  @Test
  void anAgentOnlySeesItsProjectAndItsRepository() {
    Runner runner = register("laptop");
    Project alpha = project("ALPHA");
    Project beta = project("BETA");
    String otherRepo =
        post(
                "/api/projects/" + alpha.id() + "/repositories",
                Map.of("name", "web", "localPath", "/repos/alpha-web"))
            .path("id")
            .asString();
    String inBeta =
        post(
                "/api/projects/" + beta.id() + "/memory",
                entry(beta.repositoryId(), "Secreto de beta"))
            .path("id")
            .asString();
    String inOtherRepo =
        post("/api/projects/" + alpha.id() + "/memory", entry(otherRepo, "Del otro repo"))
            .path("id")
            .asString();
    String inProject =
        post("/api/projects/" + alpha.id() + "/memory", entry(null, "De todo alpha"))
            .path("id")
            .asString();

    String token = token(runner, alpha.launchAndClaim(runner));
    assertThat(call(token, "search", Map.of()).path("results"))
        .extracting(r -> r.path("id").asString())
        .containsExactly(inProject);
    assertStatus(() -> call(token, "get", Map.of("id", inBeta)), 404);
    assertStatus(() -> call(token, "get", Map.of("id", inOtherRepo)), 404);

    // Guardar en "project" lo deja para todo el proyecto.
    JsonNode saved =
        call(
            token,
            "save",
            Map.of("kind", "PATTERN", "title", "Común", "what", "x", "scope", "project"));
    assertThat(get("/api/memory/" + saved.path("id").asString()).path("repositoryId").isNull())
        .isTrue();
    assertStatus(
        () -> call(token, "save", Map.of("kind", "RUN_LOG", "title", "No", "what", "x")), 400);
    assertStatus(
        () -> call(token, "save", Map.of("kind", "SESSION_SUMMARY", "title", "No", "what", "x")),
        400);
  }

  @Test
  void theTokenStopsWorkingWhenTheInvocationFinishesOrIsReplaced() {
    Runner runner = register("laptop");
    Runner other = register("otro");
    Project alpha = project("ALPHA");
    JsonNode start = alpha.launchAndClaim(runner);
    String agent = start.path("agentRunId").asString();

    // Solo lo pide el runner que tiene la invocación.
    assertStatus(() -> issue(other, agent), 404);
    assertStatus(
        () ->
            http.post()
                .uri("/api/runner/agent-runs/" + agent + "/memory-token")
                .retrieve()
                .body(JsonNode.class),
        401);

    String first = token(runner, start);
    String second = token(runner, start);
    assertStatus(() -> call(first, "search", Map.of()), 401);
    call(second, "search", Map.of());
    assertStatus(() -> call("skm_inventado", "search", Map.of()), 401);
    assertStatus(
        () ->
            http.post()
                .uri("/api/runner/memory/search")
                .body(Map.of())
                .retrieve()
                .body(JsonNode.class),
        401);

    jdbc.sql("UPDATE agent_run SET status = 'COMPLETED' WHERE id = ?")
        .param(UUID.fromString(agent))
        .update();
    assertStatus(() -> call(second, "search", Map.of()), 401);
    assertStatus(() -> issue(runner, agent), 409);
    // El token solo se guarda como hash.
    assertThat(
            jdbc.sql("SELECT count(*) FROM memory_token WHERE token_hash = ?")
                .param(second)
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  void eachInvocationHasACallAndSaveLimitAndReadOnlyCannotSave() {
    Runner runner = register("laptop");
    Project alpha = project("ALPHA");
    JsonNode start = alpha.launchAndClaim(runner);
    String token = token(runner, start);
    UUID agent = agentId(start);

    jdbc.sql("UPDATE memory_token SET saves = 100 WHERE agent_run_id = ?").param(agent).update();
    assertStatus(
        () -> call(token, "save", Map.of("kind", "PATTERN", "title", "Uno más", "what", "x")), 429);

    jdbc.sql("UPDATE memory_token SET calls = 500 WHERE agent_run_id = ?").param(agent).update();
    assertStatus(() -> call(token, "search", Map.of()), 429);

    jdbc.sql("UPDATE memory_token SET calls = 0, saves = 0, mode = 'READ' WHERE agent_run_id = ?")
        .param(agent)
        .update();
    call(token, "search", Map.of());
    assertStatus(
        () -> call(token, "save", Map.of("kind", "PATTERN", "title", "No", "what", "x")), 403);
    assertStatus(
        () -> call(token, "session-summary", Map.of("goal", "g", "accomplished", "a")), 403);
  }

  @Test
  void aCommandHasNoMemory() {
    Runner runner = register("laptop");
    Project alpha = project("ALPHA");
    JsonNode start = alpha.launchAndClaim(runner);
    jdbc.sql("UPDATE agent_run SET provider = 'command' WHERE id = ?")
        .param(agentId(start))
        .update();
    assertStatus(() -> issue(runner, start.path("agentRunId").asString()), 409);
  }

  private record Runner(UUID id, String token) {}

  private record Project(String id, String repositoryId, String workItemId, MemoryAgentIT test) {
    /** Lanza una ejecución y la recoge el runner: devuelve su orden de arranque. */
    JsonNode launchAndClaim(Runner runner) {
      test.post(
          "/api/work-items/" + workItemId + "/runs",
          Map.of("repositoryId", repositoryId, "prompt", "haz algo"));
      JsonNode commands =
          test.http
              .get()
              .uri("/api/runner/commands?waitSeconds=1")
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + runner.token())
              .retrieve()
              .body(JsonNode.class);
      assertThat(commands).hasSize(1);
      test.http
          .post()
          .uri("/api/runner/commands/" + commands.get(0).path("id").asString() + "/ack")
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + runner.token())
          .retrieve()
          .toBodilessEntity();
      return commands.get(0);
    }
  }

  private Project project(String key) {
    String projectId = post("/api/projects", Map.of("key", key, "name", key)).path("id").asString();
    String repositoryId =
        post(
                "/api/projects/" + projectId + "/repositories",
                Map.of("name", "demo", "localPath", "/repos/" + key.toLowerCase()))
            .path("id")
            .asString();
    String workItemId =
        post("/api/projects/" + projectId + "/work-items", Map.of("title", "T", "type", "BUG"))
            .path("id")
            .asString();
    return new Project(projectId, repositoryId, workItemId, this);
  }

  private Runner register(String name) {
    JsonNode registered =
        http.post()
            .uri("/api/runner/register")
            .body(new RunnerRegistration(name, RUNNER_REGISTRATION_TOKEN, "dev", 2, "2.1.288"))
            .retrieve()
            .body(JsonNode.class);
    return new Runner(
        UUID.fromString(registered.path("runnerId").asString()),
        registered.path("token").asString());
  }

  private static UUID agentId(JsonNode command) {
    return UUID.fromString(command.path("agentRunId").asString());
  }

  private String token(Runner runner, JsonNode command) {
    JsonNode issued = issue(runner, command.path("agentRunId").asString());
    assertThat(issued.path("mode").asString()).isEqualTo("WRITE");
    return issued.path("token").asString();
  }

  private JsonNode issue(Runner runner, String agentRunId) {
    return http.post()
        .uri("/api/runner/agent-runs/" + agentRunId + "/memory-token")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + runner.token())
        .retrieve()
        .body(JsonNode.class);
  }

  private JsonNode call(String token, String tool, Map<String, ?> body) {
    return http.post()
        .uri("/api/runner/memory/" + tool)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .body(body)
        .retrieve()
        .body(JsonNode.class);
  }

  private static Map<String, Object> entry(String repositoryId, String title) {
    Map<String, Object> body = new HashMap<>();
    body.put("repositoryId", repositoryId);
    body.put("kind", "DISCOVERY");
    body.put("title", title);
    body.put("what", "Contenido de " + title);
    return body;
  }

  private static void assertStatus(Runnable call, int status) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            HttpClientErrorException.class,
            e -> assertThat(e.getStatusCode().value()).isEqualTo(status));
  }

  private JsonNode post(String path, Object body) {
    return http.post().uri(path).body(body).retrieve().body(JsonNode.class);
  }

  private JsonNode get(String path) {
    return http.get().uri(path).retrieve().body(JsonNode.class);
  }
}
