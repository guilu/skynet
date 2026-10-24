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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * El protocolo de memoria de cada ejecución (ME-C): el bloque al principio del prompt, la clave
 * {@code memory} de las fases y la bitácora {@code RUN_LOG} al terminar.
 */
class MemoryProtocolIT extends IntegrationTest {

  private static final String STAGES =
      """
      id: memoria
      version: 1
      stages:
        - id: investigar
          type: agent
          prompt: Investiga
          memory: read
        - id: aplicar
          type: agent
          prompt: Aplica
          dependsOn: [investigar]
          memory: off
      """;

  private String projectId;
  private String repositoryId;
  private String otherRepositoryId;
  private String workItemId;
  private String token;

  @BeforeEach
  void setUp() {
    projectId =
        post("/api/projects", Map.of("key", "MEMO", "name", "Memoria")).path("id").asString();
    repositoryId = repository("demo");
    otherRepositoryId = repository("web");
    workItemId =
        post("/api/projects/" + projectId + "/work-items", Map.of("title", "T", "type", "BUG"))
            .path("id")
            .asString();
    token =
        http.post()
            .uri("/api/runner/register")
            .body(new RunnerRegistration("laptop", RUNNER_REGISTRATION_TOKEN, "dev", 4, "2.1.288"))
            .retrieve()
            .body(JsonNode.class)
            .path("token")
            .asString();
  }

  @Test
  void aNewRunStartsWithThePinnedEntriesAndTheRunLogOfItsRepository() {
    String pinned = save(repositoryId, "Fijada de demo", "Usa Gradle");
    post("/api/memory/" + pinned + "/pin", Map.of());
    save(null, "De todo el proyecto", "Las PRs van con squash");
    save(otherRepositoryId, "Del otro repo", "No debe llegar");
    String archived = save(repositoryId, "Archivada", "Tampoco debe llegar");
    post("/api/memory/" + archived + "/archive", Map.of());

    JsonNode first = launchAdhoc();
    String prompt = first.path("start").path("prompt").asString();
    assertThat(prompt)
        .startsWith(MemoryAgents.BLOCK_START + "\n" + MemoryContext.HEADER)
        .contains("Fijada de demo", "Usa Gradle", "De todo el proyecto", MemoryAgents.TOOLS_WRITE)
        .doesNotContain("Del otro repo", "Archivada")
        .endsWith(MemoryAgents.BLOCK_END + "\n\nhaz algo");
    assertThat(first.path("start").path("memory").asString()).isEqualTo("write");
    // La fijada va primero.
    assertThat(prompt.indexOf("Fijada de demo")).isLessThan(prompt.indexOf("De todo el proyecto"));
    // Se guarda el prompt de la fase; agent.spawned y memory_access dicen qué recibió.
    UUID agent = agentId(first);
    assertThat(
            jdbc.sql("SELECT content FROM prompt WHERE agent_run_id = ?")
                .param(agent)
                .query(String.class)
                .single())
        .isEqualTo("haz algo");
    JsonNode spawned = spawned(agent).path("memory");
    assertThat(spawned.path("mode").asString()).isEqualTo("WRITE");
    assertThat(spawned.path("context"))
        .extracting(r -> r.path("id").asString())
        .hasSize(2)
        .contains(pinned);
    assertThat(
            jdbc.sql("SELECT count(*) FROM memory_access WHERE agent_run_id = ? AND via = 'PROMPT'")
                .param(agent)
                .query(Long.class)
                .single())
        .isEqualTo(2);

    // Al terminar, la bitácora queda en su repositorio.
    finish(first, Map.of("verdict", "ok"));
    JsonNode log = runLog(first.path("start").path("workflowRunId").asString());
    assertThat(log.path("repositoryId").asString()).isEqualTo(repositoryId);
    assertThat(log.path("origin").asString()).isEqualTo("SYSTEM");
    assertThat(log.path("title").asString()).contains("adhoc", "terminada bien");
    assertThat(log.path("what").asString())
        .contains(
            "Estado: SUCCEEDED", "Fases: agent SUCCEEDED", "Rama: skynet/a1", "Enlace: /runs/");
    assertThat(log.path("learned").asString()).contains("{\"verdict\":\"ok\"}");

    // La siguiente ejecución la recibe; el otro repositorio, no.
    JsonNode second = launchAdhoc();
    assertThat(second.path("start").path("prompt").asString())
        .contains(log.path("title").asString());
    finish(second, null);
    JsonNode inOther = launchAdhoc(otherRepositoryId);
    assertThat(inOther.path("start").path("prompt").asString())
        .contains("Del otro repo", "De todo el proyecto")
        .doesNotContain("Fijada de demo", "[RUN_LOG]");
  }

  @Test
  void aReadStageCannotSaveAndAnOffStageHasNeitherBlockNorTools() {
    save(repositoryId, "Algo sabido", "Usa Gradle");
    String definitionId = publish(STAGES);
    post(
        "/api/work-items/" + workItemId + "/runs",
        Map.of("repositoryId", repositoryId, "definitionId", definitionId, "inputs", Map.of()));

    JsonNode read = claim().getFirst();
    assertThat(read.path("start").path("memory").asString()).isEqualTo("read");
    assertThat(read.path("start").path("prompt").asString())
        .contains("Algo sabido", MemoryAgents.TOOLS_READ)
        .doesNotContain("mem_session_summary")
        .endsWith("\n\nInvestiga");
    JsonNode issued = issue(read);
    assertThat(issued.path("mode").asString()).isEqualTo("READ");
    String readToken = issued.path("token").asString();
    assertThat(memory(readToken, "search", Map.of()).path("total").asLong()).isEqualTo(1);
    assertStatus(
        () -> memory(readToken, "save", Map.of("kind", "PATTERN", "title", "No", "what", "x")),
        403);

    finish(read, null);
    JsonNode off = claim().getFirst();
    assertThat(off.path("start").path("memory").isNull()).isTrue();
    assertThat(off.path("start").path("prompt").asString()).isEqualTo("Aplica");
    assertThat(spawned(agentId(off)).has("memory")).isFalse();
    assertStatus(() -> issue(off), 409);
  }

