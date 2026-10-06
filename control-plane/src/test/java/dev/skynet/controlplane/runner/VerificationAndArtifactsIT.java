package dev.skynet.controlplane.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skynet.controlplane.support.IntegrationTest;
import dev.skynet.protocol.AgentEventType;
import dev.skynet.protocol.NormalizedEvent;
import dev.skynet.protocol.runner.ArtifactType;
import dev.skynet.protocol.runner.ArtifactUpload;
import dev.skynet.protocol.runner.EventBatch;
import dev.skynet.protocol.runner.RunnerRegistration;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;

/** Verificación de los worktrees y artefactos que suben los runners (M5-A). */
class VerificationAndArtifactsIT extends IntegrationTest {

  @Test
  void aCompletedInvocationIsVerifiedOnItsRunnerWithoutChangingTheAgent() {
    Runner runner = register("owner");
    Launched run = launch("./gradlew test");
    JsonNode start = poll(runner).getFirst();
    ack(runner, start);
    completed(runner, run.agentId());

    // La orden VERIFY va al runner del worktree, con el comando y los informes del repositorio.
    List<JsonNode> commands = poll(runner);
    assertThat(commands).hasSize(1);
    JsonNode verify = commands.getFirst();
    assertThat(verify.path("type").asString()).isEqualTo("VERIFY");
    assertThat(verify.path("agentRunId").asString()).isEqualTo(run.agentId().toString());
    JsonNode payload = verify.path("verify");
    assertThat(payload.path("workspacePath").asString()).isEqualTo("/w/run/a1");
    assertThat(payload.path("command").asString()).isEqualTo("./gradlew test");
    assertThat(payload.path("testReportPaths"))
        .extracting(JsonNode::asString)
        .containsExactly("**/build/test-results/**/*.xml", "**/target/surefire-reports/*.xml");
    assertThat(payload.path("timeout").asString()).isEqualTo("PT30M");
    ack(runner, verify);
    UUID verificationId = UUID.fromString(payload.path("verificationRunId").asString());

    JsonNode queued = verifications(run).get(0);
    assertThat(queued.path("status").asString()).isEqualTo("QUEUED");
    assertThat(queued.path("trigger").asString()).isEqualTo("AUTO");
    // Mientras se verifica, el worktree está ocupado.
    assertStatus(
        HttpStatus.CONFLICT,
        () -> post("/api/agent-runs/" + run.agentId() + "/messages", Map.of("text", "Sigue")));

    Events events = new Events(run.agentId(), 6);
    events.add(AgentEventType.VERIFICATION_STARTED, Map.of("verificationRunId", verificationId));
    send(runner, events.batch());
    assertThat(verifications(run).get(0).path("status").asString()).isEqualTo("RUNNING");

    events.add(
        AgentEventType.VERIFICATION_COMPLETED,
        Map.of(
            "verificationRunId",
            verificationId,
            "exitCode",
            1,
            "tests",
            Map.of("total", 12, "failed", 2, "errors", 0, "skipped", 1)));
    send(runner, events.batch());

    JsonNode result = verifications(run).get(0);
    assertThat(result.path("status").asString()).isEqualTo("FAILED");
    assertThat(result.path("exitCode").asInt()).isEqualTo(1);
    assertThat(result.path("tests").path("total").asInt()).isEqualTo(12);
    assertThat(result.path("tests").path("failed").asInt()).isEqualTo(2);
    assertThat(result.path("tests").path("skipped").asInt()).isEqualTo(1);
    assertThat(result.path("startedAt").isNull()).isFalse();
    assertThat(result.path("finishedAt").isNull()).isFalse();
    // El agente y su ejecución no cambian.
    assertThat(agent(run).path("status").asString()).isEqualTo("COMPLETED");
    assertThat(get("/api/workflow-runs/" + run.runId()).path("status").asString())
        .isEqualTo("SUCCEEDED");
    assertThat(eventTypes(run))
        .contains(
            "verification.queued", "agent.verification.started", "agent.verification.completed");

    // Reejecutar crea otra verificación, ya sin nada vivo en el worktree.
    JsonNode manual = post("/api/agent-runs/" + run.agentId() + "/verifications", Map.of());
    assertThat(manual.path("trigger").asString()).isEqualTo("MANUAL");
    assertThat(manual.path("status").asString()).isEqualTo("QUEUED");
    assertStatus(
        HttpStatus.CONFLICT,
        () -> post("/api/agent-runs/" + run.agentId() + "/verifications", Map.of()));
    assertThat(verifications(run))
        .extracting(v -> v.path("trigger").asString())
        .containsExactly("MANUAL", "AUTO");
    assertThat(poll(runner)).extracting(c -> c.path("type").asString()).containsExactly("VERIFY");

    events.add(
        AgentEventType.VERIFICATION_COMPLETED,
        Map.of(
            "verificationRunId",
            manual.path("id").asString(),
            "error",
            "Se agotó el tiempo máximo (PT30M)"));
    send(runner, events.batch());
    assertThat(verifications(run).get(0).path("status").asString()).isEqualTo("ERROR");
    assertThat(verifications(run).get(0).path("error").asString()).contains("tiempo máximo");
  }

