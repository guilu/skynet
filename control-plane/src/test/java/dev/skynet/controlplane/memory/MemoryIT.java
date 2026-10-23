package dev.skynet.controlplane.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skynet.controlplane.support.IntegrationTest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;

/** Memoria de proyecto (ME-A): versiones por tema, aislamiento, búsqueda, revisión y secretos. */
class MemoryIT extends IntegrationTest {

  @Autowired private MemoryService memory;

  @Test
  void savingTheSameTopicCreatesANewVersionAndKeepsTheHistory() {
    Project alpha = project("ALPHA");

    ResponseEntity<JsonNode> first =
        save(
            alpha.id(),
            alpha.repositoryId(),
            entry(
                "DECISION", "Fusionar con squash", "arquitectura/merge", "Las PRs van con squash"));
    assertThat(first.getStatusCode().value()).isEqualTo(201);
    JsonNode v1 = first.getBody();
    assertThat(v1.path("version").asInt()).isEqualTo(1);
    assertThat(v1.path("origin").asString()).isEqualTo("HUMAN");
    assertThat(v1.path("updatedBy").asString()).isEqualTo(ADMIN_USER);

    Map<String, Object> changed =
        entry("DECISION", "Fusionar con squash", "Arquitectura/Merge", "Squash y borrar rama");
    changed.put("why", "Historial lineal");
    ResponseEntity<JsonNode> second = save(alpha.id(), alpha.repositoryId(), changed);
    assertThat(second.getStatusCode().value()).isEqualTo(200);
    assertThat(second.getBody().path("id").asString()).isEqualTo(v1.path("id").asString());
    assertThat(second.getBody().path("version").asInt()).isEqualTo(2);
    assertThat(second.getBody().path("what").asString()).isEqualTo("Squash y borrar rama");

    // Guardar lo mismo otra vez no crea versión.
    assertThat(save(alpha.id(), alpha.repositoryId(), changed).getBody().path("version").asInt())
        .isEqualTo(2);
    // El mismo tema en otro ámbito (todo el proyecto) es otra observación.
    assertThat(save(alpha.id(), null, changed).getStatusCode().value()).isEqualTo(201);

    String id = v1.path("id").asString();
    List<JsonNode> history = list(get("/api/memory/" + id + "/history"));
    assertThat(history).extracting(h -> h.path("version").asInt()).containsExactly(2, 1);
    assertThat(history.get(1).path("what").asString()).isEqualTo("Las PRs van con squash");
    assertThat(eventTypes(UUID.fromString(id))).containsExactly("memory.created", "memory.updated");
  }

  @Test
  void eachProjectAndRepositorySeesOnlyItsOwnMemory() {
    Project alpha = project("ALPHA");
    String otherRepo = repository(alpha.id(), "web");
    Project beta = project("BETA");
    String inRepo = id(save(alpha.id(), alpha.repositoryId(), entry("DISCOVERY", "Del repo")));
    String inProject = id(save(alpha.id(), null, entry("DISCOVERY", "Del proyecto")));
    String inOther = id(save(alpha.id(), otherRepo, entry("DISCOVERY", "Del otro repo")));
    String inBeta = id(save(beta.id(), beta.repositoryId(), entry("DISCOVERY", "De beta")));

    String base = "/api/projects/" + alpha.id() + "/memory";
    assertThat(ids(get(base))).containsExactlyInAnyOrder(inRepo, inProject, inOther);
    assertThat(ids(get(base + "?repository=" + alpha.repositoryId()))).containsExactly(inRepo);
    assertThat(ids(get(base + "?repository=" + alpha.repositoryId() + "&withProject=true")))
        .containsExactlyInAnyOrder(inRepo, inProject);
    assertThat(ids(get(base + "?repository=project"))).containsExactly(inProject);
    assertThat(ids(get("/api/projects/" + beta.id() + "/memory"))).containsExactly(inBeta);
    // Un repositorio de otro proyecto no filtra nada de este.
    assertThat(ids(get(base + "?repository=" + beta.repositoryId()))).isEmpty();

    assertStatus(
        () -> save(alpha.id(), beta.repositoryId(), entry("DISCOVERY", "Mal sitio")),
        400,
        "no es del proyecto");
  }

