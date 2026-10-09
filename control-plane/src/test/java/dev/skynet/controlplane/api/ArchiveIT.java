package dev.skynet.controlplane.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skynet.controlplane.support.IntegrationTest;
import dev.skynet.protocol.runner.RunnerHeartbeat;
import dev.skynet.protocol.runner.RunnerRegistration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

/** Archivar y restaurar (AE-A): lo archivado sale de las listas y es de solo lectura. */
class ArchiveIT extends IntegrationTest {

  @Test
  void aFinishedRunIsArchivedHiddenAndRestored() {
    Project alpha = project("ALPHA");
    Launched run = alpha.launch("uno");

    assertConflict(() -> post("/api/workflow-runs/" + run.runId() + "/archive"), "en curso");

    post("/api/agent-runs/" + run.agentId() + "/cancel");
    JsonNode archived = post("/api/workflow-runs/" + run.runId() + "/archive");
    assertThat(archived.path("archivedAt").isNull()).isFalse();
    // Archivar dos veces no cambia la fecha.
    assertThat(post("/api/workflow-runs/" + run.runId() + "/archive").path("archivedAt"))
        .isEqualTo(archived.path("archivedAt"));

    assertThat(ids(get("/api/workflow-runs"))).isEmpty();
    assertThat(ids(get("/api/workflow-runs?archived=true"))).containsExactly(run.runId());
    assertThat(ids(get("/api/workflow-runs?archived=all"))).containsExactly(run.runId());
    assertThat(get("/api/work-items/" + alpha.workItemId() + "/runs")).isEmpty();
    assertThat(get("/api/work-items/" + alpha.workItemId() + "/runs?archived=true")).hasSize(1);
    // Su página se sigue abriendo, con la fecha de archivado.
    assertThat(get("/api/workflow-runs/" + run.runId()).path("archivedAt"))
        .isEqualTo(archived.path("archivedAt"));
    assertThat(get("/api/dashboard/metrics?period=24h").path("total").asLong()).isZero();

    // Un agente de una ejecución archivada no se continúa.
    assertConflict(() -> post("/api/agent-runs/" + run.agentId() + "/retry"), "archivada");

    assertThat(post("/api/workflow-runs/" + run.runId() + "/restore").path("archivedAt").isNull())
        .isTrue();
    assertThat(ids(get("/api/workflow-runs"))).containsExactly(run.runId());
    assertThat(get("/api/dashboard/metrics?period=24h").path("total").asLong()).isEqualTo(1);
    assertThat(eventTypes(run.runId())).contains("workflow.archived", "workflow.restored");
  }

  @Test
  void anArchivedWorkItemHidesItsRunsAndTakesNoAgents() {
    Project alpha = project("ALPHA");
    Launched run = alpha.launch("uno");

    assertConflict(() -> post("/api/work-items/" + alpha.workItemId() + "/archive"), "en curso");
    post("/api/agent-runs/" + run.agentId() + "/cancel");
    post("/api/work-items/" + alpha.workItemId() + "/archive");

    assertThat(get("/api/projects/" + alpha.id() + "/work-items")).isEmpty();
    assertThat(get("/api/projects/" + alpha.id() + "/work-items?archived=true")).hasSize(1);
    // La ejecución no está archivada, pero cuelga de un trabajo que sí.
    assertThat(ids(get("/api/workflow-runs"))).isEmpty();
    assertThat(ids(get("/api/workflow-runs?archived=true"))).containsExactly(run.runId());
    assertThat(get("/api/workflow-runs/" + run.runId()).path("archivedAt").isNull()).isTrue();

    assertConflict(() -> alpha.launch("dos"), "archivado");
    assertConflict(() -> post("/api/agent-runs/" + run.agentId() + "/retry"), "archivado");

    post("/api/work-items/" + alpha.workItemId() + "/restore");
    assertThat(ids(get("/api/workflow-runs"))).containsExactly(run.runId());
    alpha.launch("dos");
  }

  @Test
  void anArchivedProjectIsReadOnlyAndHidesEverythingBelow() {
    Project alpha = project("ALPHA");
    Project beta = project("BETA");
    Launched run = alpha.launch("uno");
    beta.launch("otra");

    assertConflict(() -> post("/api/projects/" + alpha.id() + "/archive"), "en curso");
    post("/api/agent-runs/" + run.agentId() + "/cancel");
    post("/api/projects/" + alpha.id() + "/archive");

    assertThat(keys(get("/api/projects"))).containsExactly("BETA");
    assertThat(keys(get("/api/projects?archived=true"))).containsExactly("ALPHA");
    assertThat(keys(get("/api/projects?archived=all"))).containsExactly("ALPHA", "BETA");
    assertThat(get("/api/workflow-runs").path("total").asLong()).isEqualTo(1);
    assertThat(ids(get("/api/workflow-runs?archived=true&projectId=" + alpha.id())))
        .containsExactly(run.runId());
    assertThat(get("/api/dashboard/metrics?period=24h").path("total").asLong()).isEqualTo(1);
    assertThat(get("/api/projects/" + alpha.id()).path("archivedAt").isNull()).isFalse();

    // Solo lectura: ni trabajos, ni repositorios, ni agentes, ni cambios de configuración.
    assertConflict(
        () ->
            post(
                "/api/projects/" + alpha.id() + "/work-items",
                Map.of("title", "Nuevo", "type", "BUG")),
        "archivado");
    assertConflict(
        () ->
            post(
                "/api/projects/" + alpha.id() + "/repositories",
                Map.of("name", "otro", "localPath", "/repos/otro")),
        "archivado");
    assertConflict(() -> alpha.launch("dos"), "archivado");
    assertConflict(
        () ->
            http.put()
                .uri(
                    "/api/projects/"
                        + alpha.id()
                        + "/repositories/"
                        + alpha.repositoryId()
                        + "/verification")
                .body(Map.of("validationCommand", "make test"))
                .retrieve()
                .toBodilessEntity(),
        "archivado");
    // Nada de lo que cuelga se restaura mientras el proyecto siga archivado.
    post("/api/workflow-runs/" + run.runId() + "/archive");
    assertConflict(() -> post("/api/workflow-runs/" + run.runId() + "/restore"), "archivado");

    post("/api/projects/" + alpha.id() + "/restore");
    assertThat(keys(get("/api/projects"))).containsExactly("ALPHA", "BETA");
    post("/api/workflow-runs/" + run.runId() + "/restore");
    alpha.launch("dos");
    assertThat(eventTypes(null)).contains("project.archived", "project.restored");
  }

