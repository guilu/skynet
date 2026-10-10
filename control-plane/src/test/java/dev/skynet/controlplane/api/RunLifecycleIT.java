package dev.skynet.controlplane.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skynet.controlplane.support.IntegrationTest;
import dev.skynet.controlplane.support.SseClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;

/** Recorre por HTTP los criterios del MVP 1 y 2 y el ciclo de vida de una ejecución en cola. */
class RunLifecycleIT extends IntegrationTest {

  @Test
  void registersRepositoryCreatesWorkItemsAndLaunchesARun() throws Exception {
    JsonNode project = post("/api/projects", Map.of("key", "TKM", "name", "TokenMeter"));
    String projectId = project.path("id").asString();
    JsonNode repo =
        post(
            "/api/projects/" + projectId + "/repositories",
            Map.of("name", "tokenmeter", "localPath", "/repos/tokenmeter"));
    assertThat(repo.path("defaultBranch").asString()).isEqualTo("main");

    JsonNode first =
        post(
            "/api/projects/" + projectId + "/work-items",
            Map.of("title", "Add model pricing importer", "type", "FEATURE"));
    JsonNode second =
        post(
            "/api/projects/" + projectId + "/work-items",
            Map.of("title", "Fix cache invalidation", "type", "BUG"));
    assertThat(first.path("key").asString()).isEqualTo("TKM-1");
    assertThat(second.path("key").asString()).isEqualTo("TKM-2");
    assertThat(get("/api/projects/" + projectId + "/work-items"))
        .extracting(n -> n.path("key").asString())
        .containsExactly("TKM-2", "TKM-1");

    String workItemId = first.path("id").asString();
    try (SseClient sse = new SseClient(baseUrl() + "/api/events/stream?after=0", null)) {
      JsonNode run =
          post(
              "/api/work-items/" + workItemId + "/runs",
              Map.of("repositoryId", repo.path("id").asString(), "prompt", "Implementa X"));

      assertThat(run.path("status").asString()).isEqualTo("RUNNING");
      JsonNode stage = run.path("stages").get(0);
      assertThat(stage.path("stageKey").asString()).isEqualTo("agent");
      assertThat(stage.path("status").asString()).isEqualTo("READY");
      JsonNode agent = stage.path("agents").get(0);
      assertThat(agent.path("status").asString()).isEqualTo("QUEUED");
      assertThat(agent.path("kind").asString()).isEqualTo("START");

      // project.created, repository.registered, 2× workitem.created, workflow.started,
      // stage.pending, stage.ready, agent.spawned
      List<SseClient.Message> messages = sse.await(8, Duration.ofSeconds(10));
      assertThat(messages)
          .extracting(m -> m.data().path("type").asString())
          .containsExactly(
              "project.created",
              "repository.registered",
              "workitem.created",
              "workitem.created",
              "workflow.started",
              "stage.pending",
              "stage.ready",
              "agent.spawned");

      String agentId = agent.path("id").asString();
      JsonNode detail = get("/api/agent-runs/" + agentId);
      assertThat(detail.path("prompts").get(0).path("content").asString())
          .isEqualTo("Implementa X");
      assertThat(detail.path("prompts").get(0).path("sha256").asString()).hasSize(64);
    }
  }

  @Test
  void cancellingAQueuedAgentCancelsItsStageAndRun() {
    Fixture f = fixture();
    JsonNode run = launch(f);
    String runId = run.path("id").asString();
    String agentId = run.path("stages").get(0).path("agents").get(0).path("id").asString();

    JsonNode cancelled = post("/api/agent-runs/" + agentId + "/cancel", Map.of());
    assertThat(cancelled.path("agent").path("status").asString()).isEqualTo("CANCELLED");
    assertThat(cancelled.path("agent").path("finishedAt").isNull()).isFalse();

    JsonNode after = get("/api/workflow-runs/" + runId);
    assertThat(after.path("status").asString()).isEqualTo("CANCELLED");
    assertThat(after.path("stages").get(0).path("status").asString()).isEqualTo("CANCELLED");

    List<JsonNode> events = getList("/api/events?workflowRunId=" + runId);
    assertThat(events)
        .extracting(e -> e.path("type").asString())
        .containsExactly(
            "workflow.started",
            "stage.pending",
            "stage.ready",
            "agent.spawned",
            "agent.status.changed",
            "stage.status.changed",
            "workflow.status.changed");
    assertThat(events.get(4).path("payload").path("previousStatus").asString()).isEqualTo("QUEUED");

    // Un agente cancelado no admite más transiciones.
    assertStatus(
        HttpStatus.CONFLICT, () -> post("/api/agent-runs/" + agentId + "/cancel", Map.of()));
  }

  @Test
  void rejectsInvalidRequests() {
    assertStatus(
        HttpStatus.BAD_REQUEST,
        () -> post("/api/projects", Map.of("key", "lowercase", "name", "x")));
    post("/api/projects", Map.of("key", "DUP", "name", "x"));
    assertStatus(
        HttpStatus.CONFLICT, () -> post("/api/projects", Map.of("key", "DUP", "name", "y")));
    assertStatus(HttpStatus.NOT_FOUND, () -> get("/api/projects/" + UUID.randomUUID()));
    assertStatus(
        HttpStatus.BAD_REQUEST,
        () ->
            post(
                "/api/projects/" + UUID.randomUUID() + "/repositories",
                Map.of("name", "r", "localPath", "relative/path")));

    // El repositorio debe pertenecer al proyecto del trabajo.
    Fixture f = fixture();
    JsonNode other = post("/api/projects", Map.of("key", "OTH", "name", "Other"));
    JsonNode foreignRepo =
        post(
            "/api/projects/" + other.path("id").asString() + "/repositories",
            Map.of("name", "foreign", "localPath", "/repos/foreign"));
    assertStatus(
        HttpStatus.CONFLICT,
        () ->
            post(
                "/api/work-items/" + f.workItemId + "/runs",
                Map.of("repositoryId", foreignRepo.path("id").asString(), "prompt", "x")));
  }

  private record Fixture(String projectId, String repositoryId, String workItemId) {}

  private Fixture fixture() {
    String projectId =
        post("/api/projects", Map.of("key", "FIX", "name", "F")).path("id").asString();
    String repositoryId =
        post(
                "/api/projects/" + projectId + "/repositories",
                Map.of("name", "repo", "localPath", "/repos/fix"))
            .path("id")
            .asString();
    String workItemId =
        post("/api/projects/" + projectId + "/work-items", Map.of("title", "T", "type", "REFACTOR"))
            .path("id")
            .asString();
    return new Fixture(projectId, repositoryId, workItemId);
  }

  private JsonNode launch(Fixture f) {
    return post(
        "/api/work-items/" + f.workItemId + "/runs",
        Map.of("repositoryId", f.repositoryId, "prompt", "Haz algo"));
  }

  private JsonNode post(String path, Object body) {
    return http.post().uri(path).body(body).retrieve().body(JsonNode.class);
  }

  private JsonNode get(String path) {
    return http.get().uri(path).retrieve().body(JsonNode.class);
  }

  private List<JsonNode> getList(String path) {
    List<JsonNode> list = new java.util.ArrayList<>();
    get(path).forEach(list::add);
    return list;
  }

  private String baseUrl() {
    return "http://localhost:" + port;
  }

  private static void assertStatus(HttpStatus expected, Runnable call) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            HttpClientErrorException.class,
            e -> assertThat(e.getStatusCode().value()).isEqualTo(expected.value()));
  }
}