  @Test
  void withoutAValidationCommandNothingIsVerified() {
    Runner runner = register("owner");
    Launched run = launch(null);
    ack(runner, poll(runner).getFirst());
    completed(runner, run.agentId());

    assertThat(poll(runner)).isEmpty();
    assertThat(verifications(run)).isEmpty();
    assertStatus(
        HttpStatus.CONFLICT,
        () -> post("/api/agent-runs/" + run.agentId() + "/verifications", Map.of()));

    // Configurado el comando, se puede verificar a mano.
    JsonNode repository =
        put(
            "/api/projects/" + projectId + "/repositories/" + repositoryId + "/verification",
            Map.of(
                "validationCommand", "  npm test  ", "testReportPaths", List.of("reports/*.xml")));
    assertThat(repository.path("validationCommand").asString()).isEqualTo("npm test");
    assertThat(repository.path("testReportPaths"))
        .extracting(JsonNode::asString)
        .containsExactly("reports/*.xml");
    post("/api/agent-runs/" + run.agentId() + "/verifications", Map.of());
    JsonNode verify = poll(runner).getFirst();
    assertThat(verify.path("verify").path("command").asString()).isEqualTo("npm test");
    assertThat(verify.path("verify").path("testReportPaths"))
        .extracting(JsonNode::asString)
        .containsExactly("reports/*.xml");
  }

  @Test
  void verificationEventsOfOtherAgentsAreRejected() {
    Runner runner = register("owner");
    Launched run = launch("make test");
    ack(runner, poll(runner).getFirst());
    completed(runner, run.agentId());
    ack(runner, poll(runner).getFirst());

    Events events = new Events(run.agentId(), 6);
    events.add(
        AgentEventType.VERIFICATION_COMPLETED,
        Map.of("verificationRunId", UUID.randomUUID(), "exitCode", 0));
    JsonNode result = send(runner, events.batch());

    assertThat(result.path("rejected").get(0).path("reason").asString())
        .isEqualTo("unknown-verification-run");
    assertThat(verifications(run).get(0).path("status").asString()).isEqualTo("QUEUED");
  }

  @Test
  void artifactsAreStoredOnceRedactedAndServedInChunks() {
    Runner runner = register("owner");
    Launched run = launch(null);
    ack(runner, poll(runner).getFirst());

    String diff =
        "diff --git a/calc.py b/calc.py\n-    return a - b\n+    return a + b\n"
            + "+TOKEN=ghp_abcdefghijklmnopqrstuvwxyz0123456789\n";
    JsonNode stored =
        upload(
            runner,
            new ArtifactUpload(
                run.agentId(),
                null,
                ArtifactType.DIFF,
                "changes.diff",
                "text/x-diff",
                sha256(diff.getBytes(StandardCharsets.UTF_8)),
                Map.of("files", 1, "insertions", 2, "deletions", 1)),
            diff.getBytes(StandardCharsets.UTF_8));
    assertThat(stored.path("duplicate").asBoolean()).isFalse();
    String id = stored.path("id").asString();

    // Reenviarlo no lo duplica.
    JsonNode again =
        upload(
            runner,
            new ArtifactUpload(
                run.agentId(),
                null,
                ArtifactType.DIFF,
                "changes.diff",
                "text/x-diff",
                sha256(diff.getBytes(StandardCharsets.UTF_8)),
                Map.of()),
            diff.getBytes(StandardCharsets.UTF_8));
    assertThat(again.path("duplicate").asBoolean()).isTrue();
    assertThat(again.path("id").asString()).isEqualTo(id);

    JsonNode list = get("/api/agent-runs/" + run.agentId() + "/artifacts");
    assertThat(list).hasSize(1);
    JsonNode summary = list.get(0);
    assertThat(summary.path("type").asString()).isEqualTo("DIFF");
    assertThat(summary.path("metadata").path("files").asInt()).isEqualTo(1);
    assertThat(summary.path("metadata").path("originalSha256").asString())
        .isEqualTo(sha256(diff.getBytes(StandardCharsets.UTF_8)));
    assertThat(summary.path("truncated").asBoolean()).isFalse();

    ResponseEntity<String> content = content(id, 0, 1_000_000);
    assertThat(content.getBody()).contains("+    return a + b").doesNotContain("ghp_");
    assertThat(content.getBody()).contains("[REDACTED]");
    assertThat(content.getHeaders().getContentType().toString())
        .isEqualTo("text/plain;charset=UTF-8");
    long size = Long.parseLong(content.getHeaders().getFirst("X-Artifact-Size"));
    assertThat(size).isEqualTo(summary.path("size").asLong());

    ResponseEntity<String> chunk = content(id, 5, 10);
    assertThat(chunk.getBody()).isEqualTo(content.getBody().substring(5, 15));
    assertThat(chunk.getHeaders().getFirst("X-Artifact-Offset")).isEqualTo("5");
    assertThat(content(id, size + 10, 10).getBody()).isNullOrEmpty();
    assertThat(eventTypes(run)).contains("artifact.created");
  }

