package dev.skynet.controlplane.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skynet.controlplane.support.IntegrationTest;
import dev.skynet.protocol.AgentEventType;
import dev.skynet.protocol.NormalizedEvent;
import dev.skynet.protocol.runner.EventBatch;
import dev.skynet.protocol.runner.RunnerRegistration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;

/**
 * El curador de la memoria (MV-E): una fase con {@code memory: curate} recibe lo confirmado desde
 * la última curación, lee como {@code read} y, al terminar bien, su salida escribe páginas de la
 * wiki y deja un informe con propuestas que decide una persona.
 */
class MemoryCurationIT extends IntegrationTest {

  private static final String YAML =
      """
      id: curar-prueba
      name: Curar prueba
      stages:
        - id: curar
          name: Cura
          type: agent
          phase: review
          memory: curate
          prompt: Cura la memoria
      """;

  @Autowired private WikiPages wiki;

  @Test
  void theCuratorWritesPagesAndProposesWhatAPersonDecides() {
    String token = register();
    String project =
        post("/api/projects", Map.of("key", "CUR", "name", "curada")).path("id").asString();
    String repository =
        post(
                "/api/projects/" + project + "/repositories",
                Map.of("name", "curada", "localPath", "/repos/curada"))
            .path("id")
            .asString();
    String workItem =
        post(
                "/api/projects/" + project + "/work-items",
                Map.of("title", "Curar la memoria", "type", "REFACTOR"))
            .path("id")
            .asString();
    String squash = remember(project, "DECISION", "Fusionar con squash", "Las PRs van con squash");
    String repeated = remember(project, "DECISION", "Las PRs van con squash", "Squash siempre");
    String stale = remember(project, "CONFIG", "Usar Java 17", "El proyecto compila con 17");
    // Una página de una persona: el curador no la toca.
    wiki.write(
        UUID.fromString(project),
        UUID.fromString(repository),
        "convenciones",
        "Convenciones",
        "Lo escribió Diego",
        List.of(),
        WikiOrigin.HUMAN,
        "diego");

    String definition = publish(YAML);
    JsonNode start = launchAndClaim(token, workItem, repository, definition);
    String prompt = start.path("start").path("prompt").asString();
    assertThat(prompt)
        .contains(MemoryAgents.INBOX_TITLE + " (es la primera)")
        .contains("- [DECISION] Fusionar con squash (proyecto, v1, id " + squash + ")")
        .contains("Las PRs van con squash", "Usar Java 17")
        .contains(MemoryAgents.CURATE_OUTPUT)
        .contains(MemoryAgents.TOOLS_READ);

    // Lee como `read`: su token no guarda.
    UUID agent = UUID.fromString(start.path("agentRunId").asString());
    JsonNode issued = issue(token, agent);
    assertThat(issued.path("mode").asString()).isEqualTo("READ");
    assertStatus(
        () ->
            memoryCall(
                issued.path("token").asString(),
                "save",
                Map.of("kind", "PATTERN", "title", "No", "what", "x")),
        403);

    // Termina bien con su salida: páginas y propuestas, unas que valen y otras que no.
    Map<String, Object> output = new LinkedHashMap<>();
    output.put("summary", "Dos decisiones repetidas y una configuración vieja.");
    output.put(
        "pages",
        List.of(
            Map.of(
                "slug",
                "arquitectura",
                "title",
                "Arquitectura",
                "body",
                "Un control plane y un runner.",
                "sources",
                List.of(squash, UUID.randomUUID().toString())),
            Map.of(
                "slug",
                "modulos",
                "title",
                "Módulos",
                "body",
                "control-plane, runner",
                "scope",
                "project"),
            Map.of("slug", "indice", "title", "Índice", "body", "Mío"),
            Map.of("slug", "convenciones", "title", "Convenciones", "body", "Otra cosa"),
            Map.of("slug", "Mal Nombre", "title", "Mal", "body", "x")));
    output.put(
        "proposals",
        List.of(
            Map.of(
                "action",
                "merge",
                "into",
                squash,
                "observations",
                List.of(repeated, squash),
                "reason",
                "Dicen lo mismo"),
            Map.of("action", "archive", "observations", List.of(stale), "reason", "Ya es Java 21"),
            Map.of(
                "action",
                "archive",
                "observations",
                List.of(UUID.randomUUID().toString()),
                "reason",
                "No existe"),
            Map.of("action", "archive", "observations", List.of(stale)),
            Map.of("action", "borrar", "observations", List.of(stale), "reason", "x")));
    complete(token, agent, output);

    JsonNode curations = get("/api/projects/" + project + "/memory/curations");
    assertThat(curations).hasSize(1);
    JsonNode curation = curations.get(0);
    assertThat(curation.path("summary").asString())
        .isEqualTo("Dos decisiones repetidas y una configuración vieja.");
    assertThat(curation.path("agentRunId").asString()).isEqualTo(agent.toString());
    assertThat(curation.path("pages"))
        .extracting(p -> p.path("slug").asString() + " " + p.path("scope").asString())
        .containsExactly("arquitectura repository", "modulos project");
    assertThat(curation.path("rejected"))
        .extracting(r -> r.path("item").asString())
        .contains("indice", "convenciones", "mal nombre")
        .hasSize(6);
    assertThat(curation.path("proposals"))
        .extracting(p -> p.path("action").asString() + " " + p.path("status").asString())
        .containsExactly("MERGE PENDING", "ARCHIVE PENDING");
    JsonNode merge = curation.path("proposals").get(0);
    assertThat(merge.path("intoId").asString()).isEqualTo(squash);
    assertThat(merge.path("observationIds").get(0).asString()).isEqualTo(repeated);
    assertThat(merge.path("observationIds")).hasSize(1);

    // Las páginas, con origen CURATOR y sus fuentes; la de la persona sigue igual.
    JsonNode architecture =
        get("/api/projects/" + project + "/wiki/arquitectura?repositoryId=" + repository);
    assertThat(architecture.path("origin").asString()).isEqualTo("CURATOR");
    assertThat(architecture.path("updatedBy").asString()).isEqualTo(MemoryCuration.ACTOR);
    assertThat(architecture.path("sources")).hasSize(1);
    assertThat(architecture.path("sources").get(0).path("label").asString())
        .isEqualTo("Fusionar con squash");
    assertThat(get("/api/projects/" + project + "/wiki/modulos").path("body").asString())
        .isEqualTo("control-plane, runner");
    assertThat(
            get("/api/projects/" + project + "/wiki/convenciones?repositoryId=" + repository)
                .path("body")
                .asString())
        .isEqualTo("Lo escribió Diego");
    // El índice se regeneró y las nombra.
    assertThat(get("/api/projects/" + project + "/wiki/indice").path("body").asString())
        .contains("[Arquitectura](arquitectura)", "[Módulos](modulos)");

    // Una persona decide: fusionar archiva la repetida; rechazar no cambia nada.
    JsonNode pending = get("/api/projects/" + project + "/memory/proposals?status=PENDING");
    assertThat(pending).hasSize(2);
    JsonNode accepted =
        post("/api/memory/proposals/" + merge.path("id").asString() + "/accept", Map.of());
    assertThat(accepted.path("status").asString()).isEqualTo("ACCEPTED");
    assertThat(accepted.path("decidedBy").asString()).isEqualTo(ADMIN_USER);
    assertThat(get("/api/memory/" + repeated).path("archivedAt").isNull()).isFalse();
    assertThat(get("/api/memory/" + squash).path("archivedAt").isNull()).isTrue();
    String archive = curation.path("proposals").get(1).path("id").asString();
    assertThat(
            post("/api/memory/proposals/" + archive + "/reject", Map.of())
                .path("status")
                .asString())
        .isEqualTo("REJECTED");
    assertThat(get("/api/memory/" + stale).path("archivedAt").isNull()).isTrue();
    assertStatus(() -> post("/api/memory/proposals/" + archive + "/accept", Map.of()), 409);
    assertStatus(
        () -> post("/api/memory/proposals/" + UUID.randomUUID() + "/accept", Map.of()), 404);
    assertThat(get("/api/projects/" + project + "/memory/proposals?status=PENDING")).isEmpty();
    assertThat(get("/api/projects/" + project + "/memory/proposals")).hasSize(2);

    // La siguiente curación empieza por lo nuevo desde esta.
    JsonNode next = launchAndClaim(token, workItem, repository, definition);
    assertThat(next.path("start").path("prompt").asString())
        .contains("No hay nada confirmado nuevo desde la última curación")
        .doesNotContain("(es la primera)");
    remember(project, "PITFALL", "Cuidado con el runner", "Se cuelga sin token");
    JsonNode third = launchAndClaim(token, workItem, repository, definition);
    assertThat(third.path("start").path("prompt").asString())
        .contains("- [PITFALL] Cuidado con el runner")
        .doesNotContain("- [DECISION] Fusionar con squash");
  }

