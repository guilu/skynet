package dev.skynet.controlplane.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skynet.controlplane.artifact.BlobStore;
import dev.skynet.controlplane.memory.MemoryContent;
import dev.skynet.controlplane.memory.MemoryKind;
import dev.skynet.controlplane.memory.MemoryService;
import dev.skynet.controlplane.memory.MemorySource;
import dev.skynet.controlplane.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;

/**
 * Las páginas de la wiki (MV-C): Skynet genera sin modelo «Lo hecho», «Errores conocidos»,
 * «Decisiones y restricciones» y el «Índice» a partir de lo entregado, los hallazgos corregidos,
 * las lecciones, los escapes y la memoria confirmada; lo que no cambia no crea versión.
 */
class WikiPagesIT extends IntegrationTest {

  private static final Timestamp DAY = Timestamp.from(Instant.parse("2026-11-03T10:00:00Z"));

  @Autowired private BlobStore blobs;
  @Autowired private MemoryService memory;
  @Autowired private ApplicationEventPublisher events;

  @Test
  void theWikiIsGeneratedFromWhatHappenedInTheProject() throws Exception {
    String project =
        post("/api/projects", Map.of("key", "AKA", "name", "akademia")).path("id").asString();
    UUID projectId = UUID.fromString(project);
    UUID repository =
        UUID.fromString(
            post(
                    "/api/projects/" + project + "/repositories",
                    Map.of("name", "akademia", "localPath", "/repos/akademia"))
                .path("id")
                .asString());

    // Lo entregado: AKA-1 con la PR #40 y los ficheros de su GIT_CHANGES.
    UUID delivered = launch(workItem(project, "Exportar las notas a PDF"), repository);
    Ids ids = ids(delivered);
    UUID pullRequest = merge(delivered, repository, ids, 40);
    changes(delivered, ids, List.of("src/Pdf.java", "web/Notas.tsx"));
    // Otra ejecución, sin entregar.
    UUID other = launch(workItem(project, "Arreglar el login con Google"), repository);

    // El mismo hallazgo corregido en dos ejecuciones, y otro que sigue abierto.
    UUID first = finding(delivered, ids, repository, "corregido", null, "Falta escapar las tildes");
    finding(other, ids(other), repository, "corregido", first, "Falta escapar las tildes otra vez");
    finding(other, ids(other), repository, "abierto", null, "Esto sigue abierto");
    lesson(projectId, repository, "aplicada", "Fija la fuente del PDF antes de escribir tildes");
    lesson(projectId, repository, "propuesta", "Esto no se ha aplicado");
    escape(delivered, repository, pullRequest);

    // La memoria: lo de una persona está confirmado; lo de un agente, todavía no.
    remember(project, "PITFALL", "El PDF pierde las tildes", "Usa DejaVuSans");
    remember(project, "CONSTRAINT", "Sin dependencias nuevas de PDF", "Solo la librería actual");
    remember(project, "DECISION", "Exportar con OpenPDF", "Ya la usa el informe");
    memory.save(
        projectId,
        repository,
        new MemoryContent(
            MemoryKind.PITFALL, "Sin confirmar", null, "Lo dijo un agente", null, null, null, null),
        MemorySource.agent(other, ids(other).agent()));

    // Al terminar una ejecución se generan las cuatro páginas.
    events.publishEvent(new WorkflowRunFinished(other));
    JsonNode list = get("/api/projects/" + project + "/wiki");
    assertThat(list.findValuesAsString("slug"))
        .containsExactly("decisiones-y-restricciones", "errores-conocidos", "indice", "lo-hecho");
    assertThat(list.get(0).path("origin").asString()).isEqualTo("GENERATED");

    JsonNode done = get("/api/projects/" + project + "/wiki/lo-hecho");
    assertThat(done.path("title").asString()).isEqualTo("Lo hecho");
    assertThat(done.path("body").asString())
        .contains(
            "- **AKA-1** «Exportar las notas a PDF»: [PR #40](https://github.com/guilu/akademia"
                + "/pull/40), 3/11/2026, en akademia. Ficheros: `src/Pdf.java`, `web/Notas.tsx`.")
        .doesNotContain("AKA-2");
    assertThat(done.path("sources").get(0).path("kind").asString()).isEqualTo("pull_request");
    assertThat(done.path("sources").get(0).path("label").asString()).isEqualTo("AKA-1 · PR #40");

    String errors =
        get("/api/projects/" + project + "/wiki/errores-conocidos").path("body").asString();
    assertThat(errors)
        .contains(
            "`src/Pdf.java` (akademia), alta: Falta escapar las tildes otra vez (en 2 ejecuciones)")
        .doesNotContain("Esto sigue abierto")
        .contains("akademia, errores: Fija la fuente del PDF antes de escribir tildes")
        .doesNotContain("Esto no se ha aplicado")
        .contains("- **El PDF pierde las tildes**: Usa DejaVuSans")
        .doesNotContain("Sin confirmar")
        .contains("**AKA-1** «Exportar las notas a PDF» ([PR #40]")
        .contains("se revirtió o falló tras fusionarse el 3/11/2026: Revertida por romper el PDF");

    String decisions =
        get("/api/projects/" + project + "/wiki/decisiones-y-restricciones")
            .path("body")
            .asString();
    assertThat(decisions)
        .contains(
            "## Restricciones\n\n- **Sin dependencias nuevas de PDF**: Solo la librería actual")
        .contains("## Decisiones\n\n- **Exportar con OpenPDF**");

    String index = get("/api/projects/" + project + "/wiki/indice").path("body").asString();
    assertThat(index)
        .startsWith("La wiki de akademia")
        .contains("- [Lo hecho](lo-hecho): 1 trabajo entregado; el último, AKA-1 el 3/11/2026.")
        .contains(
            "- [Errores conocidos](errores-conocidos): 1 fallo corregido, 1 lección, 1 trampa o"
                + " bug, 1 escape.")
        .contains(
            "- [Decisiones y restricciones](decisiones-y-restricciones): 1 restricción, 1 decisión.");

    // Regenerar sin cambios no crea versión; con una decisión nueva, sí, y queda el historial.
    post("/api/projects/" + project + "/wiki/refresh", Map.of());
    assertThat(
            get("/api/projects/" + project + "/wiki/decisiones-y-restricciones")
                .path("version")
                .asInt())
        .isEqualTo(1);
    remember(project, "DECISION", "Paginar el PDF por alumno", "Lo piden los profesores");
    JsonNode refreshed = post("/api/projects/" + project + "/wiki/refresh", Map.of());
    assertThat(refreshed).hasSize(4);
    JsonNode page = get("/api/projects/" + project + "/wiki/decisiones-y-restricciones");
    assertThat(page.path("version").asInt()).isEqualTo(2);
    assertThat(page.path("body").asString()).contains("Paginar el PDF por alumno");
    JsonNode history =
        get("/api/projects/" + project + "/wiki/decisiones-y-restricciones/revisions");
    assertThat(history).hasSize(2);
    assertThat(history.get(0).path("version").asInt()).isEqualTo(2);
    assertThat(
            get("/api/projects/" + project + "/wiki/decisiones-y-restricciones/revisions/1")
                .path("body")
                .asString())
        .doesNotContain("Paginar");
    assertThat(get("/api/projects/" + project + "/wiki/lo-hecho").path("version").asInt())
        .isEqualTo(1);

    assertThatThrownBy(() -> get("/api/projects/" + project + "/wiki/arquitectura"))
        .isInstanceOfSatisfying(
            HttpClientErrorException.class,
            e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
  }

  @Test
  void anEmptyProjectSaysThereIsNothingYet() {
    String project =
        post("/api/projects", Map.of("key", "VAC", "name", "vacío")).path("id").asString();
    post("/api/projects/" + project + "/wiki/refresh", Map.of());
    assertThat(get("/api/projects/" + project + "/wiki/lo-hecho").path("body").asString())
        .contains("Todavía no se ha entregado nada.");
    assertThat(get("/api/projects/" + project + "/wiki/indice").path("body").asString())
        .contains("- [Lo hecho](lo-hecho): todavía no se ha entregado nada.");
  }

  private record Ids(UUID stage, UUID agent) {}

  private String workItem(String project, String title) {
    Map<String, Object> body = new HashMap<>();
    body.put("title", title);
    body.put("type", "FEATURE");
    return post("/api/projects/" + project + "/work-items", body).path("id").asString();
  }

  private UUID launch(String workItem, UUID repository) {
    Map<String, Object> body = new HashMap<>();
    body.put("repositoryId", repository.toString());
    body.put("prompt", "haz algo");
    body.put("confirmDone", true);
    return UUID.fromString(
        post("/api/work-items/" + workItem + "/runs", body).path("id").asString());
  }

  private Ids ids(UUID run) {
    return jdbc.sql(
            "SELECT s.id AS stage, a.id AS agent FROM stage_run s"
                + " JOIN agent_run a ON a.stage_run_id = s.id WHERE s.workflow_run_id = ? LIMIT 1")
        .param(run)
        .query(
            (rs, n) ->
                new Ids(rs.getObject("stage", UUID.class), rs.getObject("agent", UUID.class)))
        .single();
  }

  private UUID merge(UUID run, UUID repository, Ids ids, int number) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO github_pull_request (id, workflow_run_id, repository_id, stage_run_id,"
                + " agent_run_id, full_name, branch, title, body, status, number, url,"
                + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'guilu/akademia', 'b', 't',"
                + " 'b', 'MERGED', ?, ?, ?, ?)")
        .params(
            id,
            run,
            repository,
            ids.stage(),
            ids.agent(),
            number,
            "https://github.com/guilu/akademia/pull/" + number,
            DAY,
            DAY)
        .update();
    return id;
  }

