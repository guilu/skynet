package dev.skynet.controlplane.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skynet.controlplane.support.IntegrationTest;
import dev.skynet.protocol.AgentEventType;
import dev.skynet.protocol.NormalizedEvent;
import dev.skynet.protocol.runner.ArtifactType;
import dev.skynet.protocol.runner.ArtifactUpload;
import dev.skynet.protocol.runner.EventBatch;
import dev.skynet.protocol.runner.RunnerRegistration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Eliminar lo archivado (AE-B): todo o nada, con lápida, y sin tocar lo que no se elimina. */
class PurgeIT extends IntegrationTest {

  @Test
  void deletingARunRemovesEverythingBelowAndLeavesATombstone() {
    Runner runner = register("laptop");
    Project alpha = project("ALPHA");
    Launched run = alpha.launch("uno");
    start(runner);
    complete(runner, run.agentId(), "/w/alpha/a1");
    byte[] diff = ("diff de " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
    upload(runner, run.agentId(), "changes.diff", diff);
    Path blob = blobPath(diff);
    assertThat(blob).exists();

    assertConflict(() -> delete("/api/workflow-runs/" + run.runId()), "no está archivada");
    post("/api/workflow-runs/" + run.runId() + "/archive");

    // El worktree sigue en el runner: hay que eliminarlo antes.
    JsonNode preview = get("/api/workflow-runs/" + run.runId() + "/deletion-preview");
    assertThat(preview.path("deletable").asBoolean()).isFalse();
    assertThat(preview.path("liveWorkspaces").asInt()).isEqualTo(1);
    assertConflict(() -> delete("/api/workflow-runs/" + run.runId()), "worktree");

    assertThat(post("/api/workflow-runs/" + run.runId() + "/workspaces/cleanup").path("requested"))
        .hasToString("1");
    // Mientras se elimina, sigue impidiéndolo.
    assertThat(
            get("/api/workflow-runs/" + run.runId() + "/deletion-preview")
                .path("deletable")
                .asBoolean())
        .isFalse();
    JsonNode cleanup = poll(runner).getFirst();
    assertThat(cleanup.path("type").asString()).isEqualTo("CLEANUP");
    ack(runner, cleanup);
    send(
        runner,
        run.agentId(),
        AgentEventType.WORKSPACE_REMOVED,
        Map.of(
            "workspaceId", cleanup.path("cleanup").path("workspaceId").asString(), "path", "/w"));

    preview = get("/api/workflow-runs/" + run.runId() + "/deletion-preview");
    assertThat(preview.path("deletable").asBoolean()).isTrue();
    assertThat(preview.path("blockers")).isEmpty();
    JsonNode counts = preview.path("counts");
    assertThat(counts.path("runs").asLong()).isEqualTo(1);
    assertThat(counts.path("agents").asLong()).isEqualTo(1);
    assertThat(counts.path("artifacts").asLong()).isEqualTo(1);
    assertThat(counts.path("artifactBytes").asLong()).isEqualTo(diff.length);
    assertThat(counts.path("events").asLong()).isPositive();

    JsonNode deleted = delete("/api/workflow-runs/" + run.runId());
    assertThat(deleted.path("runs").asLong()).isEqualTo(1);

    assertNotFound(() -> get("/api/workflow-runs/" + run.runId()));
    assertNotFound(() -> get("/api/agent-runs/" + run.agentId()));
    for (String table :
        List.of(
            "workflow_run",
            "stage_run",
            "agent_run",
            "prompt",
            "artifact",
            "runner_command",
            "workspace")) {
      assertThat(rows(table)).as(table).isZero();
    }
    assertThat(
            jdbc.sql("SELECT count(*) FROM event WHERE workflow_run_id IS NOT NULL")
                .query(Long.class)
                .single())
        .isZero();
    // El trabajo, el proyecto y el runner se quedan.
    assertThat(get("/api/work-items/" + alpha.workItemId()).path("key").asString())
        .isEqualTo("ALPHA-1");
    JsonNode tombstone = lastEvent("workflow.purged");
    assertThat(tombstone.path("aggregateId").asString()).isEqualTo(run.runId().toString());
    assertThat(tombstone.path("payload").path("workItemKey").asString()).isEqualTo("ALPHA-1");
    assertThat(tombstone.path("payload").path("counts").path("artifacts").asLong()).isEqualTo(1);
    assertThat(blob).doesNotExist();
  }

  @Test
  void aSharedBlobAndContinuedSessionsSurvive() {
    Runner runner = register("laptop");
    Project alpha = project("ALPHA");
    Launched first = alpha.launch("uno");
    start(runner);
    complete(runner, first.agentId(), null);
    // El reintento es otra ejecución que parte de la primera y sube el mismo contenido.
    Launched retry = launched(post("/api/agent-runs/" + first.agentId() + "/retry"));
    start(runner);
    complete(runner, retry.agentId(), null);
    byte[] same = ("compartido " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
    upload(runner, first.agentId(), "log.txt", same);
    upload(runner, retry.agentId(), "log.txt", same);

    post("/api/workflow-runs/" + first.runId() + "/archive");
    JsonNode preview = get("/api/workflow-runs/" + first.runId() + "/deletion-preview");
    assertThat(preview.path("deletable").asBoolean()).isTrue();
    assertThat(preview.path("warnings").get(0).asString()).contains("pierde el enlace");
    delete("/api/workflow-runs/" + first.runId());

    JsonNode survivor = get("/api/agent-runs/" + retry.agentId()).path("agent");
    assertThat(survivor.path("status").asString()).isEqualTo("COMPLETED");
    assertThat(survivor.path("parentAgentRunId").isNull()).isTrue();
    assertThat(blobPath(same)).exists();
    assertThat(get("/api/agent-runs/" + retry.agentId() + "/artifacts")).hasSize(1);
  }

  @Test
  void deletingAProjectRemovesItsWorkItemsRunsAndRepositories() {
    Runner runner = register("laptop");
    Project alpha = project("ALPHA");
    Project beta = project("BETA");
    Launched run = alpha.launch("uno");
    start(runner);
    complete(runner, run.agentId(), null);
    beta.launch("otra");

    assertConflict(() -> delete("/api/projects/" + alpha.id()), "no está archivado");
    post("/api/projects/" + alpha.id() + "/archive");
    JsonNode counts = get("/api/projects/" + alpha.id() + "/deletion-preview").path("counts");
    assertThat(counts.path("repositories").asLong()).isEqualTo(1);
    assertThat(counts.path("workItems").asLong()).isEqualTo(1);
    assertThat(counts.path("runs").asLong()).isEqualTo(1);

    delete("/api/projects/" + alpha.id());

    assertNotFound(() -> get("/api/projects/" + alpha.id()));
    assertNotFound(() -> get("/api/work-items/" + alpha.workItemId()));
    assertThat(keys(get("/api/projects?archived=all"))).containsExactly("BETA");
    assertThat(get("/api/workflow-runs").path("total").asLong()).isEqualTo(1);
    assertThat(lastEvent("project.purged").path("payload").path("key").asString())
        .isEqualTo("ALPHA");
    assertThat(
            jdbc.sql("SELECT count(*) FROM event WHERE aggregate_id = ? AND event_type <> ?")
                .params(UUID.fromString(alpha.id()), "project.purged")
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  void aWorkItemIsDeletedWithItsRuns() {
    Project alpha = project("ALPHA");
    Launched run = alpha.launch("uno");
    post("/api/agent-runs/" + run.agentId() + "/cancel");
    post("/api/work-items/" + alpha.workItemId() + "/archive");

    delete("/api/work-items/" + alpha.workItemId());

    assertNotFound(() -> get("/api/work-items/" + alpha.workItemId()));
    assertNotFound(() -> get("/api/workflow-runs/" + run.runId()));
    assertThat(get("/api/projects/" + alpha.id()).path("key").asString()).isEqualTo("ALPHA");
    assertThat(lastEvent("workitem.purged").path("payload").path("key").asString())
        .isEqualTo("ALPHA-1");
  }

  @Test
  void repositoriesAndRunnersWithHistoryStayArchived() {
    Runner used = register("used");
    Project alpha = project("ALPHA");
    Launched run = alpha.launch("uno");
    start(used);
    complete(used, run.agentId(), null);
    String repositories = "/api/projects/" + alpha.id() + "/repositories";

    post(repositories + "/" + alpha.repositoryId() + "/archive");
    assertConflict(() -> delete(repositories + "/" + alpha.repositoryId()), "se queda archivado");
    post("/api/runners/" + used.id() + "/archive");
    assertConflict(() -> delete("/api/runners/" + used.id()), "se queda olvidado");

    String unused =
        post(repositories, Map.of("name", "otro", "localPath", "/repos/otro"))
            .path("id")
            .asString();
    post(repositories + "/" + unused + "/archive");
    delete(repositories + "/" + unused);
    assertThat(get(repositories + "?archived=all")).hasSize(1);

    Runner gone = register("gone");
    assertConflict(() -> delete("/api/runners/" + gone.id()), "no está olvidado");
    post("/api/runners/" + gone.id() + "/archive");
    delete("/api/runners/" + gone.id());
    assertThat(names(get("/api/runners?archived=all"))).containsExactly("used");
    assertThat(lastEvent("runner.purged").path("payload").path("name").asString())
        .isEqualTo("gone");
    assertThat(lastEvent("repository.purged").path("payload").path("name").asString())
        .isEqualTo("otro");
  }

  @Test
  void outsideAPurgeTheHistoryCannotBeChanged() {
    project("ALPHA");
    assertThatThrownBy(() -> jdbc.sql("DELETE FROM event").update())
        .hasMessageContaining("append-only");
    assertThatThrownBy(() -> jdbc.sql("UPDATE event SET event_type = 'x'").update())
        .hasMessageContaining("append-only");
  }

  // --- utilidades ---

  private record Runner(UUID id, String token) {}

  private record Launched(UUID runId, UUID agentId) {}

  private record Project(String id, String repositoryId, String workItemId, PurgeIT test) {
    Launched launch(String prompt) {
      return launched(
          test.post(
              "/api/work-items/" + workItemId + "/runs",
              Map.of("repositoryId", repositoryId, "prompt", prompt)));
    }
  }

  private static Launched launched(JsonNode run) {
    return new Launched(
        UUID.fromString(run.path("id").asString()),
        UUID.fromString(run.path("stages").get(0).path("agents").get(0).path("id").asString()));
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
            .body(new RunnerRegistration(name, RUNNER_REGISTRATION_TOKEN, "dev", 4, "2.1.288"))
            .retrieve()
            .body(JsonNode.class);
    return new Runner(
        UUID.fromString(registered.path("runnerId").asString()),
        registered.path("token").asString());
  }

  /** El runner recoge una orden de arranque y la confirma. */
  private void start(Runner runner) {
    List<JsonNode> commands = poll(runner);
    assertThat(commands).hasSize(1);
    ack(runner, commands.getFirst());
  }

  /** Recorre una invocación completa; con {@code path}, en un worktree. */
  private void complete(Runner runner, UUID agentRunId, String path) {
    if (path != null) {
      send(
          runner,
          agentRunId,
          AgentEventType.WORKSPACE_READY,
          Map.of("path", path, "branch", "skynet" + path, "baseCommit", "abc"));
    }
    send(
        runner,
        agentRunId,
        AgentEventType.SESSION_STARTED,
        Map.of("sessionId", UUID.randomUUID().toString()));
    send(runner, agentRunId, AgentEventType.RESULT, Map.of("subtype", "success", "isError", false));
    send(runner, agentRunId, AgentEventType.PROCESS_EXITED, Map.of("exitCode", 0));
    assertThat(get("/api/agent-runs/" + agentRunId).path("agent").path("status").asString())
        .isEqualTo("COMPLETED");
  }

  private final Map<UUID, Integer> sequences = new HashMap<>();

  private void send(Runner runner, UUID agentRunId, AgentEventType type, Map<String, ?> payload) {
    int seq = sequences.merge(agentRunId, 1, Integer::sum);
    NormalizedEvent event =
        new NormalizedEvent(
            UUID.randomUUID(), agentRunId, seq, Instant.now(), type, new LinkedHashMap<>(payload));
    http.post()
        .uri("/api/runner/events")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + runner.token())
        .body(new EventBatch(List.of(event)))
        .retrieve()
        .toBodilessEntity();
  }

  private void upload(Runner runner, UUID agentRunId, String name, byte[] content) {
    ArtifactUpload metadata =
        new ArtifactUpload(
            agentRunId, null, ArtifactType.LOG, name, "text/plain", sha256(content), Map.of());
    http.post()
        .uri("/api/runner/artifacts")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + runner.token())
        .header(
            ArtifactUpload.HEADER,
            java.util.Base64.getUrlEncoder()
                .encodeToString(JsonMapper.builder().build().writeValueAsBytes(metadata)))
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .body(content)
        .retrieve()
        .toBodilessEntity();
  }

  private static Path blobPath(byte[] content) {
    String sha = sha256(content);
    return ARTIFACTS.resolve(sha.substring(0, 2)).resolve(sha.substring(2, 4)).resolve(sha);
  }

  private static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private List<JsonNode> poll(Runner runner) {
    List<JsonNode> list = new ArrayList<>();
    http.get()
        .uri("/api/runner/commands?waitSeconds=0")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + runner.token())
        .retrieve()
        .body(JsonNode.class)
        .forEach(list::add);
    return list;
  }

  private void ack(Runner runner, JsonNode command) {
    http.post()
        .uri("/api/runner/commands/" + command.path("id").asString() + "/ack")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + runner.token())
        .retrieve()
        .toBodilessEntity();
  }

