package dev.skynet.controlplane.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skynet.controlplane.support.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * La cola por repositorio (DS-C): las ejecuciones que pasan del límite esperan en {@code PENDING} y
 * entran por orden de llegada al liberarse un hueco.
 */
class RunQueueIT extends IntegrationTest {

  private String repositoryId;
  private String otherRepositoryId;
  private String workItemId;

  @BeforeEach
  void project() {
    String projectId =
        post("/api/projects", Map.of("key", "COL", "name", "Cola")).path("id").asString();
    repositoryId =
        post(
                "/api/projects/" + projectId + "/repositories",
                Map.of("name", "uno", "localPath", "/repos/uno"))
            .path("id")
            .asString();
    otherRepositoryId =
        post(
                "/api/projects/" + projectId + "/repositories",
                Map.of("name", "dos", "localPath", "/repos/dos"))
            .path("id")
            .asString();
    workItemId =
        post(
                "/api/projects/" + projectId + "/work-items",
                Map.of("title", "Hacer cosas", "type", "FEATURE"))
            .path("id")
            .asString();
  }

  @Test
  void runsOverTheLimitWaitAndStartInOrderWhenASlotFrees() {
    JsonNode queue = get("/api/repositories/" + repositoryId + "/run-queue");
    assertThat(queue.path("maxConcurrentRuns").asInt()).isEqualTo(50);
    assertThat(queue.path("custom").asBoolean()).isFalse();
    queue = put("/api/repositories/" + repositoryId + "/run-queue", Map.of("maxConcurrentRuns", 1));
    assertThat(queue.path("maxConcurrentRuns").asInt()).isEqualTo(1);
    assertThat(queue.path("custom").asBoolean()).isTrue();

    String first = launch(repositoryId, "Uno");
    String second = launch(repositoryId, "Dos");
    String third = launch(repositoryId, "Tres");
    assertThat(status(first)).isEqualTo("RUNNING");
    assertThat(status(second)).isEqualTo("PENDING");
    assertThat(status(third)).isEqualTo("PENDING");
    // Otro repositorio no espera.
    assertThat(status(launch(otherRepositoryId, "Otro"))).isEqualTo("RUNNING");

    queue = get("/api/repositories/" + repositoryId + "/run-queue");
    assertThat(queue.path("running").asInt()).isEqualTo(1);
    assertThat(queue.path("queued")).extracting(JsonNode::asString).containsExactly(second, third);
    assertThat(eventTypes(second)).contains("workflow.started", "workflow.queued");
    JsonNode position = get("/api/workflow-runs/" + third + "/queue");
    assertThat(position.path("position").asInt()).isEqualTo(2);
    assertThat(position.path("running").asInt()).isEqualTo(1);
    assertThat(position.path("maxConcurrentRuns").asInt()).isEqualTo(1);
    assertThat(get("/api/workflow-runs/" + first + "/queue").path("position").asInt()).isZero();
    JsonNode queued =
        jdbc.sql(
                "SELECT payload FROM event WHERE aggregate_id = ?::uuid"
                    + " AND event_type = 'workflow.queued'")
            .param(second)
            .query((rs, n) -> rs.getString(1))
            .optional()
            .map(this::json)
            .orElseThrow();
    assertThat(queued.path("running").asInt()).isEqualTo(1);
    assertThat(queued.path("limit").asInt()).isEqualTo(1);

    // Al terminar la primera, entra la segunda; la tercera sigue esperando.
    post("/api/workflow-runs/" + first + "/cancel", Map.of());
    await(() -> status(first).equals("CANCELLED") ? true : null, "la primera cancelada");
    await(() -> status(second).equals("RUNNING") ? true : null, "la segunda en marcha");
    assertThat(status(third)).isEqualTo("PENDING");
    assertThat(eventTypes(second)).contains("workflow.status.changed");

    // Cancelar una en cola la cierra sin que llegue a ocupar el hueco.
    post("/api/workflow-runs/" + third + "/cancel", Map.of());
    await(() -> status(third).equals("CANCELLED") ? true : null, "la tercera cancelada");
    assertThat(status(second)).isEqualTo("RUNNING");

    // Subir el límite deja entrar enseguida a las que esperan.
    String fourth = launch(repositoryId, "Cuatro");
    assertThat(status(fourth)).isEqualTo("PENDING");
    put("/api/repositories/" + repositoryId + "/run-queue", Map.of("maxConcurrentRuns", 2));
    await(() -> status(fourth).equals("RUNNING") ? true : null, "la cuarta en marcha");

    Map<String, Object> reset = new HashMap<>();
    reset.put("maxConcurrentRuns", null);
    assertThat(
            put("/api/repositories/" + repositoryId + "/run-queue", reset)
                .path("custom")
                .asBoolean())
        .isFalse();
    Integer status =
        http.put()
            .uri("/api/repositories/" + repositoryId + "/run-queue")
            .body(Map.of("maxConcurrentRuns", 0))
            .exchange((request, response) -> response.getStatusCode().value());
    assertThat(status).isEqualTo(400);
  }

  private String launch(String repository, String prompt) {
    return post(
            "/api/work-items/" + workItemId + "/runs",
            Map.of("repositoryId", repository, "prompt", prompt))
        .path("id")
        .asString();
  }

  private String status(String runId) {
    return get("/api/workflow-runs/" + runId).path("status").asString();
  }

  private List<String> eventTypes(String runId) {
    return jdbc.sql(
            "SELECT event_type FROM event WHERE workflow_run_id = ?::uuid ORDER BY sequence")
        .param(runId)
        .query(String.class)
        .list();
  }

  private JsonNode json(String text) {
    return tools.jackson.databind.json.JsonMapper.builder().build().readTree(text);
  }

  private JsonNode post(String path, Object body) {
    return http.post().uri(path).body(body).retrieve().body(JsonNode.class);
  }

  private JsonNode put(String path, Object body) {
    return http.put().uri(path).body(body).retrieve().body(JsonNode.class);
  }

  private JsonNode get(String path) {
    return http.get().uri(path).retrieve().body(JsonNode.class);
  }

  private static <T> T await(Supplier<T> probe, String what) {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
    while (Instant.now().isBefore(deadline)) {
      T value = probe.get();
      if (value != null) {
        return value;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError(e);
      }
    }
    throw new AssertionError("No llegó " + what);
  }
}
