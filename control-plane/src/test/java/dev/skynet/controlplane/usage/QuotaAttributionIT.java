package dev.skynet.controlplane.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.skynet.controlplane.support.IntegrationTest;
import dev.skynet.protocol.runner.QuotaSample;
import dev.skynet.protocol.runner.RunnerHeartbeat;
import dev.skynet.protocol.runner.RunnerRegistration;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.JsonNode;

/** Cómo se reparte la cuota entre los agentes (CU-B) y cómo se ve en la API. */
class QuotaAttributionIT extends IntegrationTest {

  private static final Offset<Double> EPS = within(1e-6);
  private static final String CLAUDE = "claude-code";

  @Autowired QuotaAttributor attributor;
  @Autowired QuotaSnapshots snapshots;

  /** Hace cuatro horas, en minutos redondos: los intervalos se leen mejor. */
  private Instant t0;

  private UUID runnerId;
  private String runnerToken;
  private String workItemId;
  private String repositoryId;

  @BeforeEach
  void setUp() {
    t0 = Instant.now().truncatedTo(ChronoUnit.MINUTES).minus(4, ChronoUnit.HOURS);
    JsonNode registered =
        http.post()
            .uri("/api/runner/register")
            .body(
                new RunnerRegistration(
                    "laptop", RUNNER_REGISTRATION_TOKEN, "dev", 4, "2.1.301", null))
            .retrieve()
            .body(JsonNode.class);
    runnerId = UUID.fromString(registered.path("runnerId").asString());
    runnerToken = registered.path("token").asString();
    String projectId =
        post("/api/projects", Map.of("key", "USE", "name", "Uso")).path("id").asString();
    repositoryId =
        post(
                "/api/projects/" + projectId + "/repositories",
                Map.of("name", "demo", "localPath", "/repos/demo"))
            .path("id")
            .asString();
    workItemId =
        post("/api/projects/" + projectId + "/work-items", Map.of("title", "T", "type", "BUG"))
            .path("id")
            .asString();
  }

  @Test
  void theRiseIsSplitByCostAmongTheAgentsThatRanAndTheRestGoesOutside() {
    Agent a = agent(0, 60, "0.30");
    Agent b = agent(30, 60, "0.10");

    samples(sample(0, 0.10), sample(60, 0.30));
    // Los dos cubren todo el intervalo: 0,20 a repartir según su coste (3 a 1).
    assertThat(share(a)).isCloseTo(0.15, EPS);
    assertThat(share(b)).isCloseTo(0.05, EPS);
    assertThat(methods(a)).containsExactly("prorated");

    // Luego no corre ningún agente: la subida es de fuera.
    samples(sample(120, 0.40));
    assertThat(share(a)).isCloseTo(0.15, EPS);
    assertThat(skynet(120)).isZero();
    assertThat(delta(120)).isCloseTo(0.10, EPS);

    // Una muestra que llega tarde parte el primer intervalo: en la primera mitad solo corría A
    // (medido); en la segunda, A ha gastado la mitad de su coste y B todo el suyo (0,15 a 0,10).
    samples(sample(30, 0.20));
    assertThat(share(a)).isCloseTo(0.10 + 0.06, EPS);
    assertThat(share(b)).isCloseTo(0.04, EPS);
    assertThat(methods(a)).containsExactlyInAnyOrder("measured", "prorated");

    JsonNode run = get("/api/workflow-runs/" + a.runId());
    assertThat(run.path("totals").path("quota"))
        .extracting(q -> q.path("window").asString() + " " + round(q.path("share").asDouble()))
        .containsExactly("five_hour 0.16");
    JsonNode detail = get("/api/agent-runs/" + b.agentId()).path("agent");
    assertThat(detail.path("quota").get(0).path("share").asDouble()).isCloseTo(0.04, EPS);
    assertThat(detail.path("quota").get(0).path("method").asString()).isEqualTo("prorated");
  }

  @Test
  void theFirstSampleOfAnAgentIsWhereItStartsFrom() {
    Agent a = agent(50, 60, "0.20");
    samples(sample(0, 0.10));

    // La primera muestra que ve el agente es lo que había antes de que gastara: la subida hasta
    // ella es de fuera, y lo que sube después, suyo.
    invocation(a, 50, 0.30);
    assertThat(share(a)).isZero();
    assertThat(skynet(50)).isZero();
    invocation(a, 60, 0.35);
    assertThat(share(a)).isCloseTo(0.05, EPS);
    assertThat(methods(a)).containsExactly("measured");

    JsonNode window = windows().get(0);
    assertThat(window.path("usedFraction").asDouble()).isEqualTo(0.35);
    assertThat(window.path("skynet").asDouble()).isCloseTo(0.05, EPS);
  }