  @Test
  void searchFindsWordsAndPiecesOfTitleOrTopic() {
    Project alpha = project("ALPHA");
    String flaky =
        id(
            save(
                alpha.id(),
                alpha.repositoryId(),
                entry("PITFALL", "Test inestable", null, "El test de SSE es flaky en la CI")));
    String auth =
        id(
            save(
                alpha.id(),
                alpha.repositoryId(),
                entry(
                    "DECISION", "Modelo de sesión", "arquitectura/auth-model", "Basic + cookie")));
    String pinnedOld = id(save(alpha.id(), null, entry("CONSTRAINT", "Sin Docker en CI local")));
    post("/api/memory/" + pinnedOld + "/pin");

    String base = "/api/projects/" + alpha.id() + "/memory";
    assertThat(ids(get(base + "?q=flaky"))).containsExactly(flaky);
    assertThat(ids(get(base + "?q=auth"))).containsExactly(auth);
    assertThat(ids(get(base + "?q=arquitectura"))).containsExactly(auth);
    assertThat(ids(get(base + "?kind=PITFALL&kind=CONSTRAINT")))
        .containsExactlyInAnyOrder(flaky, pinnedOld);
    // Sin texto, las fijadas primero y luego lo más reciente.
    assertThat(ids(get(base))).containsExactly(pinnedOld, auth, flaky);
    assertThat(ids(get(base + "?pinned=true"))).containsExactly(pinnedOld);

    JsonNode page = get(base + "?size=2&page=1");
    assertThat(page.path("total").asLong()).isEqualTo(3);
    assertThat(ids(page)).containsExactly(flaky);
  }

  @Test
  void anArchivedObservationIsHiddenAndFreesItsTopic() {
    Project alpha = project("ALPHA");
    String old =
        id(save(alpha.id(), alpha.repositoryId(), entry("PATTERN", "Viejo", "ci/cache", "A")));
    post("/api/memory/" + old + "/pin");

    JsonNode archived = post("/api/memory/" + old + "/archive");
    assertThat(archived.path("archivedAt").isNull()).isFalse();
    assertThat(archived.path("pinned").asBoolean()).isFalse();
    String base = "/api/projects/" + alpha.id() + "/memory";
    assertThat(ids(get(base))).isEmpty();
    assertThat(ids(get(base + "?archived=true"))).containsExactly(old);
    assertStatus(() -> post("/api/memory/" + old + "/pin"), 409, "archivada");

    // El tema queda libre: guardarlo otra vez empieza otra observación.
    ResponseEntity<JsonNode> fresh =
        save(alpha.id(), alpha.repositoryId(), entry("PATTERN", "Nuevo", "ci/cache", "B"));
    assertThat(fresh.getStatusCode().value()).isEqualTo(201);
    assertStatus(() -> post("/api/memory/" + old + "/restore"), 409, "ci/cache");

    post("/api/memory/" + id(fresh) + "/archive");
    assertThat(post("/api/memory/" + old + "/restore").path("archivedAt").isNull()).isTrue();
    assertThat(eventTypes(UUID.fromString(old)))
        .containsExactly("memory.created", "memory.pinned", "memory.archived", "memory.restored");
  }

  @Test
  void aPersonEditsAnAgentObservationAndATopicIsNotTakenTwice() {
    Project alpha = project("ALPHA");
    String id =
        memory
            .save(
                UUID.fromString(alpha.id()),
                null,
                new MemoryContent(
                    MemoryKind.BUGFIX,
                    "NUL en eventos",
                    null,
                    "Se filtran",
                    null,
                    null,
                    null,
                    null),
                MemorySource.agent(null, null))
            .observation()
            .id()
            .toString();

    JsonNode edited =
        http.put()
            .uri("/api/memory/" + id)
            .body(entry("BUGFIX", "NUL en eventos", "eventos/nul", "SafeText los quita"))
            .retrieve()
            .body(JsonNode.class);
    assertThat(edited.path("version").asInt()).isEqualTo(2);
    assertThat(edited.path("topicKey").asString()).isEqualTo("eventos/nul");
    assertThat(edited.path("origin").asString()).isEqualTo("HUMAN");
    assertThat(list(get("/api/memory/" + id + "/history")))
        .extracting(h -> h.path("origin").asString())
        .containsExactly("HUMAN", "AGENT");

    String other = id(save(alpha.id(), null, entry("DISCOVERY", "Otro", "otro/tema", "x")));
    assertStatus(
        () ->
            http.put()
                .uri("/api/memory/" + other)
                .body(entry("DISCOVERY", "Otro", "eventos/nul", "x"))
                .retrieve()
                .body(JsonNode.class),
        409,
        "eventos/nul");
  }

