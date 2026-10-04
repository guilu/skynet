package dev.skynet.controlplane.event;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skynet.controlplane.support.IntegrationTest;
import dev.skynet.controlplane.support.SseClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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
            "INSERT INTO project (id, key, name, created_at, version) VALUES (?, 'EVT', 'e', now(), 0)")
        .param(project)
        .update();
    jdbc.sql(
            "INSERT INTO work_item (id, project_id, number, key, title, type, status, created_at,"
                + " version) VALUES (?, ?, 1, 'EVT-1', 't', 'FEATURE', 'OPEN', now(), 0)")
        .params(workItem, project)
        .update();
    jdbc.sql(
            "INSERT INTO workflow_run (id, work_item_id, definition_id, status, created_at, version)"
                + " VALUES (?, ?, '00000000-0000-0000-0000-000000000001', 'RUNNING', now(), 0)")
        .params(run, workItem)
        .update();
    return run;
  }
}
