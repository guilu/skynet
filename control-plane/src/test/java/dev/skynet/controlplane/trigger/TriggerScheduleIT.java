package dev.skynet.controlplane.trigger;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skynet.controlplane.support.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/** Los disparadores programados (DS-C): validación, disparo, salto y el interruptor. */
class TriggerScheduleIT extends IntegrationTest {

  static final String WORKFLOW =
      """
      id: semanal
      name: Investigar mejoras
      version: 1
      inputs:
        foco:
          type: string
          required: false
      stages:
        - id: investigar
          type: agent
          prompt: "Investiga {{inputs.foco}}"
      """;

  @DynamicPropertySource
  static void schedules(DynamicPropertyRegistry registry) {
    registry.add("skynet.triggers.schedule-interval", () -> "100ms");
  }

  private String projectId;
  private String repositoryId;

  @BeforeEach
  void setUp() {
    projectId = post("/api/projects", Map.of("key", "CRON", "name", "Cron")).path("id").asString();
    repositoryId =
        post(
                "/api/projects/" + projectId + "/repositories",
                Map.of("name", "demo-repo", "localPath", "/repos/demo"))
            .path("id")
            .asString();
    JsonNode draft = post("/api/workflows", Map.of("sourceYaml", WORKFLOW));
    post(
        "/api/workflow-versions/" + draft.path("id").asString() + "/publish",
        Map.of("revision", draft.path("revision").asLong()));
  }

  @Test
  void aScheduleLaunchesOnItsOwnWorkItemAndSkipsWhileTheLastOneRuns() {
    JsonNode rule =
        post(
            "/api/repositories/" + repositoryId + "/triggers",
            Map.of(
                "kind",
                "SCHEDULE",
                "workflowKey",
                "semanal",
                "cron",
                "0 0 9 * * MON",
                "workItemTitle",
                "Investigar mejoras (semanal)",
                "inputs",
                Map.of("foco", "tests")));
    String id = rule.path("id").asString();
    assertThat(rule.path("label").isNull()).isTrue();
    JsonNode schedule = rule.path("schedule");
    assertThat(schedule.path("timeZone").asString()).isEqualTo("Europe/Madrid");
    Instant next = Instant.parse(schedule.path("nextFireAt").asString());
    assertThat(next).isAfter(Instant.now());
    // Un lunes a las 9:00 en Madrid: 7:00 u 8:00 UTC según el horario.
    assertThat(next.atZone(java.time.ZoneId.of("Europe/Madrid")).getHour()).isEqualTo(9);

    // Con el interruptor apagado no lanza.
    post("/api/triggers/" + id + "/fire", Map.of());
    JsonNode rejected = awaitOutcome(id, "REJECTED");
    assertThat(rejected.path("schedule").path("lastReason").asString()).contains("apagados");
    assertThat(Instant.parse(rejected.path("schedule").path("nextFireAt").asString()))
        .isAfter(Instant.now());

    put("/api/triggers/settings", Map.of("enabled", true));
    post("/api/triggers/" + id + "/fire", Map.of());
    JsonNode launched = awaitOutcome(id, "LAUNCHED");
    String runId = launched.path("schedule").path("lastRunId").asString();
    String workItemId = launched.path("schedule").path("workItemId").asString();
    JsonNode item = get("/api/work-items/" + workItemId);
    assertThat(item.path("title").asString()).isEqualTo("Investigar mejoras (semanal)");
    assertThat(item.path("externalRef").asString()).isEqualTo("skynet:schedule:" + id);
    assertThat(
            jdbc.sql("SELECT inputs ->> 'foco' FROM workflow_run WHERE id = ?::uuid")
                .param(runId)
                .query(String.class)
                .single())
        .isEqualTo("tests");

    // Con la anterior en marcha, el disparo se salta.
    post("/api/triggers/" + id + "/fire", Map.of());
    JsonNode skipped = awaitOutcome(id, "SKIPPED");
    assertThat(skipped.path("schedule").path("lastRunId").asString()).isEqualTo(runId);

    // Terminada, el siguiente disparo es otra ejecución del mismo trabajo.
    post("/api/workflow-runs/" + runId + "/cancel", Map.of());
    await(
        () ->
            get("/api/workflow-runs/" + runId).path("status").asString().equals("CANCELLED")
                ? true
                : null,
        "la cancelación");
    post("/api/triggers/" + id + "/fire", Map.of());
    await(
        () -> {
          JsonNode r = get("/api/triggers/" + id);
          return r.path("schedule").path("lastOutcome").asString().equals("LAUNCHED")
                  && !r.path("schedule").path("lastRunId").asString().equals(runId)
              ? r
              : null;
        },
        "el segundo lanzamiento");
    assertThat(
            jdbc.sql("SELECT count(*) FROM workflow_run WHERE work_item_id = ?::uuid")
                .param(workItemId)
                .query(Integer.class)
                .single())
        .isEqualTo(2);
    assertThat(get("/api/projects/" + projectId + "/work-items")).hasSize(1);
    assertThat(eventTypes())
        .contains(
            "trigger.schedule.rejected", "trigger.schedule.launched", "trigger.schedule.skipped");

    // Desactivado, no tiene próxima hora.
    JsonNode disabled = put("/api/triggers/" + id, Map.of("enabled", false));
    assertThat(disabled.path("schedule").path("nextFireAt").isNull()).isTrue();
  }