  @Test
  void codexReportsTheQuotaAtTheEndSoItsOnlySampleIsTheAgents() {
    // El proceso sale un poco después de dar la cuota.
    Agent a = agent(50, 61, "0.20");
    Agent b = agent(62, 70, "0.10");
    Agent failed = agent(40, 40, null);
    jdbc.sql("UPDATE agent_run SET provider = 'codex' WHERE id IN (?, ?, ?)")
        .params(a.agentId(), b.agentId(), failed.agentId())
        .update();
    jdbc.sql("UPDATE agent_run SET status = 'FAILED', failure = 'START' WHERE id = ?")
        .param(failed.agentId())
        .update();
    samples(new QuotaSample("codex", null, null, "five_hour", 0.20, null, null, at(0)));

    snapshots.recordInvocation(
        runnerId,
        a.agentId(),
        Map.of(
            "provider",
            "codex",
            "windows",
            List.of(Map.of("bucket", "default", "window", "five_hour", "usedFraction", 0.23))),
        at(60));

    // El que no llegó a arrancar el CLI no cuenta.
    assertThat(share(a)).isCloseTo(0.03, EPS);
    assertThat(methods(a)).containsExactly("measured");
    assertThat(share(failed)).isZero();

    // Lo que sube después es de B: A ya había dado su cuota final.
    samples(new QuotaSample("codex", null, null, "five_hour", 0.30, null, null, at(70)));
    assertThat(share(a)).isCloseTo(0.03, EPS);
    assertThat(share(b)).isCloseTo(0.07, EPS);
  }

  @Test
  void aRenewedWindowCountsFromZeroAndFromTheRenewal() {
    Agent a = agent(124, 131, "0.05");

    samples(sample(0, 0.80, at(125)), sample(130, 0.02, at(425)));

    // La subida es 0,02 (desde 0), y desde la renovación solo corría A: medido.
    assertThat(delta(130)).isCloseTo(0.02, EPS);
    assertThat(share(a)).isCloseTo(0.02, EPS);
    assertThat(methods(a)).containsExactly("measured");
    assertThat(jdbc.sql("SELECT renewed FROM quota_interval").query(Boolean.class).single())
        .isTrue();
  }

  @Test
  void aSmallDropFromRoundingIsNotARenewal() {
    Agent a = agent(0, 60, "0.05");

    // Claude redondea a dos decimales: 0,30 → 0,297 no es una ventana nueva ni gasto.
    samples(sample(0, 0.30), sample(30, 0.297), sample(60, 0.31));

    assertThat(jdbc.sql("SELECT count(*) FROM quota_interval").query(Long.class).single())
        .isEqualTo(1);
    assertThat(share(a)).isCloseTo(0.31 - 0.297, EPS);
  }

  @Test
  void anAgentThatFinishesLaterGetsItsShareRecomputedWithItsCost() {
    Agent a = agent(0, 60, "0.30");
    Agent b = agent(0, null, null);

    samples(sample(0, 0.10), sample(60, 0.30));
    // Sin el coste de B, se reparte por el tiempo que corrieron en el intervalo: a medias.
    assertThat(share(a)).isCloseTo(0.10, EPS);

    jdbc.sql("UPDATE agent_run SET finished_at = ?, cost_usd = 0.10 WHERE id = ?")
        .params(Timestamp.from(at(60)), b.agentId())
        .update();
    attributor.agentChanged(b.agentId());

    assertThat(share(a)).isCloseTo(0.15, EPS);
    assertThat(share(b)).isCloseTo(0.05, EPS);
  }

  @Test
  void theUsagePageReportsHistoryAgentsRunsAndWarnings() {
    Agent a = agent(0, 60, "0.30");
    agent(0, 60, "0.10");
    samples(
        sample(0, 0.10, at(240)),
        sample(60, 0.30, at(240)),
        new QuotaSample(CLAUDE, null, null, "seven_day", 0.50, null, null, at(0)),
        new QuotaSample(CLAUDE, null, null, "seven_day", 0.95, null, null, at(60)));

    JsonNode history =
        get("/api/usage/history?provider=" + CLAUDE + "&window=five_hour&runner=" + runnerId);
    assertThat(history.path("windowStart").asString()).isEqualTo(at(-60).toString());
    assertThat(history.path("points"))
        .extracting(
            p ->
                round(p.path("usedFraction").asDouble()) + "/" + round(p.path("skynet").asDouble()))
        .containsExactly("0.1/0.0", "0.3/0.2");

    JsonNode agents = get("/api/usage/agents?period=24h");
    assertThat(agents).hasSize(1);
    assertThat(agents.get(0).path("agent").asString()).isEqualTo("agent");
    assertThat(agents.get(0).path("workflowName").asString()).isEqualTo("Agente suelto");
    assertThat(agents.get(0).path("runs").asLong()).isEqualTo(2);
    assertThat(agents.get(0).path("succeeded").asLong()).isEqualTo(2);
    assertThat(agents.get(0).path("fiveHour").asDouble()).isCloseTo(0.20, EPS);
    assertThat(agents.get(0).path("sevenDay").asDouble()).isCloseTo(0.45, EPS);
    assertThat(agents.get(0).path("sevenDayPerSuccess").asDouble()).isCloseTo(0.225, EPS);

    JsonNode runs = get("/api/usage/runs?period=24h&limit=1");
    assertThat(runs).hasSize(1);
    assertThat(runs.get(0).path("id").asString()).isEqualTo(a.runId().toString());
    assertThat(runs.get(0).path("quota"))
        .extracting(q -> q.path("window").asString())
        .containsExactly("five_hour", "seven_day");

    // La semana pasa del 90 %: avisa el Dashboard.
    JsonNode warnings = get("/api/dashboard").path("quotaWarnings");
    assertThat(warnings)
        .extracting(w -> w.path("window").asString() + " " + w.path("reason").asString())
        .containsExactly("seven_day limit");
  }