  @Test
  void aCuratorThatReturnsNothingLeavesAnEmptyReport() {
    String token = register();
    String project =
        post("/api/projects", Map.of("key", "NAD", "name", "nada")).path("id").asString();
    String repository =
        post(
                "/api/projects/" + project + "/repositories",
                Map.of("name", "nada", "localPath", "/repos/nada"))
            .path("id")
            .asString();
    String workItem =
        post("/api/projects/" + project + "/work-items", Map.of("title", "T", "type", "REFACTOR"))
            .path("id")
            .asString();
    JsonNode start = launchAndClaim(token, workItem, repository, publish(YAML));
    assertThat(start.path("start").path("prompt").asString())
        .contains("No hay nada confirmado nuevo.");
    complete(token, UUID.fromString(start.path("agentRunId").asString()), null);
    JsonNode curation = get("/api/projects/" + project + "/memory/curations").get(0);
    assertThat(curation.path("summary").asString()).isEqualTo("El curador no devolvió nada.");
    assertThat(curation.path("pages")).isEmpty();
    assertThat(curation.path("proposals")).isEmpty();
  }

  // Ayudas

  private String register() {
    return http.post()
        .uri("/api/runner/register")
        .body(new RunnerRegistration("laptop", RUNNER_REGISTRATION_TOKEN, "dev", 2, "2.1.288"))
        .retrieve()
        .body(JsonNode.class)
        .path("token")
        .asString();
  }