  /** El artefacto GIT_CHANGES de la ejecución, con esos ficheros. */
  private void changes(UUID run, Ids ids, List<String> files) throws Exception {
    StringBuilder json = new StringBuilder("{\"branch\":\"b\",\"files\":[");
    for (int i = 0; i < files.size(); i++) {
      json.append(i > 0 ? "," : "").append("{\"path\":\"").append(files.get(i)).append("\"}");
    }
    byte[] content = json.append("]}").toString().getBytes(StandardCharsets.UTF_8);
    String sha =
        java.util.HexFormat.of()
            .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
    String uri = blobs.put(sha, content);
    jdbc.sql(
            "INSERT INTO artifact (id, workflow_run_id, stage_run_id, agent_run_id, type, name,"
                + " media_type, size, sha256, uri, truncated, metadata, created_at)"
                + " VALUES (?, ?, ?, ?, 'GIT_CHANGES', 'changes.json', 'application/json', ?, ?,"
                + " ?, false, '{\"branch\":\"b\"}'::jsonb, ?)")
        .params(UUID.randomUUID(), run, ids.stage(), ids.agent(), content.length, sha, uri, DAY)
        .update();
  }

  private UUID finding(
      UUID run, Ids ids, UUID repository, String outcome, UUID firstSeen, String text) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO finding (id, repository_id, workflow_run_id, stage_run_id, round, source,"
                + " judge, file, severity, text, words, fingerprint, outcome, first_seen_id,"
                + " category, created_at, updated_at) VALUES (?, ?, ?, ?, 1, 'judge', 'juez',"
                + " 'src/Pdf.java', 'alta', ?, ARRAY['tildes'], 'huella', ?, ?, 'errores', ?, ?)")
        .params(
            id,
            repository,
            run,
            ids.stage(),
            text,
            outcome,
            firstSeen,
            firstSeen == null ? DAY : Timestamp.from(DAY.toInstant().plusSeconds(60)),
            firstSeen == null ? DAY : Timestamp.from(DAY.toInstant().plusSeconds(60)))
        .update();
    return id;
  }

  private void lesson(UUID project, UUID repository, String status, String text) {
    jdbc.sql(
            "INSERT INTO lesson (id, project_id, repository_id, category, kind, status,"
                + " suggested_text, runs, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, 'prevenir', ?, ?, 2, ?, ?)")
        .params(
            UUID.randomUUID(),
            project,
            repository,
            status.equals("aplicada") ? "errores" : "tests",
            status,
            text,
            DAY,
            DAY)
        .update();
  }

  private void escape(UUID run, UUID repository, UUID pullRequest) {
    jdbc.sql(
            "INSERT INTO judge_escape (id, repository_id, workflow_run_id, pull_request_id,"
                + " origin, note, created_by, created_at)"
                + " VALUES (?, ?, ?, ?, 'manual', 'Revertida por romper el PDF', 'diego', ?)")
        .params(UUID.randomUUID(), repository, run, pullRequest, DAY)
        .update();
  }

  private void remember(String project, String kind, String title, String what) {
    Map<String, Object> body = new HashMap<>();
    body.put("kind", kind);
    body.put("title", title);
    body.put("what", what);
    post("/api/projects/" + project + "/memory", body);
  }

  private JsonNode post(String path, Object body) {
    return http.post().uri(path).body(body).retrieve().body(JsonNode.class);
  }

  private JsonNode get(String path) {
    return http.get().uri(path).retrieve().body(JsonNode.class);
  }
}
