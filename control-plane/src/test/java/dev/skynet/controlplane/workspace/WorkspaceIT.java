package dev.skynet.controlplane.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skynet.controlplane.support.IntegrationTest;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;

/** Datos operativos de la fábrica (FS-B): repositorio del trabajo, fase, coste y cambios. */
class WorkspaceIT extends IntegrationTest {

  @Test
  void aWorkItemTakesTheOnlyRepositoryOrTheOneOfItsFirstLaunch() {
    String project = project("ONE");
    String demo = repository(project, "demo");
    JsonNode only = workItem(project, Map.of());
    assertThat(only.path("repositoryId").asString()).isEqualTo(demo);
    assertThat(only.path("phase").asString()).isEqualTo("TODO");

    String other = repository(project, "other");
    // Con dos repositorios ya no se elige solo…
    JsonNode loose = workItem(project, Map.of());
    assertThat(loose.path("repositoryId").isNull()).isTrue();
    // …pero se puede indicar.
    assertThat(workItem(project, Map.of("repositoryId", other)).path("repositoryId").asString())
        .isEqualTo(other);
    // Uno de otro proyecto no vale.
    String foreign = repository(project("TWO"), "foreign");
    assertThatThrownBy(() -> workItem(project, Map.of("repositoryId", foreign)))
        .isInstanceOfSatisfying(
            HttpClientErrorException.class,
            e -> assertThat(e.getStatusCode().value()).isEqualTo(404));

    // Al lanzarlo en un repositorio, el trabajo sin repositorio se queda con ese.
    String id = loose.path("id").asString();
    launch(id, other);
    assertThat(get("/api/work-items/" + id).path("repositoryId").asString()).isEqualTo(other);
    assertThat(get("/api/work-items/" + id).path("phase").asString()).isEqualTo("RUNNING");
  }