  private String publish(String yaml) {
    JsonNode draft = post("/api/workflows", Map.of("sourceYaml", yaml));
    String id = draft.path("id").asString();
    post(
        "/api/workflow-versions/" + id + "/publish",
        Map.of("revision", draft.path("revision").asLong()));
    return id;
  }

  private JsonNode launchAndClaim(
      String token, String workItem, String repository, String definition) {
    Map<String, Object> body = new HashMap<>();
    body.put("repositoryId", repository);
    body.put("definitionId", definition);
    body.put("confirmDone", true);
    post("/api/work-items/" + workItem + "/runs", body);
    JsonNode commands =
        http.get()
            .uri("/api/runner/commands?waitSeconds=1")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .retrieve()
            .body(JsonNode.class);
    assertThat(commands).hasSize(1);
    http.post()
        .uri("/api/runner/commands/" + commands.get(0).path("id").asString() + "/ack")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .retrieve()
        .toBodilessEntity();
    return commands.get(0);
  }

  /** El agente prepara su worktree y termina bien, con esa salida estructurada. */
  private void complete(String token, UUID agent, Map<String, Object> output) {
    List<NormalizedEvent> events = new ArrayList<>();
    event(
        events,
        agent,
        AgentEventType.WORKSPACE_READY,
        Map.of("path", "/w/" + agent, "branch", "skynet/" + agent, "baseCommit", "abc1234"));
    event(events, agent, AgentEventType.SESSION_STARTED, Map.of("sessionId", UUID.randomUUID()));
    Map<String, Object> result =
        new LinkedHashMap<>(Map.of("subtype", "success", "isError", false));
    if (output != null) {
      result.put("structuredOutput", output);
    }
    event(events, agent, AgentEventType.RESULT, result);
    event(events, agent, AgentEventType.PROCESS_EXITED, Map.of("exitCode", 0));
    http.post()
        .uri("/api/runner/events")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .body(new EventBatch(events))
        .retrieve()
        .toBodilessEntity();
  }

  private static void event(
      List<NormalizedEvent> events, UUID agent, AgentEventType type, Map<String, ?> payload) {
    events.add(
        new NormalizedEvent(
            UUID.randomUUID(),
            agent,
            events.size() + 1,
            Instant.now(),
            type,
            new LinkedHashMap<>(payload)));
  }

  private JsonNode issue(String token, UUID agent) {
    return http.post()
        .uri("/api/runner/agent-runs/" + agent + "/memory-token")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .retrieve()
        .body(JsonNode.class);
  }

  private JsonNode memoryCall(String token, String tool, Map<String, ?> body) {
    return http.post()
        .uri("/api/runner/memory/" + tool)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .body(body)
        .retrieve()
        .body(JsonNode.class);
  }

  private String remember(String project, String kind, String title, String what) {
    Map<String, Object> body = new HashMap<>();
    body.put("kind", kind);
    body.put("title", title);
    body.put("what", what);
    return post("/api/projects/" + project + "/memory", body).path("id").asString();
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
