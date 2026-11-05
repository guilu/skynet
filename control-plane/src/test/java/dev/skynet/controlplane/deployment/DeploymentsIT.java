package dev.skynet.controlplane.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skynet.controlplane.deployment.Deployments.NewDeployment;
import dev.skynet.controlplane.deployment.Deployments.Status;
import dev.skynet.controlplane.deployment.Deployments.StatusUpdate;
import dev.skynet.controlplane.shared.InvalidRequestException;
import dev.skynet.controlplane.support.IntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;

/** El modelo de despliegues (DL-A): idempotencia, desorden, superados y la API de lectura. */
class DeploymentsIT extends IntegrationTest {

  static final String SHA_1 = "1111111111111111111111111111111111111111";
  static final String SHA_2 = "2222222222222222222222222222222222222222";
  static final Instant T0 = Instant.parse("2026-11-05T10:00:00Z");

  @Autowired Deployments deployments;

  private UUID repositoryId;

  @BeforeEach
  void setUp() {
    String projectId =
        post("/api/projects", Map.of("key", "DEP", "name", "Despliegues")).path("id").asString();
    repositoryId =
        UUID.fromString(
            post(
                    "/api/projects/" + projectId + "/repositories",
                    Map.of("name", "demo-repo", "localPath", "/repos/demo"))
                .path("id")
                .asString());
  }

  private Deployments.Deployment deploy(String externalId, String sha, Instant at) {
    return deployments.register(
        new NewDeployment(
            repositoryId,
            "omarchy",
            externalId,
            sha,
            "main",
            7,
            "https://demo.local",
            "hermes",
            at));
  }

  private Deployments.Deployment status(UUID id, String externalId, Status state, Instant at) {
    return deployments.record(id, new StatusUpdate(externalId, state, null, at));
  }

  @Test
  void aDeploymentGoesThroughItsStatesOnceEach() {
    Deployments.Deployment d = deploy("101", SHA_1, T0);
    assertThat(d.status()).isEqualTo(Status.PENDING);
    assertThat(d.health()).isEqualTo(Deployments.Health.UNKNOWN);
    // El mismo despliegue otra vez: el mismo, sin evento nuevo.
    assertThat(deploy("101", SHA_1, T0).id()).isEqualTo(d.id());

    status(d.id(), "s1", Status.IN_PROGRESS, T0.plusSeconds(1));
    Deployments.Deployment done = status(d.id(), "s2", Status.SUCCEEDED, T0.plusSeconds(30));
    assertThat(done.status()).isEqualTo(Status.SUCCEEDED);
    assertThat(done.startedAt()).isEqualTo(T0.plusSeconds(1));
    assertThat(done.finishedAt()).isEqualTo(T0.plusSeconds(30));
    // Terminar bien no dice nada de la salud.
    assertThat(done.health()).isEqualTo(Deployments.Health.UNKNOWN);

    // El mismo estado repetido no cambia nada.
    status(d.id(), "s2", Status.SUCCEEDED, T0.plusSeconds(30));
    assertThat(deployments.statuses(d.id())).hasSize(2);
    assertThat(eventTypes())
        .containsExactly("deployment.created", "deployment.status", "deployment.status");
  }