  @Test
  void theOverviewFollowsAttemptsCostsAndPullRequests() {
    String project = project("OPS");
    String repo = repository(project, "demo-repo");
    String item = workItem(project, Map.of("title", "Separar el lector")).path("id").asString();

    JsonNode first = launch(item, repo);
    UUID firstAgent = agent(first);
    post("/api/workflow-runs/" + first.path("id").asString() + "/cancel");
    cost(firstAgent, "0.61");
    assertThat(summary(item).path("phase").asString()).isEqualTo("TODO");

    JsonNode second = launch(item, repo);
    UUID secondAgent = agent(second);
    cost(secondAgent, "1.12");
    JsonNode running = get("/api/projects/" + project + "/overview");
    assertThat(running.path("activeRuns").asLong()).isEqualTo(1);
    assertThat(running.path("openWorkItems").asLong()).isEqualTo(1);
    JsonNode repoView = running.path("repositories").get(0);
    assertThat(repoView.path("name").asString()).isEqualTo("demo-repo");
    assertThat(repoView.path("activeRuns").asLong()).isEqualTo(1);
    assertThat(repoView.path("openWorkItems").asLong()).isEqualTo(1);
    assertThat(repoView.path("costUsd").decimalValue()).isEqualByComparingTo("1.73");
    JsonNode summary = running.path("workItems").get(0);
    assertThat(summary.path("phase").asString()).isEqualTo("RUNNING");
    assertThat(summary.path("attempts").asLong()).isEqualTo(2);
    // El coste del trabajo suma todos sus intentos.
    assertThat(summary.path("costUsd").decimalValue()).isEqualByComparingTo("1.73");
    assertThat(summary.path("repositoryName").asString()).isEqualTo("demo-repo");
    assertThat(summary.path("workflowKey").asString()).isEqualTo("adhoc");

    // Falla el segundo intento: atascado.
    post("/api/workflow-runs/" + second.path("id").asString() + "/cancel");
    jdbc.sql("UPDATE workflow_run SET status = 'FAILED' WHERE id = ?")
        .param(UUID.fromString(second.path("id").asString()))
        .update();
    assertThat(summary(item).path("phase").asString()).isEqualTo("STUCK");

    // Abre una PR: en revisión, y sale en los cambios y en los resultados.
    UUID pr = pullRequest(second, repo, "OPEN");
    assertThat(summary(item).path("phase").asString()).isEqualTo("REVIEW");
    assertThat(summary(item).path("pullRequest").path("number").asInt()).isEqualTo(44);
    JsonNode review = get("/api/projects/" + project + "/overview");
    assertThat(review.path("openPullRequests").asLong()).isEqualTo(1);
    assertThat(review.path("repositories").get(0).path("openPullRequests").asLong()).isEqualTo(1);
    JsonNode changes = get("/api/projects/" + project + "/changes");
    assertThat(changes).hasSize(1);
    assertThat(changes.get(0).path("workItemTitle").asString()).isEqualTo("Separar el lector");
    assertThat(changes.get(0).path("repositoryName").asString()).isEqualTo("demo-repo");
    assertThat(get("/api/results")).hasSize(1);
    assertThat(keys(get("/api/work-items"))).containsExactly("OPS-1");
    assertThat(keys(get("/api/work-items?phase=REVIEW&phase=STUCK"))).containsExactly("OPS-1");
    assertThat(keys(get("/api/work-items?phase=DONE"))).isEmpty();

    // Se fusiona: entregado, fuera de los pendientes.
    jdbc.sql("UPDATE github_pull_request SET status = 'MERGED' WHERE id = ?").param(pr).update();
    assertThat(summary(item).path("phase").asString()).isEqualTo("DONE");
    assertThat(get("/api/projects/" + project + "/overview").path("mergedPullRequests").asLong())
        .isEqualTo(1);
    assertThat(keys(get("/api/work-items"))).isEmpty();
    assertThat(keys(get("/api/work-items?phase=DONE"))).containsExactly("OPS-1");

    // Archivado, deja de contar.
    post("/api/work-items/" + item + "/archive");
    assertThat(get("/api/projects/" + project + "/overview").path("workItems")).isEmpty();
    assertThat(get("/api/results")).isEmpty();
    assertThatThrownBy(() -> summary(item))
        .isInstanceOfSatisfying(
            HttpClientErrorException.class,
            e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
  }

  @Test
  void anUnknownPhaseIsRejected() {
    assertThatThrownBy(() -> get("/api/work-items?phase=LATER"))
        .isInstanceOfSatisfying(
            HttpClientErrorException.class,
            e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
  }

  private String project(String key) {
    return post("/api/projects", Map.of("key", key, "name", key)).path("id").asString();
  }

  private String repository(String project, String name) {
    return post(
            "/api/projects/" + project + "/repositories",
            Map.of("name", name, "localPath", "/repos/" + project + "/" + name))
        .path("id")
        .asString();
  }

  private JsonNode workItem(String project, Map<String, String> extra) {
    Map<String, String> body = new java.util.HashMap<>(Map.of("title", "T", "type", "FEATURE"));
    body.putAll(extra);
    return post("/api/projects/" + project + "/work-items", body);
  }

  private JsonNode launch(String workItem, String repository) {
    return post(
        "/api/work-items/" + workItem + "/runs",
        Map.of("repositoryId", repository, "prompt", "haz algo"));
  }

  private static UUID agent(JsonNode run) {
    return UUID.fromString(run.path("stages").get(0).path("agents").get(0).path("id").asString());
  }

  private void cost(UUID agent, String usd) {
    jdbc.sql("UPDATE agent_run SET cost_usd = ? WHERE id = ?")
        .params(new BigDecimal(usd), agent)
        .update();
  }

  private UUID pullRequest(JsonNode run, String repository, String status) {
    UUID id = UUID.randomUUID();
    JsonNode stage = run.path("stages").get(0);
    Timestamp now = Timestamp.from(Instant.now());
    jdbc.sql(
            "INSERT INTO github_pull_request (id, workflow_run_id, repository_id, stage_run_id,"
                + " agent_run_id, full_name, branch, title, body, status, number, url,"
                + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'guilu/demo-repo',"
                + " 'skynet/OPS-1', 'Separar el lector', '', ?, 44,"
                + " 'https://github.com/guilu/demo-repo/pull/44', ?, ?)")
        .params(
            id,
            UUID.fromString(run.path("id").asString()),
            UUID.fromString(repository),
            UUID.fromString(stage.path("id").asString()),
            agent(run),
            status,
            now,
            now)
        .update();
    return id;
  }

  private JsonNode summary(String item) {
    return get("/api/work-items/" + item + "/summary");
  }

  private static List<String> keys(JsonNode items) {
    List<String> keys = new ArrayList<>();
    items.forEach(i -> keys.add(i.path("key").asString()));
    return keys;
  }

  private JsonNode post(String path, Object body) {
    return http.post().uri(path).body(body).retrieve().body(JsonNode.class);
  }

  private JsonNode post(String path) {
    return post(path, Map.of());
  }

  private JsonNode get(String path) {
    return http.get().uri(path).retrieve().body(JsonNode.class);
  }
}
