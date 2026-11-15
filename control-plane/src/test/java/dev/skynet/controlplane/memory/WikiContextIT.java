package dev.skynet.controlplane.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skynet.controlplane.support.IntegrationTest;
import dev.skynet.protocol.runner.RunnerRegistration;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;

/**
 * El contexto pertinente (MV-D): el bloque de memoria lleva el índice de la wiki, los errores
 * conocidos que tocan el trabajo y las entradas que más se le parecen, antes de lo de siempre; los
 * agentes leen la wiki con {@code wiki_index} y {@code wiki_read}, y queda en {@code
 * memory_access}.
 */
class WikiContextIT extends IntegrationTest {

  private static final Timestamp DAY = Timestamp.from(Instant.parse("2026-11-10T10:00:00Z"));

  @Test
  void theBlockCarriesTheIndexAndWhatTouchesTheWork() {
    Runner runner = register();
    String project =
        post("/api/projects", Map.of("key", "WIK", "name", "notas")).path("id").asString();
    String repository =
        post(
                "/api/projects/" + project + "/repositories",
                Map.of("name", "notas", "localPath", "/repos/notas"))
            .path("id")
            .asString();
    String workItem =
        post(
                "/api/projects/" + project + "/work-items",
                Map.of("title", "Exportar las notas con tildes en src/Pdf.java", "type", "BUG"))
            .path("id")
            .asString();

    // Lo que sabe el proyecto: una trampa del fichero, una nota parecida y otra que no tiene que
    // ver.
    remember(project, "PITFALL", "El PDF pierde las tildes", "Pasa en src/Pdf.java al escribir");
    remember(project, "DISCOVERY", "Las notas se exportan por alumno", "El exportador agrupa");
    remember(project, "DISCOVERY", "Login con Google", "Usa OAuth");

    // Una ejecución anterior en la que un juez encontró un fallo que se corrigió.
    UUID before = agentId(launchAndClaim(runner, workItem, repository));
    finding(before, UUID.fromString(repository), "Falta escapar las tildes");
    post("/api/projects/" + project + "/wiki/refresh", Map.of());

    // La siguiente empieza con el bloque repartido, en este orden.
    JsonNode start = launchAndClaim(runner, workItem, repository);
    UUID agent = agentId(start);
    String prompt = start.path("start").path("prompt").asString();
    assertThat(prompt)
        .contains(MemoryContext.INDEX_TITLE + "\n- [Lo hecho](lo-hecho)")
        .contains("- [Errores conocidos](errores-conocidos)")
        .contains("### [PITFALL] El PDF pierde las tildes")
        .contains("### [HALLAZGO CORREGIDO] src/Pdf.java: Falta escapar las tildes (alta)")
        .contains("### [DISCOVERY] Las notas se exportan por alumno")
        .contains("### [DISCOVERY] Login con Google")
        .contains("wiki_read");
    assertThat(prompt.indexOf(MemoryContext.INDEX_TITLE))
        .isLessThan(prompt.indexOf(MemoryContext.ERRORS_TITLE));
    assertThat(prompt.indexOf(MemoryContext.ERRORS_TITLE))
        .isLessThan(prompt.indexOf("El PDF pierde las tildes"))
        .isLessThan(prompt.indexOf(MemoryContext.RELEVANT_TITLE));
    assertThat(prompt.indexOf(MemoryContext.RELEVANT_TITLE))
        .isLessThan(prompt.indexOf("Las notas se exportan por alumno"))
        .isLessThan(prompt.indexOf(MemoryContext.RECENT_TITLE));
    assertThat(prompt.indexOf(MemoryContext.RECENT_TITLE))
        .isLessThan(prompt.indexOf("Login con Google"));
    // Cada entrada va una sola vez.
    assertThat(prompt.split("El PDF pierde las tildes", -1)).hasSize(2);

    // mem_context da lo mismo; wiki_index y wiki_read leen la wiki.
    String token = token(runner, agent);
    assertThat(call(token, "context", Map.of()).path("text").asString())
        .contains(MemoryContext.ERRORS_TITLE, MemoryContext.RELEVANT_TITLE);
    JsonNode index = call(token, "wiki-index", Map.of());
    assertThat(index.path("text").asString()).startsWith("La wiki de notas");
    assertThat(index.path("pages").findValuesAsString("slug"))
        .containsExactlyInAnyOrder(
            "indice", "lo-hecho", "errores-conocidos", "decisiones-y-restricciones");
    JsonNode page = call(token, "wiki-read", Map.of("slug", "errores-conocidos"));
    assertThat(page.path("title").asString()).isEqualTo("Errores conocidos");
    assertThat(page.path("scope").asString()).isEqualTo("project");
    assertThat(page.path("body").asString()).contains("Falta escapar las tildes");
    assertStatus(() -> call(token, "wiki-read", Map.of("slug", "arquitectura")), 404);
    assertStatus(() -> call(token, "wiki-read", Map.of()), 400);

    // Queda en memory_access, y la web lo ve por invocación.
    assertThat(
            jdbc.sql(
                    "SELECT p.slug || ' ' || a.via FROM memory_access a"
                        + " JOIN wiki_page p ON p.id = a.wiki_page_id"
                        + " WHERE a.agent_run_id = ? ORDER BY a.id")
                .param(agent)
                .query(String.class)
                .list())
        .containsExactly("indice PROMPT", "indice CONTEXT", "indice GET", "errores-conocidos GET");
    JsonNode memory = get("/api/agent-runs/" + agent + "/memory");
    assertThat(memory.path("wiki"))
        .extracting(w -> w.path("slug").asString() + " " + w.path("via").asString())
        .contains("indice PROMPT", "errores-conocidos GET");
    assertThat(memory.path("accesses"))
        .extracting(a -> a.path("title").asString())
        .contains("El PDF pierde las tildes");
  }