  /** Un agente de Claude Code de este runner que corrió de {@code from} a {@code to} minutos. */
  private Agent agent(int from, Integer to, String cost) {
    JsonNode run =
        post(
            "/api/work-items/" + workItemId + "/runs",
            new LinkedHashMap<>(Map.of("repositoryId", repositoryId, "prompt", "x")));
    UUID agentId =
        UUID.fromString(run.path("stages").get(0).path("agents").get(0).path("id").asString());
    jdbc.sql(
            "UPDATE agent_run SET runner_id = ?, status = ?, started_at = ?, finished_at = ?,"
                + " cost_usd = ?::numeric WHERE id = ?")
        .params(
            runnerId,
            to == null ? "EXECUTING" : "COMPLETED",
            Timestamp.from(at(from)),
            to == null ? null : Timestamp.from(at(to)),
            cost,
            agentId)
        .update();
    return new Agent(UUID.fromString(run.path("id").asString()), agentId);
  }

  private record Agent(UUID runId, UUID agentId) {}

  private Instant at(int minutes) {
    return t0.plus(minutes, ChronoUnit.MINUTES);
  }

  private QuotaSample sample(int minute, double used) {
    return sample(minute, used, null);
  }

  private QuotaSample sample(int minute, double used, Instant resetsAt) {
    return new QuotaSample(CLAUDE, null, null, "five_hour", used, resetsAt, null, at(minute));
  }

  private void samples(QuotaSample... samples) {
    http.post()
        .uri("/api/runner/heartbeat")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + runnerToken)
        .body(new RunnerHeartbeat(1, List.of(), null, List.of(samples)))
        .retrieve()
        .toBodilessEntity();
  }

  /** Una muestra que ve el agente en su salida ({@code agent.quota}). */
  private void invocation(Agent agent, int minute, double used) {
    snapshots.recordInvocation(
        runnerId,
        agent.agentId(),
        Map.of(
            "provider",
            CLAUDE,
            "windows",
            List.of(Map.of("bucket", "default", "window", "five_hour", "usedFraction", used))),
        at(minute));
  }

  private double share(Agent agent) {
    return jdbc.sql(
            "SELECT coalesce(sum(share), 0) FROM agent_run_quota WHERE agent_run_id = ?"
                + " AND window_kind = 'five_hour'")
        .param(agent.agentId())
        .query(Double.class)
        .single();
  }

  private List<String> methods(Agent agent) {
    return jdbc.sql(
            "SELECT method FROM agent_run_quota WHERE agent_run_id = ? AND window_kind ="
                + " 'five_hour' ORDER BY ended_at")
        .param(agent.agentId())
        .query(String.class)
        .list();
  }

  /** La subida del intervalo que acaba en el minuto {@code minute}. */
  private double delta(int minute) {
    return interval(minute, "delta");
  }

  private double skynet(int minute) {
    return interval(minute, "skynet");
  }

  private double interval(int minute, String column) {
    return jdbc.sql(
            "SELECT "
                + column
                + " FROM quota_interval WHERE window_kind = 'five_hour'"
                + " AND ended_at = ?")
        .param(Timestamp.from(at(minute)))
        .query(Double.class)
        .single();
  }

  private JsonNode windows() {
    return get("/api/usage/windows?runner=" + runnerId).path("runners").get(0).path("windows");
  }

  private static double round(double value) {
    return Math.round(value * 1000) / 1000.0;
  }

  private JsonNode post(String path, Object body) {
    return http.post().uri(path).body(body).retrieve().body(JsonNode.class);
  }

  private JsonNode get(String path) {
    return http.get().uri(path).retrieve().body(JsonNode.class);
  }
}