  @Test
  void largeArtifactsAreTruncatedAndBadUploadsRejected() {
    Runner runner = register("owner");
    Launched run = launch(null);
    ack(runner, poll(runner).getFirst());

    // El máximo en los tests es 64 KB.
    byte[] log = "línea del NDJSON\n".repeat(10_000).getBytes(StandardCharsets.UTF_8);
    JsonNode stored =
        upload(
            runner,
            new ArtifactUpload(
                run.agentId(),
                null,
                ArtifactType.LOG,
                "agent.ndjson",
                "application/x-ndjson",
                sha256(log),
                Map.of()),
            log);
    JsonNode summary = get("/api/agent-runs/" + run.agentId() + "/artifacts").get(0);
    assertThat(summary.path("truncated").asBoolean()).isTrue();
    assertThat(summary.path("size").asLong()).isLessThanOrEqualTo(64 * 1024);
    assertThat(summary.path("metadata").path("originalSize").asLong()).isEqualTo(log.length);
    String text = content(stored.path("id").asString(), 0, 1_000_000).getBody();
    assertThat(text).endsWith(" bytes más]\n").doesNotContain("�");

    byte[] other = "x".getBytes(StandardCharsets.UTF_8);
    assertStatus(
        HttpStatus.BAD_REQUEST,
        () ->
            upload(
                runner,
                new ArtifactUpload(
                    run.agentId(),
                    null,
                    ArtifactType.PROMPT,
                    "prompt.txt",
                    "text/plain",
                    "00",
                    Map.of()),
                other));
    assertStatus(
        HttpStatus.NOT_FOUND,
        () ->
            upload(
                runner,
                new ArtifactUpload(
                    run.agentId(),
                    UUID.randomUUID(),
                    ArtifactType.TEST_REPORT,
                    "tests.json",
                    "application/json",
                    sha256(other),
                    Map.of()),
                other));
    Runner stranger = register("stranger");
    assertStatus(
        HttpStatus.CONFLICT,
        () ->
            upload(
                stranger,
                new ArtifactUpload(
                    run.agentId(),
                    null,
                    ArtifactType.PROMPT,
                    "prompt.txt",
                    "text/plain",
                    sha256(other),
                    Map.of()),
                other));
    assertStatus(
        HttpStatus.NOT_FOUND, () -> get("/api/artifacts/" + UUID.randomUUID() + "/content"));
  }

  // --- utilidades ---

  private record Runner(UUID id, String token) {}

  private record Launched(UUID runId, UUID agentId) {}

  /** Eventos de una invocación con {@code seq} consecutivo, como los genera el runner. */
  private static final class Events {
    private final UUID agentRunId;
    private final List<NormalizedEvent> all = new ArrayList<>();
    private final int firstSeq;

    Events(UUID agentRunId, int firstSeq) {
      this.agentRunId = agentRunId;
      this.firstSeq = firstSeq;
    }

    void add(AgentEventType type, Map<String, ?> payload) {
      all.add(
          new NormalizedEvent(
              UUID.randomUUID(),
              agentRunId,
              firstSeq + all.size(),
              Instant.now(),
              type,
              new LinkedHashMap<>(payload)));
    }

    EventBatch batch() {
      return new EventBatch(all);
    }
  }

  private String projectId;
  private String repositoryId;
  private String workItemId;

