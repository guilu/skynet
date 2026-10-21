package dev.skynet.controlplane.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skynet.controlplane.support.IntegrationTest;
import dev.skynet.protocol.AgentEventType;
import dev.skynet.protocol.NormalizedEvent;
import dev.skynet.protocol.runner.EventBatch;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;

/**
 * Límites de lo que aceptan la API del runner y las decisiones de aprobación (RV-C). Con el
 * contexto de todos los ITs ({@code skynet.runner.max-request} de 4 MB): otro contexto contra la
 * misma base de datos tendría sus tareas en marcha y el TRUNCATE de cada test se bloquearía.
 */
class RunnerInputLimitsIT extends IntegrationTest {

  @Test
  void aRegistrationOutOfBoundsIsRefusedWithoutCreatingARunner() {
    assertBadRequest(() -> register(registration("x".repeat(10_000), 1)));
    assertBadRequest(() -> register(registration("laptop\nfalso", 1)));
    assertBadRequest(() -> register(registration("", 1)));
    assertBadRequest(() -> register(registration("laptop", 1_000_000)));
    Map<String, Object> longVersion = registration("laptop", 1);
    longVersion.put("runnerVersion", "v".repeat(10_000));
    assertBadRequest(() -> register(longVersion));
    Map<String, Object> manyProviders = registration("laptop", 1);
    manyProviders.put(
        "providers",
        IntStream.range(0, 500).mapToObj(i -> Map.of("id", "p" + i, "version", "1")).toList());
    assertBadRequest(() -> register(manyProviders));

    assertThat(jdbc.sql("SELECT count(*) FROM runner").query(Long.class).single()).isZero();

    // Lo de siempre sigue valiendo: un nombre de host o uno con espacios y tildes.
    register(registration("omarchy.local", 4));
    register(registration("Mac de Diego", 1));
  }

  @Test
  void aHeartbeatOutOfBoundsIsRefused() {
    String token = register(registration("laptop", 2)).path("token").asString();

    assertBadRequest(() -> heartbeat(token, Map.of("capacity", 1_000_000)));
    List<String> running =
        IntStream.range(0, 20_000).mapToObj(i -> UUID.randomUUID().toString()).toList();
    assertBadRequest(() -> heartbeat(token, Map.of("capacity", 2, "runningAgentRunIds", running)));
    List<Map<String, Object>> quotas =
        Collections.nCopies(
            2_000,
            Map.of(
                "provider",
                "codex",
                "bucket",
                "default",
                "window",
                "five_hour",
                "usedFraction",
                0.5,
                "observedAt",
                Instant.now().toString()));
    assertBadRequest(() -> heartbeat(token, Map.of("capacity", 2, "quotas", quotas)));

    assertThat(
            jdbc.sql("SELECT capacity FROM runner WHERE name = 'laptop'")
                .query(Integer.class)
                .single())
        .isEqualTo(2);
    heartbeat(token, Map.of("capacity", 3));
  }

  @Test
  void anEventBatchOutOfBoundsIsRefused() {
    String token = register(registration("laptop", 1)).path("token").asString();
    UUID agent = UUID.randomUUID();
    List<NormalizedEvent> events = new ArrayList<>();
    for (int i = 1; i <= 5_000; i++) {
      events.add(
          new NormalizedEvent(
              UUID.randomUUID(), agent, i, Instant.now(), AgentEventType.RAW, Map.of()));
    }

    assertBadRequest(() -> send(token, new EventBatch(events)));
  }

  @Test
  void aRunnerRequestBodyOverTheLimitIsRefusedBeforeReadingIt() {
    String token = register(registration("laptop", 1)).path("token").asString();
    NormalizedEvent huge =
        new NormalizedEvent(
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            Instant.now(),
            AgentEventType.RAW,
            Map.of("line", "x".repeat(5 * 1024 * 1024)));

    assertStatus(HttpStatus.CONTENT_TOO_LARGE, () -> send(token, new EventBatch(List.of(huge))));
  }

  @Test
  void anApprovalDecisionWithAnOversizedCommentIsRefused() {
    assertBadRequest(
        () ->
            http.post()
                .uri("/api/approvals/" + UUID.randomUUID() + "/decisions")
                .header(HttpHeaders.AUTHORIZATION, ADMIN_BASIC_AUTH)
                .body(Map.of("action", "COMMENT", "comment", "x".repeat(100_000)))
                .retrieve()
                .toBodilessEntity());
  }

  @Test
  void reRegisteringAnExistingNameIsRecordedAsATokenReplacement() {
    register(registration("laptop", 1));
    register(registration("laptop", 1));

    assertThat(
            jdbc.sql(
                    "SELECT payload->>'tokenReplaced' FROM event WHERE event_type = 'runner.registered'"
                        + " ORDER BY sequence")
                .query(String.class)
                .list())
        .containsExactly("false", "true");
  }

  private static Map<String, Object> registration(String name, int capacity) {
    Map<String, Object> body = new HashMap<>();
    body.put("name", name);
    body.put("registrationToken", RUNNER_REGISTRATION_TOKEN);
    body.put("runnerVersion", "dev");
    body.put("capacity", capacity);
    return body;
  }

  private JsonNode register(Map<String, Object> body) {
    return http.post().uri("/api/runner/register").body(body).retrieve().body(JsonNode.class);
  }

  private void heartbeat(String token, Map<String, Object> body) {
    http.post()
        .uri("/api/runner/heartbeat")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .body(body)
        .retrieve()
        .toBodilessEntity();
  }

  private void send(String token, EventBatch batch) {
    http.post()
        .uri("/api/runner/events")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .body(batch)
        .retrieve()
        .toBodilessEntity();
  }

  private static void assertBadRequest(Runnable call) {
    assertStatus(HttpStatus.BAD_REQUEST, call);
  }

  private static void assertStatus(HttpStatus expected, Runnable call) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            HttpClientErrorException.class,
            e -> assertThat(e.getStatusCode().value()).isEqualTo(expected.value()));
  }
}
