package dev.skynet.controlplane.definition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skynet.controlplane.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;

/** Ciclo de vida de un workflow por la API: borrador, validación, publicación y versiones. */
class WorkflowDefinitionsIT extends IntegrationTest {

  static final String REVIEW =
      """
      id: revisar
      version: 1
      name: Revisar y corregir
      agents:
        reviewer:
          prompt: Revisa {{workItem.title}}
          tools: [Read, Grep]
      stages:
        - id: review
          type: agent
          agent: reviewer
        - id: fix
          type: agent
          prompt: Corrige lo que encontró la revisión
          dependsOn: [review]
      """;

  @Test
  void adhocIsPublishedFromTheMigrations() {
    JsonNode list = get("/api/workflows");
    assertThat(list).hasSize(1);
    JsonNode adhoc = list.get(0);
    assertThat(adhoc.path("key").asString()).isEqualTo("adhoc");
    assertThat(adhoc.path("name").asString()).isEqualTo("Agente suelto");
    assertThat(adhoc.path("published").path("version").asInt()).isEqualTo(1);
    assertThat(adhoc.path("draft").isNull()).isTrue();

    JsonNode detail =
        get("/api/workflow-versions/" + adhoc.path("published").path("id").asString());
    assertThat(detail.path("status").asString()).isEqualTo("PUBLISHED");
    assertThat(detail.path("validation").path("publishable").asBoolean()).isTrue();
    assertThat(detail.path("definition").path("inputs").get(0).path("name").asString())
        .isEqualTo("prompt");
  }

  @Test
  void aDraftIsSavedEvenWithErrorsAndPublishedOnceValid() {
    JsonNode draft =
        post("/api/workflows", Map.of("sourceYaml", REVIEW.replace("[review]", "[reviw]")));
    String id = draft.path("id").asString();
    assertThat(draft.path("key").asString()).isEqualTo("revisar");
    assertThat(draft.path("version").asInt()).isEqualTo(1);
    assertThat(draft.path("status").asString()).isEqualTo("DRAFT");
    JsonNode problem = draft.path("validation").path("problems").get(0);
    assertThat(problem.path("message").asString())
        .isEqualTo("No hay ninguna fase `reviw`; ¿quisiste decir `review`?");
    assertThat(problem.path("line").asInt()).isEqualTo(15);

    assertThatThrownBy(() -> publish(id, draft.path("revision").asLong()))
        .isInstanceOfSatisfying(
            HttpClientErrorException.Conflict.class,
            e ->
                assertThat(e.getResponseBodyAsString())
                    .contains("No se puede publicar un borrador con errores")
                    .contains("reviw"));

    JsonNode saved = save(id, REVIEW, draft.path("revision").asLong());
    assertThat(saved.path("status").asString()).isEqualTo("VALIDATED");
    assertThat(saved.path("revision").asLong()).isEqualTo(draft.path("revision").asLong() + 1);

    // Guardar con una revisión vieja: alguien lo cambió entretanto.
    assertThatThrownBy(() -> save(id, REVIEW, draft.path("revision").asLong()))
        .isInstanceOf(HttpClientErrorException.Conflict.class);

    JsonNode published = publish(id, saved.path("revision").asLong());
    assertThat(published.path("status").asString()).isEqualTo("PUBLISHED");
    assertThat(published.path("publishedAt").isNull()).isFalse();

    // Publicada ya no se edita ni se borra, ni siquiera por SQL.
    assertThatThrownBy(() -> save(id, REVIEW, published.path("revision").asLong()))
        .isInstanceOf(HttpClientErrorException.Conflict.class);
    assertThatThrownBy(
            () -> http.delete().uri("/api/workflow-versions/" + id).retrieve().toBodilessEntity())
        .isInstanceOf(HttpClientErrorException.Conflict.class);
    assertThatThrownBy(
            () ->
                jdbc.sql("UPDATE workflow_definition SET source_yaml = 'x' WHERE id = ?::uuid")
                    .param(id)
                    .update())
        .hasMessageContaining("una versión publicada de un workflow no se modifica");

    assertThat(eventTypes())
        .containsExactly("workflow.definition.created", "workflow.definition.published");
    assertThat(
            get("/api/workflow-definitions").findValues("key").stream()
                .map(JsonNode::asString)
                .toList())
        .containsExactly("adhoc", "revisar");
  }