  /** Lanza un agente sobre un repositorio con ese comando de validación (o ninguno). */
  private Launched launch(String validationCommand) {
    if (projectId == null) {
      projectId = post("/api/projects", Map.of("key", "VER", "name", "Ver")).path("id").asString();
      Map<String, Object> repository = new LinkedHashMap<>();
      repository.put("name", "demo");
      repository.put("localPath", "/repos/demo");
      repository.put("validationCommand", validationCommand);
      repositoryId =
          post("/api/projects/" + projectId + "/repositories", repository).path("id").asString();
      workItemId =
          post("/api/projects/" + projectId + "/work-items", Map.of("title", "T", "type", "BUG"))
              .path("id")
              .asString();
    }
    JsonNode run =
        post(
            "/api/work-items/" + workItemId + "/runs",
            Map.of("repositoryId", repositoryId, "prompt", "Arregla add()"));
    return new Launched(
        UUID.fromString(run.path("id").asString()),
        UUID.fromString(run.path("stages").get(0).path("agents").get(0).path("id").asString()));
  }

  /** Recorre una invocación completa: worktree, sesión, resultado y salida limpia. */
  private void completed(Runner runner, UUID agentRunId) {
    Events events = new Events(agentRunId, 1);
    events.add(
        AgentEventType.WORKSPACE_READY,
        Map.of("path", "/w/run/a1", "branch", "skynet/ver-1/a1", "baseCommit", "abc"));
    events.add(AgentEventType.SESSION_STARTED, Map.of("sessionId", UUID.randomUUID().toString()));
    events.add(AgentEventType.MESSAGE_RECEIVED, Map.of("text", "Hecho"));
    events.add(
        AgentEventType.RESULT,
        Map.of(
            "subtype", "success", "isError", false, "costUsdCumulative", new BigDecimal("0.05")));
    events.add(AgentEventType.PROCESS_EXITED, Map.of("exitCode", 0));
    send(runner, events.batch());
    assertThat(get("/api/agent-runs/" + agentRunId).path("agent").path("status").asString())
        .isEqualTo("COMPLETED");
  }

  private JsonNode upload(Runner runner, ArtifactUpload metadata, byte[] content) {
    MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
    HttpHeaders jsonHeaders = new HttpHeaders();
    jsonHeaders.setContentType(MediaType.APPLICATION_JSON);
    parts.add("metadata", new org.springframework.http.HttpEntity<>(metadata, jsonHeaders));
    parts.add(
        "content",
        new ByteArrayResource(content) {
          @Override
          public String getFilename() {
            return metadata.name();
          }
        });
    return http.post()
        .uri("/api/runner/artifacts")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + runner.token())
        .contentType(MediaType.MULTIPART_FORM_DATA)
        .body(parts)
        .retrieve()
        .body(JsonNode.class);
  }

  private ResponseEntity<String> content(String artifactId, long offset, int limit) {
    return http.get()
        .uri("/api/artifacts/" + artifactId + "/content?offset=" + offset + "&limit=" + limit)
        .retrieve()
        .toEntity(String.class);
  }

  private static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private Runner register(String name) {
    JsonNode registered =
        http.post()
            .uri("/api/runner/register")
            .body(new RunnerRegistration(name, RUNNER_REGISTRATION_TOKEN, "dev", 1, "2.1.288"))
            .retrieve()
            .body(JsonNode.class);
    return new Runner(
        UUID.fromString(registered.path("runnerId").asString()),
        registered.path("token").asString());
  }

  private List<JsonNode> poll(Runner runner) {
    List<JsonNode> list = new ArrayList<>();
    http.get()
        .uri("/api/runner/commands?waitSeconds=0")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + runner.token())
        .retrieve()
        .body(JsonNode.class)
        .forEach(list::add);
    return list;
  }

  private void ack(Runner runner, JsonNode command) {
    http.post()
        .uri("/api/runner/commands/" + command.path("id").asString() + "/ack")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + runner.token())
        .retrieve()
        .toBodilessEntity();
  }

  private JsonNode send(Runner runner, EventBatch batch) {
    return http.post()
        .uri("/api/runner/events")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + runner.token())
        .body(batch)
        .retrieve()
        .body(JsonNode.class);
  }

  private JsonNode agent(Launched run) {
    return get("/api/agent-runs/" + run.agentId()).path("agent");
  }

  private List<JsonNode> verifications(Launched run) {
    List<JsonNode> list = new ArrayList<>();
    get("/api/agent-runs/" + run.agentId() + "/verifications").forEach(list::add);
    return list;
  }

  private List<String> eventTypes(Launched run) {
    List<String> types = new ArrayList<>();
    get("/api/events?workflowRunId=" + run.runId() + "&limit=500")
        .forEach(e -> types.add(e.path("type").asString()));
    return types;
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

  private static void assertStatus(HttpStatus expected, Runnable call) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            HttpClientErrorException.class,
            e -> assertThat(e.getStatusCode().value()).isEqualTo(expected.value()));
  }
}