  @Test
  void theBlockLeavesOutWholeEntriesOverItsLimit() {
    String what = "x".repeat(1_500);
    List<String> titles = List.of("Primera", "Segunda", "Tercera");
    titles.forEach(t -> save(repositoryId, t, t + " " + what));

    JsonNode start = launchAdhoc();
    String prompt = start.path("start").path("prompt").asString();
    JsonNode spawned = spawned(agentId(start)).path("memory");
    assertThat(spawned.path("omitted").asInt()).isPositive();
    assertThat(spawned.path("context").size() + spawned.path("omitted").asInt()).isEqualTo(3);
    // Cada entrada llega entera o no llega.
    for (String title : titles) {
      assertThat(prompt.contains(title + " " + what) == prompt.contains("] " + title + " ("))
          .as(title)
          .isTrue();
    }
    assertThat(prompt).contains("mem_search");
  }

  private JsonNode launchAdhoc() {
    return launchAdhoc(repositoryId);
  }

  private JsonNode launchAdhoc(String repository) {
    post(
        "/api/work-items/" + workItemId + "/runs",
        Map.of("repositoryId", repository, "prompt", "haz algo"));
    List<JsonNode> claimed = claim();
    assertThat(claimed).hasSize(1);
    return claimed.getFirst();
  }

  private List<JsonNode> claim() {
    JsonNode commands =
        http.get()
            .uri("/api/runner/commands?waitSeconds=1")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .retrieve()
            .body(JsonNode.class);
    List<JsonNode> claimed = new ArrayList<>();
    for (JsonNode command : commands) {
      http.post()
          .uri("/api/runner/commands/" + command.path("id").asString() + "/ack")
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
          .retrieve()
          .toBodilessEntity();
      claimed.add(command);
    }
    return claimed;
  }

  /** El agente prepara su worktree y termina bien, con {@code output} como salida estructurada. */
  private void finish(JsonNode command, Map<String, Object> output) {
    UUID agent = agentId(command);
    List<NormalizedEvent> events = new ArrayList<>();
    Map<String, Object> result =
        new LinkedHashMap<>(Map.of("subtype", "success", "isError", false));
    if (output != null) {
      result.put("structuredOutput", output);
    }
    List.of(
            Map.entry(
                AgentEventType.WORKSPACE_READY,
                Map.<String, Object>of(
                    "path", "/w/memo/a1", "branch", "skynet/a1", "baseCommit", "abc1234")),
            Map.entry(AgentEventType.SESSION_STARTED, Map.<String, Object>of("sessionId", "s")),
            Map.entry(AgentEventType.RESULT, result),
            Map.entry(AgentEventType.PROCESS_EXITED, Map.<String, Object>of("exitCode", 0)))
        .forEach(
            e ->
                events.add(
                    new NormalizedEvent(
                        UUID.randomUUID(),
                        agent,
                        events.size() + 1,
                        Instant.now(),
                        e.getKey(),
                        new LinkedHashMap<>(e.getValue()))));
    http.post()
        .uri("/api/runner/events")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .body(new EventBatch(events))
        .retrieve()
        .toBodilessEntity();
  }

  private JsonNode runLog(String runId) {
    String id =
        jdbc.sql("SELECT id FROM memory_observation WHERE kind = 'RUN_LOG' AND topic_key = ?")
            .param("bitacora/" + runId)
            .query(UUID.class)
            .single()
            .toString();
    return get("/api/memory/" + id);
  }

  private JsonNode spawned(UUID agent) {
    return JsonMapper.builder()
        .build()
        .readTree(
            jdbc.sql(
                    "SELECT CAST(payload AS text) FROM event WHERE aggregate_id = ?"
                        + " AND event_type = 'agent.spawned'")
                .param(agent)
                .query(String.class)
                .single());
  }

  private JsonNode issue(JsonNode command) {
    return http.post()
        .uri("/api/runner/agent-runs/" + command.path("agentRunId").asString() + "/memory-token")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .retrieve()
        .body(JsonNode.class);
  }

  private JsonNode memory(String memoryToken, String operation, Map<String, ?> body) {
    return http.post()
        .uri("/api/runner/memory/" + operation)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + memoryToken)
        .body(body)
        .retrieve()
        .body(JsonNode.class);
  }

  private String save(String repository, String title, String what) {
    Map<String, Object> body = new HashMap<>();
    body.put("repositoryId", repository);
    body.put("kind", "DISCOVERY");
    body.put("title", title);
    body.put("what", what);
    return post("/api/projects/" + projectId + "/memory", body).path("id").asString();
  }

  private String repository(String name) {
    return post(
            "/api/projects/" + projectId + "/repositories",
            Map.of("name", name, "localPath", "/repos/memo-" + name))
        .path("id")
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

  private static UUID agentId(JsonNode command) {
    return UUID.fromString(command.path("agentRunId").asString());
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
