package dev.skynet.controlplane.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skynet.controlplane.support.IntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class EventStoreIT extends IntegrationTest {

  @Autowired EventStore store;

  @Test
  void assignsConsecutiveSequencesAndStoresThePayload() {
    UUID aggregate = UUID.randomUUID();
    StoredEvent first = store.append(draft(aggregate, "test.first", null));
    StoredEvent second = store.append(draft(aggregate, "test.second", null));

    assertThat(first.sequence()).isEqualTo(baseline + 1);
    assertThat(second.sequence()).isEqualTo(baseline + 2);
    assertThat(store.list(0, new EventFilter(null, aggregate), 10))
        .extracting(StoredEvent::type, e -> e.payload().path("n").asInt())
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("test.first", 7),
            org.assertj.core.groups.Tuple.tuple("test.second", 7));
  }

  @Test
  void ignoresDuplicatesBySourceEventId() {
    UUID aggregate = UUID.randomUUID();
    StoredEvent original = store.append(draft(aggregate, "test.ingested", "runner-evt-1"));
    StoredEvent duplicate = store.append(draft(aggregate, "test.ingested", "runner-evt-1"));

    assertThat(duplicate.eventId()).isEqualTo(original.eventId());
    assertThat(store.list(0, EventFilter.ALL, 10)).hasSize(1);
  }

  @Test
  void rejectsUpdatesAndDeletes() {
    store.append(draft(UUID.randomUUID(), "test.immutable", null));

    assertThatThrownBy(() -> jdbc.sql("UPDATE event SET event_type = 'x'").update())
        .hasMessageContaining("append-only");
    assertThatThrownBy(() -> jdbc.sql("DELETE FROM event").update())
        .hasMessageContaining("append-only");
  }

  @Test
  void concurrentAppendsProduceAGapFreeSequence() throws Exception {
    int writers = 8;
    int perWriter = 25;
    List<Callable<Void>> tasks = new ArrayList<>();
    for (int w = 0; w < writers; w++) {
      tasks.add(
          () -> {
            for (int i = 0; i < perWriter; i++) {
              store.append(draft(UUID.randomUUID(), "test.concurrent", null));
            }
            return null;
          });
    }
    try (ExecutorService pool = Executors.newFixedThreadPool(writers)) {
      for (Future<Void> f : pool.invokeAll(tasks)) {
        f.get();
      }
    }

    List<Long> sequences =
        store.list(0, EventFilter.ALL, 1000).stream().map(StoredEvent::sequence).toList();
    assertThat(sequences)
        .containsExactlyElementsOf(
            LongStream.rangeClosed(baseline + 1, baseline + (long) writers * perWriter)
                .boxed()
                .toList());
  }

  private static EventDraft draft(UUID aggregate, String type, String sourceEventId) {
    return new EventDraft(
        "test", aggregate, type, null, Map.of("n", 7), sourceEventId, Instant.now());
  }
}