  @Test
  void editingAPublishedWorkflowOpensTheNextVersion() {
    JsonNode v1 = post("/api/workflows", Map.of("sourceYaml", REVIEW));
    publish(v1.path("id").asString(), v1.path("revision").asLong());

    JsonNode v2 = post("/api/workflows/revisar/draft", Map.of());
    assertThat(v2.path("version").asInt()).isEqualTo(2);
    assertThat(v2.path("status").asString()).isEqualTo("VALIDATED");
    assertThat(v2.path("sourceYaml").asString())
        .contains("version: 2")
        .doesNotContain("version: 1");
    assertThat(v2.path("validation").path("problems")).isEmpty();
    // Pedirlo otra vez devuelve el mismo borrador.
    assertThat(post("/api/workflows/revisar/draft", Map.of()).path("id").asString())
        .isEqualTo(v2.path("id").asString());

    // Cambiar el id no se permite: queda como error del borrador.
    JsonNode renamed =
        save(
            v2.path("id").asString(),
            v2.path("sourceYaml").asString().replace("id: revisar", "id: otro"),
            v2.path("revision").asLong());
    assertThat(renamed.path("status").asString()).isEqualTo("DRAFT");
    assertThat(renamed.path("validation").path("problems").get(0).path("message").asString())
        .isEqualTo("El id de un workflow no se puede cambiar: este es `revisar`");

    JsonNode workflow = get("/api/workflows/revisar");
    assertThat(workflow.path("workflow").path("published").path("version").asInt()).isEqualTo(1);
    assertThat(workflow.path("workflow").path("draft").path("version").asInt()).isEqualTo(2);
    assertThat(workflow.path("versions").findValues("version").stream().map(JsonNode::asInt))
        .containsExactly(2, 1);

    // Descartar el borrador deja el workflow con su versión publicada.
    http.delete()
        .uri("/api/workflow-versions/" + v2.path("id").asString())
        .retrieve()
        .toBodilessEntity();
    assertThat(get("/api/workflows/revisar").path("versions")).hasSize(1);
  }

  @Test
  void discardingTheOnlyDraftRemovesTheWorkflow() {
    JsonNode draft = post("/api/workflows", Map.of("sourceYaml", REVIEW));
    http.delete()
        .uri("/api/workflow-versions/" + draft.path("id").asString())
        .retrieve()
        .toBodilessEntity();
    assertThatThrownBy(() -> get("/api/workflows/revisar"))
        .isInstanceOf(HttpClientErrorException.NotFound.class);
  }

  @Test
  void creatingRequiresAValidIdThatIsNotTaken() {
    assertThatThrownBy(() -> post("/api/workflows", Map.of("sourceYaml", "stages: []\n")))
        .isInstanceOfSatisfying(
            HttpClientErrorException.BadRequest.class,
            e -> assertThat(e.getResponseBodyAsString()).contains("Falta `id`"));
    post("/api/workflows", Map.of("sourceYaml", REVIEW));
    assertThatThrownBy(() -> post("/api/workflows", Map.of("sourceYaml", REVIEW)))
        .isInstanceOf(HttpClientErrorException.Conflict.class);
  }

  @Test
  void validateDoesNotSave() {
    JsonNode result = post("/api/workflows/validate", Map.of("sourceYaml", REVIEW));
    assertThat(result.path("key").asString()).isEqualTo("revisar");
    assertThat(result.path("validation").path("publishable").asBoolean()).isTrue();
    assertThat(result.path("definition").path("stages")).hasSize(2);
    assertThat(get("/api/workflows")).hasSize(1);
  }

  @Test
  void archivedWorkflowsLeaveTheListAndCannotBeEdited() {
    JsonNode draft = post("/api/workflows", Map.of("sourceYaml", REVIEW));
    post("/api/workflows/revisar/archive", Map.of());
    assertThat(get("/api/workflows").findValues("key").stream().map(JsonNode::asString))
        .containsExactly("adhoc");
    assertThat(get("/api/workflows?archived=true")).hasSize(1);
    assertThatThrownBy(
            () -> save(draft.path("id").asString(), REVIEW, draft.path("revision").asLong()))
        .isInstanceOf(HttpClientErrorException.Conflict.class);
    assertThatThrownBy(() -> post("/api/workflows/adhoc/archive", Map.of()))
        .isInstanceOf(HttpClientErrorException.Conflict.class);

    post("/api/workflows/revisar/restore", Map.of());
    assertThat(get("/api/workflows")).hasSize(2);
  }

  private JsonNode get(String uri) {
    return http.get().uri(uri).retrieve().body(JsonNode.class);
  }

  private JsonNode post(String uri, Object body) {
    return http.post().uri(uri).body(body).retrieve().body(JsonNode.class);
  }

  private JsonNode save(String id, String yaml, long revision) {
    return http.put()
        .uri("/api/workflow-versions/" + id)
        .body(Map.of("sourceYaml", yaml, "revision", revision))
        .retrieve()
        .body(JsonNode.class);
  }

  private JsonNode publish(String id, long revision) {
    return post("/api/workflow-versions/" + id + "/publish", Map.of("revision", revision));
  }

  private List<String> eventTypes() {
    return jdbc.sql("SELECT event_type FROM event WHERE sequence > ? ORDER BY sequence")
        .param(baseline)
        .query(String.class)
        .list();
  }
}