  private long rows(String table) {
    return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
  }

  private JsonNode lastEvent(String type) {
    JsonNode last = null;
    for (JsonNode e : get("/api/events?limit=500")) {
      if (e.path("type").asString().equals(type)) {
        last = e;
      }
    }
    assertThat(last).as(type).isNotNull();
    return last;
  }

  private static void assertConflict(Runnable call, String reason) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            HttpClientErrorException.class,
            e -> {
              assertThat(e.getStatusCode().value()).isEqualTo(409);
              assertThat(e.getResponseBodyAsString()).contains(reason);
            });
  }

  private static void assertNotFound(Runnable call) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            HttpClientErrorException.class,
            e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
  }

  private static List<String> keys(JsonNode projects) {
    List<String> keys = new ArrayList<>();
    projects.forEach(p -> keys.add(p.path("key").asString()));
    return keys;
  }

  private static List<String> names(JsonNode runners) {
    List<String> names = new ArrayList<>();
    runners.forEach(r -> names.add(r.path("name").asString()));
    return names;
  }

  private JsonNode post(String path) {
    return post(path, Map.of());
  }

  private JsonNode post(String path, Object body) {
    return http.post().uri(path).body(body).retrieve().body(JsonNode.class);
  }

  private JsonNode delete(String path) {
    return http.delete().uri(path).retrieve().body(JsonNode.class);
  }

  private JsonNode get(String path) {
    return http.get().uri(path).retrieve().body(JsonNode.class);
  }
}