  @Test
  void anArchivedRepositoryIsNotOfferedForAgents() {
    Project alpha = project("ALPHA");
    String repositories = "/api/projects/" + alpha.id() + "/repositories";
    Launched run = alpha.launch("uno");

    assertConflict(() -> post(repositories + "/" + alpha.repositoryId() + "/archive"), "en curso");
    post("/api/agent-runs/" + run.agentId() + "/cancel");
    post(repositories + "/" + alpha.repositoryId() + "/archive");

    assertThat(get(repositories)).isEmpty();
    assertThat(get(repositories + "?archived=true")).hasSize(1);
    assertConflict(() -> alpha.launch("dos"), "archivado");

    post(repositories + "/" + alpha.repositoryId() + "/restore");
    assertThat(get(repositories)).hasSize(1);
    alpha.launch("dos");
  }

  @Test
  void aForgottenRunnerLosesItsTokenAndComesBackWhenItRegisters() {
    Runner busy = register("busy");
    Runner gone = register("gone");
    project("ALPHA").launch("uno");
    startOn(busy);

    assertConflict(() -> post("/api/runners/" + busy.id() + "/archive"), "en marcha");

    JsonNode archived = post("/api/runners/" + gone.id() + "/archive");
    assertThat(archived.path("archivedAt").isNull()).isFalse();
    assertThat(names(get("/api/runners"))).containsExactly("busy");
    assertThat(names(get("/api/runners?archived=true"))).containsExactly("gone");
    assertThat(names(get("/api/dashboard").path("staleRunners"))).doesNotContain("gone");
    assertThatThrownBy(() -> heartbeat(gone))
        .isInstanceOfSatisfying(
            RestClientResponseException.class,
            e -> assertThat(e.getStatusCode().value()).isEqualTo(401));

    // El runner que sigue vivo se vuelve a registrar y reaparece.
    register("gone");
    assertThat(names(get("/api/runners"))).containsExactly("busy", "gone");

    post("/api/runners/" + gone.id() + "/archive");
    post("/api/runners/" + gone.id() + "/restore");
    assertThat(names(get("/api/runners"))).containsExactly("busy", "gone");
    assertThat(eventTypes(null)).contains("runner.archived", "runner.restored");
  }

  @Test
  void rejectsAnUnknownArchivedFilter() {
    assertThatThrownBy(() -> get("/api/projects?archived=maybe"))
        .isInstanceOfSatisfying(
            HttpClientErrorException.class,
            e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
  }

  private record Runner(UUID id, String token) {}

  private record Launched(UUID runId, UUID agentId) {}

  private record Project(String id, String repositoryId, String workItemId, ArchiveIT test) {
    Launched launch(String prompt) {
      JsonNode run =
          test.post(
              "/api/work-items/" + workItemId + "/runs",
              Map.of("repositoryId", repositoryId, "prompt", prompt));
      return new Launched(
          UUID.fromString(run.path("id").asString()),
          UUID.fromString(run.path("stages").get(0).path("agents").get(0).path("id").asString()));
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

  /** El runner recoge la orden de arranque y la confirma. */
  private void startOn(Runner runner) {
    JsonNode commands =
        http.get()
            .uri("/api/runner/commands?waitSeconds=1")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + runner.token())
            .retrieve()
            .body(JsonNode.class);
    assertThat(commands).hasSize(1);
    http.post()
        .uri("/api/runner/commands/" + commands.get(0).path("id").asString() + "/ack")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + runner.token())
        .retrieve()
        .toBodilessEntity();
  }

  private void heartbeat(Runner runner) {
    http.post()
        .uri("/api/runner/heartbeat")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + runner.token())
        .body(new RunnerHeartbeat(2, List.of()))
        .retrieve()
        .toBodilessEntity();
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

  /** Tipos de los eventos de la ejecución, o de todos con {@code null}. */
  private List<String> eventTypes(UUID workflowRunId) {
    List<String> types = new ArrayList<>();
    String filter = workflowRunId == null ? "" : "&workflowRunId=" + workflowRunId;
    get("/api/events?limit=500" + filter).forEach(e -> types.add(e.path("type").asString()));
    return types;
  }

  private static List<UUID> ids(JsonNode page) {
    List<UUID> ids = new ArrayList<>();
    page.path("items").forEach(r -> ids.add(UUID.fromString(r.path("id").asString())));
    return ids;
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

  private JsonNode get(String path) {
    return http.get().uri(path).retrieve().body(JsonNode.class);
  }
}
