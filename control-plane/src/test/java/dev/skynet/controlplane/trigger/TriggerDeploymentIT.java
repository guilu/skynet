package dev.skynet.controlplane.trigger;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skynet.controlplane.deployment.Deployments;
import dev.skynet.controlplane.deployment.Deployments.NewDeployment;
import dev.skynet.controlplane.deployment.Deployments.Status;
import dev.skynet.controlplane.deployment.Deployments.StatusUpdate;
import dev.skynet.controlplane.support.IntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Los disparadores por despliegue (DL-D): nacen apagados, lanzan una sola vez por despliegue, no
 * actúan sobre un SHA sustituido y respetan el interruptor y el freno diario.
 */
class TriggerDeploymentIT extends IntegrationTest {

  static final String WORKFLOW =
      """
      id: humo
      name: Pruebas de humo
      version: 1
      stages:
        - id: probar
          type: agent
          prompt: "Prueba lo desplegado"
      """;

  @Autowired TriggerDeployments triggers;
  @Autowired Deployments deployments;

  private String projectId;
  private String repositoryId;
  private int external;

  @BeforeEach
  void setUp() {
    projectId = post("/api/projects", Map.of("key", "DLD", "name", "Humo")).path("id").asString();
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
  void aHealthyDeploymentLaunchesOnceAndNeverForASupersededSha() {
    JsonNode rule =
        post(
            "/api/repositories/" + repositoryId + "/triggers",
            Map.of(
                "kind",
                "DEPLOYMENT",
                "workflowKey",
                "humo",
                "environment",
                "omarchy",
                "health",
                "HEALTHY"));
    String id = rule.path("id").asString();
    assertThat(rule.path("enabled").asBoolean()).isFalse();
    assertThat(rule.path("label").isNull()).isTrue();
    assertThat(rule.path("schedule").isNull()).isTrue();
    assertThat(rule.path("deployment").path("environment").asString()).isEqualTo("omarchy");
    assertThat(rule.path("deployment").path("workItemTitle").asString())
        .isEqualTo("humo (al desplegar en omarchy)");

    // Apagada, no hace nada aunque haya algo sano.
    UUID before = healthy(deployed("1"));
    assertThat(triggers.fireAll()).isZero();

    // Encendida, lo de antes de encenderla no cuenta.
    put("/api/triggers/" + id, Map.of("enabled", true));
    assertThat(triggers.fireAll()).isZero();

    // Con el interruptor general apagado, se rechaza y queda registrado una sola vez.
    UUID first = healthy(deployed("2"));
    assertThat(triggers.fireAll()).isEqualTo(1);
    JsonNode rejected = get("/api/triggers/" + id);
    assertThat(rejected.path("deployment").path("lastOutcome").asString()).isEqualTo("REJECTED");
    assertThat(rejected.path("deployment").path("lastReason").asString()).contains("apagados");
    assertThat(triggers.fireAll()).isZero();

    put("/api/triggers/settings", Map.of("enabled", true));
    UUID second = healthy(deployed("3"));
    assertThat(triggers.fireAll()).isEqualTo(1);
    JsonNode launched = get("/api/triggers/" + id);
    assertThat(launched.path("deployment").path("lastOutcome").asString()).isEqualTo("LAUNCHED");
    String runId = launched.path("deployment").path("lastRunId").asString();
    String workItemId = launched.path("deployment").path("workItemId").asString();
    assertThat(get("/api/work-items/" + workItemId).path("externalRef").asString())
        .isEqualTo("skynet:deployment:" + id);
    assertThat(fires(id)).containsEntry(second, "LAUNCHED").doesNotContainKey(before);
    assertThat(fires(id)).containsEntry(first, "REJECTED");

    // Repetir la pasada (o un evento repetido) no lanza otra vez.
    assertThat(triggers.fireAll()).isZero();
    assertThat(runsOf(workItemId)).isEqualTo(1);

    // Con la anterior en marcha, se salta.
    UUID busy = healthy(deployed("4"));
    assertThat(triggers.fireAll()).isEqualTo(1);
    assertThat(fires(id)).containsEntry(busy, "SKIPPED");
    cancel(runId);

    // Dos despliegues sanos seguidos: el sustituido no dispara, solo lo desplegado ahora.
    UUID old = deployed("5");
    UUID current = deployed("6");
    healthy(old);
    healthy(current);
    assertThat(deployments.get(old).status()).isEqualTo(Status.SUPERSEDED);
    assertThat(triggers.fireAll()).isEqualTo(1);
    assertThat(fires(id)).containsEntry(current, "LAUNCHED").doesNotContainKey(old);
    assertThat(runsOf(workItemId)).isEqualTo(2);
    assertThat(get("/api/projects/" + projectId + "/work-items")).hasSize(1);

    assertThat(eventTypes())
        .contains(
            "trigger.deployment.rejected",
            "trigger.deployment.launched",
            "trigger.deployment.skipped");
  }

  @Test
  void anUnhealthyRuleFiresOnFailedHealthAndRespectsTheDailyLimit() {
    put("/api/triggers/settings", Map.of("enabled", true, "dailyLimit", 1));
    JsonNode rule =
        post(
            "/api/repositories/" + repositoryId + "/triggers",
            Map.of(
                "kind",
                "DEPLOYMENT",
                "workflowKey",
                "humo",
                "environment",
                "omarchy",
                "health",
                "UNHEALTHY",
                "enabled",
                true));
    String id = rule.path("id").asString();

    // Sano no la dispara; sin respuesta sí.
    healthy(deployed("a"));
    assertThat(triggers.fireAll()).isZero();
    UUID timeout = checked(deployed("b"), "TIMEOUT");
    assertThat(triggers.fireAll()).isEqualTo(1);
    assertThat(fires(id)).containsEntry(timeout, "LAUNCHED");
    cancel(get("/api/triggers/" + id).path("deployment").path("lastRunId").asString());

    // El freno diario (1) ya está gastado: se rechaza.
    UUID limited = checked(deployed("c"), "UNHEALTHY");
    assertThat(triggers.fireAll()).isEqualTo(1);
    assertThat(fires(id)).containsEntry(limited, "REJECTED");
    assertThat(get("/api/triggers/" + id).path("deployment").path("lastReason").asString())
        .contains("Límite diario");
    assertThat(get("/api/triggers/settings").path("launchedToday").asInt()).isEqualTo(1);
  }

  @Test
  void aDeploymentRuleIsValidated() {
    Map<String, Object> base = new HashMap<>();
    base.put("kind", "DEPLOYMENT");
    base.put("workflowKey", "humo");
    base.put("health", "HEALTHY");
    assertBadRequest(base, "Falta `environment`");

    base.put("environment", "omarchy (compartido)");
    assertBadRequest(base, "El entorno solo admite");

    base.put("environment", "omarchy");
    base.put("label", "skynet:humo");
    assertBadRequest(base, "no lleva etiqueta");

    base.remove("label");
    base.remove("health");
    assertBadRequest(base, "Falta `health`");

    base.put("health", "HEALTHY");
    base.put("inputPolicy", Map.of("foco", Map.of("type", "string")));
    assertBadRequest(base, "no recibe datos de fuera");
  }

  /** Un despliegue en omarchy que termina bien ahora mismo. */
  private UUID deployed(String suffix) {
    Instant at = Instant.now();
    UUID id =
        deployments
            .register(
                new NewDeployment(
                    UUID.fromString(repositoryId),
                    "omarchy",
                    "dld-" + suffix + "-" + (++external),
                    String.format("%040d", external),
                    "main",
                    null,
                    null,
                    "hermes",
                    at))
            .id();
    deployments.record(id, new StatusUpdate("s-" + id, Status.SUCCEEDED, null, at));
    return id;
  }

  private void cancel(String runId) {
    post("/api/workflow-runs/" + runId + "/cancel", Map.of());
    Instant deadline = Instant.now().plusSeconds(15);
    while (!get("/api/workflow-runs/" + runId).path("status").asString().equals("CANCELLED")) {
      assertThat(Instant.now()).as("la cancelación de " + runId).isBefore(deadline);
      Thread.onSpinWait();
    }
  }

  private UUID healthy(UUID deployment) {
    return checked(deployment, "HEALTHY");
  }

  /** Lo que haría DL-C al terminar la comprobación. */
  private UUID checked(UUID deployment, String health) {
    jdbc.sql("UPDATE deployment SET health = ?, health_checked_at = ? WHERE id = ?")
        .params(health, Timestamp.from(Instant.now()), deployment)
        .update();
    return deployment;
  }

  private Map<UUID, String> fires(String triggerId) {
    Map<UUID, String> out = new HashMap<>();
    jdbc.sql(
            "SELECT deployment_id, outcome FROM trigger_deployment_fire WHERE trigger_id = ?::uuid")
        .param(triggerId)
        .query(
            (rs, row) ->
                out.put(rs.getObject("deployment_id", UUID.class), rs.getString("outcome")))
        .list();
    return out;
  }

  private int runsOf(String workItemId) {
    return jdbc.sql("SELECT count(*) FROM workflow_run WHERE work_item_id = ?::uuid")
        .param(workItemId)
        .query(Integer.class)
        .single();
  }

  private List<String> eventTypes() {
    return jdbc.sql("SELECT event_type FROM event WHERE aggregate_id = ?::uuid ORDER BY sequence")
        .param(repositoryId)
        .query(String.class)
        .list();
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

  private JsonNode post(String path, Object body) {
    return http.post().uri(path).body(body).retrieve().body(JsonNode.class);
  }

  private JsonNode put(String path, Object body) {
    return http.put().uri(path).body(body).retrieve().body(JsonNode.class);
  }

  private JsonNode get(String path) {
    return http.get().uri(path).retrieve().body(JsonNode.class);
  }
}