  @Test
  void aScheduleIsValidated() {
    assertBadRequest(Map.of("kind", "SCHEDULE", "workflowKey", "semanal"), "Falta `cron`");
    assertBadRequest(
        Map.of("kind", "SCHEDULE", "workflowKey", "semanal", "cron", "cada lunes"),
        "no es una expresión cron válida");
    assertBadRequest(
        Map.of(
            "kind",
            "SCHEDULE",
            "workflowKey",
            "semanal",
            "cron",
            "0 0 9 * * *",
            "timeZone",
            "Marte/Olympus"),
        "no es una zona horaria");
    assertBadRequest(
        Map.of(
            "kind",
            "SCHEDULE",
            "workflowKey",
            "semanal",
            "cron",
            "0 0 9 * * *",
            "label",
            "skynet:x"),
        "no lleva etiqueta");
    assertBadRequest(
        Map.of(
            "kind",
            "SCHEDULE",
            "workflowKey",
            "semanal",
            "cron",
            "0 0 9 * * *",
            "inputPolicy",
            Map.of("foco", Map.of("type", "string"))),
        "no recibe datos de fuera");
    JsonNode issue =
        post("/api/repositories/" + repositoryId + "/triggers", Map.of("workflowKey", "semanal"));
    Integer status =
        http.post()
            .uri("/api/triggers/" + issue.path("id").asString() + "/fire")
            .body(Map.of())
            .exchange((request, response) -> response.getStatusCode().value());
    assertThat(status).isEqualTo(400);
  }

  private void assertBadRequest(Map<String, Object> body, String message) {
    String detail =
        http.post()
            .uri("/api/repositories/" + repositoryId + "/triggers")
            .body(body)
            .exchange(
                (request, response) -> {
                  assertThat(response.getStatusCode().value()).isEqualTo(400);
                  return new String(response.getBody().readAllBytes());
                });
    assertThat(detail).contains(message);
  }

  private JsonNode awaitOutcome(String id, String outcome) {
    return await(
        () -> {
          JsonNode rule = get("/api/triggers/" + id);
          return rule.path("schedule").path("lastOutcome").asString().equals(outcome) ? rule : null;
        },
        "el disparo " + outcome);
  }

  private List<String> eventTypes() {
    return jdbc.sql("SELECT event_type FROM event WHERE aggregate_id = ?::uuid ORDER BY sequence")
        .param(repositoryId)
        .query(String.class)
        .list();
  }

  private JsonNode post(String path, Object body) {
    return http.post().uri(path).body(body).retrieve().body(JsonNode.class);
  }

  private JsonNode put(String path, Object body) {
    return http.put().uri(path).body(body).retrieve().body(JsonNode.class);
  }

  private JsonNode get(String path) {
    return http.get().uri(path).retrieve().body(JsonNode.class);
  }

  private static <T> T await(Supplier<T> probe, String what) {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
    while (Instant.now().isBefore(deadline)) {
      T value = probe.get();
      if (value != null) {
        return value;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError(e);
      }
    }
    throw new AssertionError("No llegó " + what);
  }
}
