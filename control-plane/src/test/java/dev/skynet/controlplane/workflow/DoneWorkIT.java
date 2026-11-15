package dev.skynet.controlplane.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skynet.controlplane.memory.MemoryAgents;
import dev.skynet.controlplane.support.IntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ¿Ya está hecho? (MV-B): lanzar un trabajo que se parece mucho a uno ya entregado pide
 * confirmarlo, y el agente recibe en su bloque de memoria los parecidos.
 */
class DoneWorkIT extends IntegrationTest {

  @Autowired private MemoryAgents memoryAgents;

  @Test
  void launchingSomethingAlreadyDeliveredAsksToConfirmIt() {
    String project =
        post("/api/projects", Map.of("key", "AKA", "name", "akademia")).path("id").asString();
    String repository =
        post(
                "/api/projects/" + project + "/repositories",
                Map.of("name", "akademia", "localPath", "/repos/akademia"))
            .path("id")
            .asString();
    String exported = workItem(project, "Exportar las notas a PDF", "Botón en la ficha");
    UUID delivered = launch(exported, repository, false);
    merge(delivered, UUID.fromString(repository), 40);

    String again = workItem(project, "Añadir exportación de notas en PDF", null);
    String excel = workItem(project, "Exportar notas a Excel", null);
    String login = workItem(project, "Arreglar el login con Google", null);

    // Lo parecido, del más al menos, con su PR.
    JsonNode matches = get("/api/work-items/" + again + "/done-matches");
    assertThat(matches).hasSize(1);
    assertThat(matches.get(0).path("key").asString()).isEqualTo("AKA-1");
    assertThat(matches.get(0).path("strong").asBoolean()).isTrue();
    assertThat(matches.get(0).path("pullRequestNumber").asInt()).isEqualTo(40);
    JsonNode weak = get("/api/work-items/" + excel + "/done-matches");
    assertThat(weak).hasSize(1);
    assertThat(weak.get(0).path("strong").asBoolean()).isFalse();
    assertThat(get("/api/work-items/" + login + "/done-matches")).isEmpty();

    // Sin confirmar, no se lanza: 409 con los parecidos.
    assertThatThrownBy(() -> launch(again, repository, false))
        .isInstanceOfSatisfying(
            HttpClientErrorException.class,
            e -> {
              assertThat(e.getStatusCode().value()).isEqualTo(409);
              JsonNode body = JsonMapper.builder().build().readTree(e.getResponseBodyAsString());
              assertThat(body.path("code").asString()).isEqualTo("DONE_MATCH");
              assertThat(body.path("detail").asString())
                  .startsWith(
                      "Esto parece ya hecho: AKA-1 «Exportar las notas a PDF» se entregó"
                          + " en la PR #40");
              assertThat(body.path("doneMatches").get(0).path("key").asString()).isEqualTo("AKA-1");
            });
    assertThat(runs(again)).isZero();

    // Confirmado, se lanza y queda en la ejecución.
    UUID confirmed = launch(again, repository, true);
    assertThat(doneEvent(confirmed).path("confirmed").asBoolean()).isTrue();
    assertThat(doneEvent(confirmed).path("matches").get(0).path("key").asString())
        .isEqualTo("AKA-1");

    // Lo que se parece poco se lanza sin preguntar, pero se avisa.
    UUID hinted = launch(excel, repository, false);
    assertThat(doneEvent(hinted).path("confirmed").asBoolean()).isFalse();
    launch(login, repository, false);

    // Volver a lanzar el mismo trabajo ya entregado también pide confirmarlo.
    assertThatThrownBy(() -> launch(exported, repository, false))
        .isInstanceOfSatisfying(
            HttpClientErrorException.class,
            e -> assertThat(e.getResponseBodyAsString()).contains("este mismo trabajo"));

    // El bloque de memoria del agente lo avisa.
    UUID agent =
        jdbc.sql(
                "SELECT a.id FROM agent_run a JOIN stage_run s ON s.id = a.stage_run_id"
                    + " WHERE s.workflow_run_id = ? LIMIT 1")
            .param(hinted)
            .query(UUID.class)
            .single();
    String block =
        memoryAgents
            .promptBlock(
                agent,
                UUID.fromString(project),
                UUID.fromString(repository),
                UUID.fromString(excel),
                MemoryAgents.Mode.WRITE,
                true)
            .text();
    assertThat(block)
        .contains("Trabajos parecidos que ya se entregaron en este proyecto")
        .contains("- AKA-1 «Exportar las notas a PDF» se entregó en la PR #40")
        .contains("(parecido 50 %)");
    String none =
        memoryAgents
            .promptBlock(
                agent,
                UUID.fromString(project),
                UUID.fromString(repository),
                UUID.fromString(login),
                MemoryAgents.Mode.WRITE,
                true)
            .text();
    assertThat(none).doesNotContain("Trabajos parecidos");
  }

  private String workItem(String project, String title, String description) {
    Map<String, Object> body = new HashMap<>();
    body.put("title", title);
    body.put("type", "FEATURE");
    body.put("description", description);
    return post("/api/projects/" + project + "/work-items", body).path("id").asString();
  }

  private UUID launch(String workItem, String repository, boolean confirm) {
    Map<String, Object> body = new HashMap<>();
    body.put("repositoryId", repository);
    body.put("prompt", "haz algo");
    if (confirm) {
      body.put("confirmDone", true);
    }
    return UUID.fromString(
        post("/api/work-items/" + workItem + "/runs", body).path("id").asString());
  }

  /** La PR de la ejecución, fusionada. */
  private void merge(UUID run, UUID repository, int number) {
    record Ids(UUID stage, UUID agent) {}
    Ids ids =
        jdbc.sql(
                "SELECT s.id AS stage, a.id AS agent FROM stage_run s"
                    + " JOIN agent_run a ON a.stage_run_id = s.id WHERE s.workflow_run_id = ?"
                    + " LIMIT 1")
            .param(run)
            .query(
                (rs, n) ->
                    new Ids(rs.getObject("stage", UUID.class), rs.getObject("agent", UUID.class)))
            .single();
    Timestamp now = Timestamp.from(Instant.parse("2026-11-03T10:00:00Z"));
    jdbc.sql(
            "INSERT INTO github_pull_request (id, workflow_run_id, repository_id, stage_run_id,"
                + " agent_run_id, full_name, branch, title, body, status, number, url,"
                + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'guilu/akademia', 'b', 't',"
                + " 'b', 'MERGED', ?, ?, ?, ?)")
        .params(
            UUID.randomUUID(),
            run,
            repository,
            ids.stage(),
            ids.agent(),
            number,
            "https://github.com/guilu/akademia/pull/" + number,
            now,
            now)
        .update();
  }

  private long runs(String workItem) {
    return jdbc.sql("SELECT count(*) FROM workflow_run WHERE work_item_id = ?")
        .param(UUID.fromString(workItem))
        .query(Long.class)
        .single();
  }

  private JsonNode doneEvent(UUID run) {
    List<String> payloads =
        jdbc.sql(
                "SELECT payload::text FROM event WHERE workflow_run_id = ?"
                    + " AND event_type = 'memory.done_match'")
            .param(run)
            .query(String.class)
            .list();
    assertThat(payloads).hasSize(1);
    return JsonMapper.builder().build().readTree(payloads.getFirst());
  }

  private JsonNode post(String path, Object body) {
    return http.post().uri(path).body(body).retrieve().body(JsonNode.class);
  }

  private JsonNode get(String path) {
    return http.get().uri(path).retrieve().body(JsonNode.class);
  }
}
