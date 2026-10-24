package dev.skynet.controlplane.event;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skynet.controlplane.support.IntegrationTest;
import dev.skynet.controlplane.support.SseClient;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;

class EventStreamIT extends IntegrationTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  @Autowired EventStore store;

  @Test
  void deliversHistoryThenLiveEvents() throws Exception {
    append("test.before");
    try (SseClient sse = new SseClient(streamUrl(""), null)) {
      assertThat(sse.await(1, TIMEOUT).getFirst().data().path("type").asString())
          .isEqualTo("test.before");

      append("test.live");
      SseClient.Message live = sse.await(1, TIMEOUT).getFirst();
      assertThat(live.data().path("type").asString()).isEqualTo("test.live");
      assertThat(live.id()).isEqualTo(baseline + 2);
    }
  }

  @Test
  void resumesAfterLastEventIdWithoutGapsOrDuplicates() throws Exception {
    for (int i = 1; i <= 3; i++) {
      append("test.e" + i);
    }
    try (SseClient first = new SseClient(streamUrl(""), null)) {
      assertThat(first.await(3, TIMEOUT))
          .extracting(SseClient.Message::id)
          .containsExactly(baseline + 1, baseline + 2, baseline + 3);
    }

    // Mientras el cliente está desconectado se registran más eventos.
    append("test.e4");
    append("test.e5");

    try (SseClient resumed = new SseClient(streamUrl(""), baseline + 3)) {
      List<SseClient.Message> messages = resumed.await(2, TIMEOUT);
      assertThat(messages)
          .extracting(SseClient.Message::id)
          .containsExactly(baseline + 4, baseline + 5);
      append("test.e6");
      assertThat(resumed.await(1, TIMEOUT).getFirst().id()).isEqualTo(baseline + 6);
      assertThat(resumed.poll(Duration.ofMillis(800))).isNull();
    }
  }

  @Test
  void filtersByWorkflowRun() throws Exception {
    UUID run = insertWorkflowRunRow();
    try (SseClient sse = new SseClient(streamUrl("?workflowRunId=" + run), null)) {
      append("test.other");
      store.append(
          EventDraft.of("test", UUID.randomUUID(), "test.mine", run, Map.of(), Instant.now()));
      SseClient.Message message = sse.await(1, TIMEOUT).getFirst();
      assertThat(message.data().path("type").asString()).isEqualTo("test.mine");
      assertThat(sse.poll(Duration.ofMillis(800))).isNull();
    }
  }

  @Test
  void latestSkipsHistory() throws Exception {
    append("test.old");
    try (SseClient sse = new SseClient(streamUrl("?after=latest"), null)) {
      // Hasta que el stream lee «latest» lo nuevo también se salta: se repite hasta que llega uno.
      SseClient.Message first = null;
      for (int i = 0; i < 50 && first == null; i++) {
        append("test.new");
        first = sse.poll(Duration.ofMillis(200));
      }
      assertThat(first).isNotNull();
      assertThat(first.data().path("type").asString()).isEqualTo("test.new");
      assertThat(first.id()).isGreaterThan(baseline + 1);
    }
  }

  @Test
  void filtersByExactTypesAndPrefixes() throws Exception {
    append("approval.requested");
    append("agent.tool.started");
    append("agent.rate_limit");
    append("agent.rateXlimit");
    append("workflow.status.changed");
    try (SseClient sse =
        new SseClient(
            streamUrl("?types=approval.*,agent.rate_limit,workflow.status.changed"), null)) {
      assertThat(sse.await(3, TIMEOUT))
          .extracting(m -> m.data().path("type").asString())
          .containsExactly("approval.requested", "agent.rate_limit", "workflow.status.changed");
      append("agent.message.received");
      append("approval.granted");
      assertThat(sse.await(1, TIMEOUT).getFirst().data().path("type").asString())
          .isEqualTo("approval.granted");
      assertThat(sse.poll(Duration.ofMillis(800))).isNull();
    }
  }

  @Test
  void resumesWithTypesAfterLastEventId() throws Exception {
    append("stage.ready");
    append("agent.raw");
    append("stage.status.changed");
    try (SseClient resumed =
        new SseClient(streamUrl("?after=latest&types=stage.*"), baseline + 1)) {
      assertThat(resumed.await(1, TIMEOUT).getFirst().id()).isEqualTo(baseline + 3);
      assertThat(resumed.poll(Duration.ofMillis(800))).isNull();
    }
  }

  @Test
  void historyFiltersByTypes() {
    append("approval.requested");
    append("agent.raw");
    List<StoredEvent> page =
        http.get()
            .uri("/api/events?after={after}&types=approval.*", baseline)
            .retrieve()
            .body(new ParameterizedTypeReference<List<StoredEvent>>() {});
    assertThat(page).extracting(StoredEvent::type).containsExactly("approval.requested");
  }

  @Test
  void rejectsMalformedTypesAndAfter() {
    for (String query : List.of("?types=approval.%25", "?types=a,,b", "?after=pronto")) {
      Integer status =
          http.get()
              .uri(URI.create(streamUrl(query)))
              .exchange((request, response) -> response.getStatusCode().value());
      assertThat(status).as(query).isEqualTo(400);
    }
  }

  private void append(String type) {
    store.append(EventDraft.of("test", UUID.randomUUID(), type, null, Map.of(), Instant.now()));
  }

  private String streamUrl(String query) {
    return "http://localhost:" + port + "/api/events/stream" + query;
  }

  /** Inserta lo mínimo para que exista un workflow_run al que referir los eventos. */
  private UUID insertWorkflowRunRow() {
    UUID project = UUID.randomUUID();
    UUID workItem = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO project (id, key, name, created_at, version) VALUES (?, 'EVT', 'e', now(),"
                + " 0)")
        .param(project)
        .update();
    jdbc.sql(
            "INSERT INTO work_item (id, project_id, number, key, title, type, status, created_at,"
                + " version) VALUES (?, ?, 1, 'EVT-1', 't', 'FEATURE', 'OPEN', now(), 0)")
        .params(workItem, project)
        .update();
    jdbc.sql(
            "INSERT INTO workflow_run (id, work_item_id, definition_id, status, created_at,"
                + " version) VALUES (?, ?, '00000000-0000-0000-0000-000000000001', 'RUNNING',"
                + " now(), 0)")
        .params(run, workItem)
        .update();
    return run;
  }
}