  @Test
  void withoutWikiTheBlockStillWorksAndTheToolsSaySo() {
    Runner runner = register();
    String project =
        post("/api/projects", Map.of("key", "VAC", "name", "vacío")).path("id").asString();
    String repository =
        post(
                "/api/projects/" + project + "/repositories",
                Map.of("name", "vacío", "localPath", "/repos/vacio"))
            .path("id")
            .asString();
    String workItem =
        post("/api/projects/" + project + "/work-items", Map.of("title", "T", "type", "BUG"))
            .path("id")
            .asString();
    JsonNode start = launchAndClaim(runner, workItem, repository);
    assertThat(start.path("start").path("prompt").asString())
        .contains(MemoryContext.EMPTY)
        .doesNotContain(MemoryContext.INDEX_TITLE);
    String token = token(runner, agentId(start));
    assertThat(call(token, "wiki-index", Map.of()).path("text").asString())
        .isEqualTo("La wiki de este proyecto todavía está vacía.");
    assertThat(call(token, "wiki-index", Map.of()).path("pages")).isEmpty();
  }

  private record Runner(UUID id, String token) {}

  private JsonNode launchAndClaim(Runner runner, String workItem, String repository) {
    Map<String, Object> body = new HashMap<>();
    body.put("repositoryId", repository);
    body.put("prompt", "haz algo");
    body.put("confirmDone", true);
    post("/api/work-items/" + workItem + "/runs", body);
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
    return commands.get(0);
  }

  private Runner register() {
    JsonNode registered =
        http.post()
            .uri("/api/runner/register")
            .body(new RunnerRegistration("laptop", RUNNER_REGISTRATION_TOKEN, "dev", 2, "2.1.288"))
            .retrieve()
            .body(JsonNode.class);
    return new Runner(
        UUID.fromString(registered.path("runnerId").asString()),
        registered.path("token").asString());
  }

  private static UUID agentId(JsonNode command) {
    return UUID.fromString(command.path("agentRunId").asString());
  }

  private String token(Runner runner, UUID agentRunId) {
    return http.post()
        .uri("/api/runner/agent-runs/" + agentRunId + "/memory-token")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + runner.token())
        .retrieve()
        .body(JsonNode.class)
        .path("token")
        .asString();
  }

  private JsonNode call(String token, String tool, Map<String, ?> body) {
    return http.post()
        .uri("/api/runner/memory/" + tool)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .body(body)
        .retrieve()
        .body(JsonNode.class);
  }

  /** Un hallazgo corregido en la ejecución de esa invocación. */
  private void finding(UUID agentRunId, UUID repository, String text) {
    record Ids(UUID run, UUID stage) {}
    Ids ids =
        jdbc.sql(
                "SELECT s.workflow_run_id AS run, s.id AS stage FROM agent_run a"
                    + " JOIN stage_run s ON s.id = a.stage_run_id WHERE a.id = ?")
            .param(agentRunId)
            .query(
                (rs, n) ->
                    new Ids(rs.getObject("run", UUID.class), rs.getObject("stage", UUID.class)))
            .single();
    jdbc.sql(
            "INSERT INTO finding (id, repository_id, workflow_run_id, stage_run_id, round, source,"
                + " judge, file, severity, text, words, fingerprint, outcome, category,"
                + " created_at, updated_at) VALUES (?, ?, ?, ?, 1, 'judge', 'juez',"
                + " 'src/Pdf.java', 'alta', ?, ARRAY['tildes'], 'huella', 'corregido', 'errores',"
                + " ?, ?)")
        .params(UUID.randomUUID(), repository, ids.run(), ids.stage(), text, DAY, DAY)
        .update();
  }

  private void remember(String project, String kind, String title, String what) {
    Map<String, Object> body = new HashMap<>();
    body.put("kind", kind);
    body.put("title", title);
    body.put("what", what);
    post("/api/projects/" + project + "/memory", body);
  }

  private static void assertStatus(Runnable call, int status) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            HttpClientErrorException.class,
            e -> assertThat(e.getStatusCode().value()).isEqualTo(status));
  }

  private JsonNode post(String path, Object body) {
    return http.post().uri(path).body(body).retrieve().body(JsonNode.class);
  }

  private JsonNode get(String path) {
    return http.get().uri(path).retrieve().body(JsonNode.class);
  }
}