  @Test
  void aLateOlderStatusIsKeptButDoesNotOverwrite() {
    Deployments.Deployment d = deploy("102", SHA_1, T0);
    status(d.id(), "s2", Status.FAILED, T0.plusSeconds(20));
    Deployments.Deployment after = status(d.id(), "s1", Status.IN_PROGRESS, T0.plusSeconds(5));
    assertThat(after.status()).isEqualTo(Status.FAILED);
    assertThat(deployments.statuses(d.id()))
        .extracting(Deployments.StatusRecord::state, Deployments.StatusRecord::applied)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(Status.IN_PROGRESS, false),
            org.assertj.core.groups.Tuple.tuple(Status.FAILED, true));
  }

  @Test
  void anErrorIsRedactedAndAFinalStatusWinsATie() {
    Deployments.Deployment d = deploy("103", SHA_1, T0);
    status(d.id(), "s1", Status.IN_PROGRESS, T0.plusSeconds(5));
    Deployments.Deployment failed =
        deployments.record(
            d.id(),
            new StatusUpdate(
                "s2",
                Status.FAILED,
                "compose falló: token=ghp_abcdefghijklmnopqrstuvwxyz0123456789",
                T0.plusSeconds(5)));
    assertThat(failed.status()).isEqualTo(Status.FAILED);
    assertThat(failed.error()).startsWith("compose falló").doesNotContain("ghp_abcdef");
  }

  @Test
  void aNewerSuccessSupersedesTheOlderOnesEvenOutOfOrder() {
    Deployments.Deployment old = deploy("201", SHA_1, T0);
    status(old.id(), "a1", Status.SUCCEEDED, T0.plusSeconds(10));
    Deployments.Deployment failedNew = deploy("202", SHA_2, T0.plusSeconds(60));
    status(failedNew.id(), "b1", Status.FAILED, T0.plusSeconds(70));
    // Un despliegue nuevo que falla no cambia lo desplegado.
    assertThat(deployments.get(old.id()).status()).isEqualTo(Status.SUCCEEDED);

    Deployments.Deployment newer = deploy("203", SHA_2, T0.plusSeconds(120));
    Deployments.Deployment late = deploy("204", SHA_1, T0.plusSeconds(90));
    status(newer.id(), "c1", Status.SUCCEEDED, T0.plusSeconds(130));
    Deployments.Deployment oldAfter = deployments.get(old.id());
    assertThat(oldAfter.status()).isEqualTo(Status.SUPERSEDED);
    assertThat(oldAfter.supersededBy()).isEqualTo(newer.id());
    assertThat(deployments.get(late.id()).status()).isEqualTo(Status.SUPERSEDED);
    // Los fallidos se quedan como fallidos.
    assertThat(deployments.get(failedNew.id()).status()).isEqualTo(Status.FAILED);

    // Un despliegue anterior que termina tarde no pasa por delante del más nuevo.
    Deployments.Deployment tardy = deploy("205", SHA_1, T0.plusSeconds(100));
    Deployments.Deployment after = status(tardy.id(), "d1", Status.SUCCEEDED, T0.plusSeconds(200));
    assertThat(after.status()).isEqualTo(Status.SUPERSEDED);
    assertThat(after.supersededBy()).isEqualTo(newer.id());
    // Y un estado de un despliegue superado ya no lo cambia.
    assertThat(status(old.id(), "a2", Status.FAILED, T0.plusSeconds(300)).status())
        .isEqualTo(Status.SUPERSEDED);

    JsonNode environments = get("/api/repositories/" + repositoryId + "/environments");
    assertThat(environments).hasSize(1);
    assertThat(environments.get(0).path("environment").asString()).isEqualTo("omarchy");
    assertThat(environments.get(0).path("deployed").path("id").asString())
        .isEqualTo(newer.id().toString());
    assertThat(environments.get(0).path("latest").path("id").asString())
        .isEqualTo(newer.id().toString());
    assertThat(eventTypes()).contains("deployment.superseded");
  }

  @Test
  void environmentsAreIndependent() {
    Deployments.Deployment a = deploy("301", SHA_1, T0);
    status(a.id(), "a1", Status.SUCCEEDED, T0.plusSeconds(10));
    Deployments.Deployment b =
        deployments.register(
            new NewDeployment(
                repositoryId, "staging", "302", SHA_2, null, null, null, null, T0.plusSeconds(60)));
    status(b.id(), "b1", Status.SUCCEEDED, T0.plusSeconds(70));
    assertThat(deployments.get(a.id()).status()).isEqualTo(Status.SUCCEEDED);
    assertThat(get("/api/repositories/" + repositoryId + "/environments")).hasSize(2);
  }

  @Test
  void concurrentSuccessesInOneEnvironmentLeaveExactlyOneDeployed() throws Exception {
    List<Deployments.Deployment> created =
        List.of(
            deploy("401", SHA_1, T0),
            deploy("402", SHA_2, T0.plusSeconds(1)),
            deploy("403", SHA_1, T0.plusSeconds(2)),
            deploy("404", SHA_2, T0.plusSeconds(3)));
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Callable<Deployments.Deployment>> tasks =
          created.stream()
              .<Callable<Deployments.Deployment>>map(
                  d ->
                      () ->
                          status(
                              d.id(), "ok-" + d.externalId(), Status.SUCCEEDED, T0.plusSeconds(60)))
              .toList();
      for (Future<Deployments.Deployment> f : pool.invokeAll(tasks)) {
        f.get();
      }
    } finally {
      pool.shutdown();
    }
    assertThat(
            jdbc.sql(
                    "SELECT external_id FROM deployment WHERE repository_id = ? AND status = 'SUCCEEDED'")
                .param(repositoryId)
                .query(String.class)
                .list())
        .containsExactly("404");
  }

  @Test
  void theReadApiFiltersBySha() {
    Deployments.Deployment a = deploy("501", SHA_1, T0);
    deploy("502", SHA_2, T0.plusSeconds(10));
    deployments.record(
        a.id(), new StatusUpdate("x", Status.FAILED, "imagen no encontrada", T0.plusSeconds(5)));

    JsonNode all = get("/api/repositories/" + repositoryId + "/deployments");
    assertThat(all).hasSize(2);
    assertThat(all.get(0).path("externalId").asString()).isEqualTo("502");

    JsonNode bySha = get("/api/repositories/" + repositoryId + "/deployments?sha=1111111");
    assertThat(bySha).hasSize(1);
    assertThat(bySha.get(0).path("commitSha").asString()).isEqualTo(SHA_1);
    assertThat(bySha.get(0).path("pullRequestNumber").asInt()).isEqualTo(7);

    JsonNode detail = get("/api/deployments/" + a.id());
    assertThat(detail.path("deployment").path("status").asString()).isEqualTo("FAILED");
    assertThat(detail.path("deployment").path("error").asString())
        .isEqualTo("imagen no encontrada");
    assertThat(detail.path("statuses")).hasSize(1);

    assertThatThrownBy(() -> get("/api/repositories/" + repositoryId + "/deployments?sha=xyz"))
        .isInstanceOf(HttpClientErrorException.BadRequest.class);
    assertThatThrownBy(() -> get("/api/deployments/" + UUID.randomUUID()))
        .isInstanceOf(HttpClientErrorException.NotFound.class);
    assertThatThrownBy(() -> get("/api/repositories/" + UUID.randomUUID() + "/environments"))
        .isInstanceOf(HttpClientErrorException.NotFound.class);
  }

  @Test
  void invalidInputIsRejected() {
    assertThatThrownBy(() -> deploy("601", "abc", T0)).isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(
            () ->
                deployments.register(
                    new NewDeployment(
                        repositoryId, "omarchy prod", "602", SHA_1, null, null, null, null, T0)))
        .isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(
            () ->
                deployments.register(
                    new NewDeployment(
                        repositoryId,
                        "omarchy",
                        "603",
                        SHA_1,
                        null,
                        null,
                        "https://user:pass@demo.local",
                        null,
                        T0)))
        .isInstanceOf(InvalidRequestException.class);
    Deployments.Deployment d = deploy("604", SHA_1, T0);
    assertThatThrownBy(() -> status(d.id(), "s", Status.SUPERSEDED, T0))
        .isInstanceOf(InvalidRequestException.class);
  }

  private JsonNode get(String uri) {
    return http.get().uri(uri).retrieve().body(JsonNode.class);
  }

  private JsonNode post(String uri, Object body) {
    return http.post().uri(uri).body(body).retrieve().body(JsonNode.class);
  }

  private List<String> eventTypes() {
    return jdbc.sql(
            "SELECT event_type FROM event WHERE sequence > ? AND aggregate_type = 'deployment'"
                + " ORDER BY sequence")
        .param(baseline)
        .query(String.class)
        .list();
  }
}