  @Test
  void secretsAreMaskedBeforeSaving() {
    Project alpha = project("ALPHA");
    String token = "ghp_" + "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6q7R8";
    JsonNode saved =
        save(alpha.id(), null, entry("CONFIG", "Token de CI", null, "Usa " + token + " en CI"))
            .getBody();

    assertThat(saved.path("what").asString()).doesNotContain(token).contains("[REDACTED]");
    assertThat(saved.path("redacted").asBoolean()).isTrue();
    String id = saved.path("id").asString();
    assertThat(get("/api/memory/" + id + "/history").toString()).doesNotContain(token);
    assertThat(
            jdbc.sql("SELECT count(*) FROM event WHERE payload::text LIKE ?")
                .param("%" + token + "%")
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  void invalidObservationsAreRejected() {
    Project alpha = project("ALPHA");
    String p = alpha.id();

    Map<String, Object> noWhat = entry("DISCOVERY", "Sin qué");
    noWhat.remove("what");
    assertStatus(() -> save(p, null, noWhat), 400, "what");
    assertStatus(() -> save(p, null, entry("DISCOVERY", "x".repeat(201))), 400, "title");
    assertStatus(
        () -> save(p, null, entry("DISCOVERY", "Tema", "Con Espacios", "x")), 400, "topicKey");
    assertStatus(() -> save(p, null, entry("DISCOVERY", "Salto\nde línea")), 400, "control");
    assertStatus(() -> save(p, null, entry("RUN_LOG", "Bitácora")), 400, "RUN_LOG");
    Map<String, Object> manyTags = entry("DISCOVERY", "Etiquetas");
    List<String> tags = new ArrayList<>();
    for (int i = 0; i < 21; i++) {
      tags.add("t" + i);
    }
    manyTags.put("tags", tags);
    assertStatus(() -> save(p, null, manyTags), 400, "etiquetas");
    assertStatus(() -> get("/api/projects/" + p + "/memory?q=" + "x".repeat(201)), 400, "200");

    post("/api/projects/" + p + "/archive");
    assertStatus(() -> save(p, null, entry("DISCOVERY", "Archivado")), 409, "archivado");
  }

  @Test
  void deletingAProjectDeletesItsMemoryAndItsEvents() {
    Project alpha = project("ALPHA");
    String inRepo = id(save(alpha.id(), alpha.repositoryId(), entry("DISCOVERY", "Del repo")));
    String inProject = id(save(alpha.id(), null, entry("DISCOVERY", "Del proyecto")));

    post("/api/projects/" + alpha.id() + "/archive");
    http.delete().uri("/api/projects/" + alpha.id()).retrieve().body(JsonNode.class);

    assertThat(jdbc.sql("SELECT count(*) FROM memory_observation").query(Long.class).single())
        .isZero();
    assertThat(
            jdbc.sql("SELECT count(*) FROM memory_observation_revision").query(Long.class).single())
        .isZero();
    assertThat(eventTypes(UUID.fromString(inRepo))).isEmpty();
    assertThat(eventTypes(UUID.fromString(inProject))).isEmpty();
  }

  private record Project(String id, String repositoryId) {}

  private Project project(String key) {
    String projectId = post("/api/projects", Map.of("key", key, "name", key)).path("id").asString();
    return new Project(projectId, repository(projectId, "demo"));
  }

  private String repository(String projectId, String name) {
    return post(
            "/api/projects/" + projectId + "/repositories",
            Map.of("name", name, "localPath", "/repos/" + projectId + "/" + name))
        .path("id")
        .asString();
  }

  private static Map<String, Object> entry(String kind, String title) {
    return entry(kind, title, null, "Contenido de " + title);
  }

  private static Map<String, Object> entry(
      String kind, String title, String topicKey, String what) {
    Map<String, Object> body = new HashMap<>();
    body.put("kind", kind);
    body.put("title", title);
    body.put("topicKey", topicKey);
    body.put("what", what);
    return body;
  }

  private ResponseEntity<JsonNode> save(
      String projectId, String repositoryId, Map<String, Object> body) {
    Map<String, Object> request = new HashMap<>(body);
    request.put("repositoryId", repositoryId);
    return http.post()
        .uri("/api/projects/" + projectId + "/memory")
        .body(request)
        .retrieve()
        .toEntity(JsonNode.class);
  }

  private static String id(ResponseEntity<JsonNode> saved) {
    return saved.getBody().path("id").asString();
  }

  private static List<String> ids(JsonNode page) {
    List<String> ids = new ArrayList<>();
    page.path("items").forEach(item -> ids.add(item.path("id").asString()));
    return ids;
  }

  private static List<JsonNode> list(JsonNode array) {
    List<JsonNode> items = new ArrayList<>();
    array.forEach(items::add);
    return items;
  }

  private List<String> eventTypes(UUID aggregateId) {
    return jdbc.sql("SELECT event_type FROM event WHERE aggregate_id = ? ORDER BY sequence")
        .param(aggregateId)
        .query(String.class)
        .list();
  }

  private static void assertStatus(Runnable call, int status, String reason) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            HttpClientErrorException.class,
            e -> {
              assertThat(e.getStatusCode().value()).isEqualTo(status);
              assertThat(e.getResponseBodyAsString()).contains(reason);
            });
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
