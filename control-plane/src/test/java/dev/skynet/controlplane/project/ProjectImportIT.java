package dev.skynet.controlplane.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skynet.controlplane.support.IntegrationTest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;

/** Importar un proyecto con su repositorio en un solo paso (IP-B). */
class ProjectImportIT extends IntegrationTest {

  private static Map<String, Object> request(String key, String path) {
    Map<String, Object> repository = new LinkedHashMap<>();
    repository.put("name", "forma");
    repository.put("localPath", path);
    repository.put("remoteUrl", "git@github.com:guilu/forma.git");
    repository.put("defaultBranch", "main");
    repository.put("validationCommand", "./gradlew check");
    repository.put("testReportPaths", List.of("**/build/test-results/**/*.xml"));
    repository.put("protectedPaths", List.of(".github/workflows/**"));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("key", key);
    body.put("name", "Forma");
    body.put("description", "Gestor de formularios");
    body.put("repository", repository);
    return body;
  }

  @Test
  void createsTheProjectAndItsRepositoryAtOnce() {
    JsonNode imported = post("/api/projects/import", request("FORMA", "/home/diego/code/forma"));

    JsonNode project = imported.path("project");
    assertThat(project.path("key").asString()).isEqualTo("FORMA");
    assertThat(project.path("name").asString()).isEqualTo("Forma");
    assertThat(project.path("description").asString()).isEqualTo("Gestor de formularios");
    JsonNode repository = imported.path("repository");
    assertThat(repository.path("projectId").asString()).isEqualTo(project.path("id").asString());
    assertThat(repository.path("name").asString()).isEqualTo("forma");
    assertThat(repository.path("localPath").asString()).isEqualTo("/home/diego/code/forma");
    assertThat(repository.path("remoteUrl").asString()).isEqualTo("git@github.com:guilu/forma.git");
    assertThat(repository.path("defaultBranch").asString()).isEqualTo("main");
    assertThat(repository.path("validationCommand").asString()).isEqualTo("./gradlew check");
    assertThat(strings(repository.path("testReportPaths")))
        .containsExactly("**/build/test-results/**/*.xml");
    assertThat(strings(repository.path("protectedPaths"))).containsExactly(".github/workflows/**");
    assertThat(repository.path("agentPolicyCustom").asBoolean()).isFalse();

    List<String> events = new ArrayList<>();
    List<String> importedFrom = new ArrayList<>();
    get("/api/events?limit=50")
        .forEach(
            e -> {
              events.add(e.path("type").asString());
              if (e.path("payload").has("importedFrom")) {
                importedFrom.add(e.path("payload").path("importedFrom").asString());
              }
            });
    assertThat(events).contains("project.created", "repository.registered");
    assertThat(importedFrom).containsExactly("/home/diego/code/forma");
  }

  @Test
  void aTakenKeyCreatesNothing() {
    post("/api/projects", Map.of("key", "FORMA", "name", "Otra"));
    assertStatus(
        HttpStatus.CONFLICT,
        () -> post("/api/projects/import", request("FORMA", "/home/diego/code/forma")));
    assertThat(repositoryCount()).isZero();
  }

  @Test
  void aRegisteredPathCreatesNothingEvenWithATrailingSlash() {
    String sky =
        post("/api/projects", Map.of("key", "SKY", "name", "Skynet")).path("id").asString();
    post(
        "/api/projects/" + sky + "/repositories",
        Map.of("name", "forma", "localPath", "/home/diego/code/forma/"));
    assertThatThrownBy(
            () -> post("/api/projects/import", request("FORMA", "/home/diego/code/forma")))
        .isInstanceOfSatisfying(
            HttpClientErrorException.class,
            e -> {
              assertThat(e.getStatusCode().value()).isEqualTo(409);
              assertThat(e.getResponseBodyAsString()).contains("SKY");
            });
    assertThat(get("/api/projects")).hasSize(1);
  }

  @Test
  void validatesTheRequest() {
    Map<String, Object> outside = request("FORMA", "/home/diego/code/forma");
    @SuppressWarnings("unchecked")
    Map<String, Object> repository = (Map<String, Object>) outside.get("repository");
    repository.put("protectedPaths", List.of("../fuera/**"));
    assertStatus(HttpStatus.BAD_REQUEST, () -> post("/api/projects/import", outside));
    assertStatus(
        HttpStatus.BAD_REQUEST,
        () -> post("/api/projects/import", request("FORMA", "relativa/forma")));
    assertStatus(
        HttpStatus.BAD_REQUEST,
        () -> post("/api/projects/import", request("forma", "/home/diego/code/forma")));
    assertThat(get("/api/projects")).isEmpty();
  }

  @Test
  void suggestsAFreeKey() {
    assertThat(key("forma")).isEqualTo("FORMA");
    assertThat(key("akademia")).isEqualTo("AKA");
    assertThat(key("tokenmeter")).isEqualTo("TOK");
    post("/api/projects", Map.of("key", "FORMA", "name", "Forma"));
    assertThat(key("forma")).isEqualTo("FORMA2");
  }

  private String key(String name) {
    return get("/api/projects/key-suggestion?name=" + name).path("key").asString();
  }

  private long repositoryCount() {
    return jdbc.sql("SELECT count(*) FROM repository").query(Long.class).single();
  }

  private static List<String> strings(JsonNode array) {
    List<String> values = new ArrayList<>();
    array.forEach(v -> values.add(v.asString()));
    return values;
  }

  private JsonNode post(String path, Object body) {
    return http.post().uri(path).body(body).retrieve().body(JsonNode.class);
  }

  private JsonNode get(String path) {
    return http.get().uri(path).retrieve().body(JsonNode.class);
  }

  private static void assertStatus(HttpStatus expected, Runnable call) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            HttpClientErrorException.class,
            e -> assertThat(e.getStatusCode().value()).isEqualTo(expected.value()));
  }
}
