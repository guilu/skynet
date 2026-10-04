package dev.skynet.runner.journal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skynet.protocol.AgentEventType;
import dev.skynet.protocol.NormalizedEvent;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JournalTest {

  @TempDir Path dir;

  @Test
  void eventsGetConsecutiveSeqPerAgentAndSurviveAReopen() throws Exception {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    try (Journal journal = Journal.open(dir.resolve("j.db"))) {
      journal.append(a, AgentEventType.SESSION_STARTED, Map.of("sessionId", "s"), Instant.now());
      journal.append(b, AgentEventType.SESSION_STARTED, Map.of(), Instant.now());
      journal.append(
          a,
          AgentEventType.RESULT,
          Map.of("costUsdCumulative", new BigDecimal("0.08147199999999999")),
          Instant.now());
    }
    try (Journal journal = Journal.open(dir.resolve("j.db"))) {
      List<NormalizedEvent> pending = journal.pending(10);
      assertThat(pending)
          .extracting(NormalizedEvent::agentRunId, NormalizedEvent::seq)
          .containsExactly(
              org.assertj.core.groups.Tuple.tuple(a, 1L),
              org.assertj.core.groups.Tuple.tuple(b, 1L),
              org.assertj.core.groups.Tuple.tuple(a, 2L));
      assertThat(pending.get(2).payload())
          .containsEntry("costUsdCumulative", new BigDecimal("0.08147199999999999"));

      journal.delivered(List.of(pending.get(0).eventId(), pending.get(1).eventId()));
      assertThat(journal.pending(10)).extracting(NormalizedEvent::seq).containsExactly(2L);

      // El seq sigue aunque se hayan borrado los anteriores.
      assertThat(journal.append(a, AgentEventType.RAW, Map.of(), Instant.now()).seq()).isEqualTo(3);
    }
  }

  @Test
  void commandsAreRecordedOnceAndStartsTrackTheirEnd() throws Exception {
    try (Journal journal = Journal.open(dir.resolve("j.db"))) {
      UUID command = UUID.randomUUID();
      UUID agent = UUID.randomUUID();
      assertThat(journal.firstDelivery(command, agent, "START")).isTrue();
      assertThat(journal.firstDelivery(command, agent, "START")).isFalse();
      assertThat(journal.unfinishedStarts()).containsExactly(agent);

      journal.processStarted(agent, 1234, Instant.now());
      assertThat(journal.processes())
          .extracting(Journal.TrackedProcess::pid)
          .containsExactly(1234L);

      NormalizedEvent exit = journal.finish(agent, Map.of("exitCode", 0), Instant.now());
      assertThat(exit.type()).isEqualTo(AgentEventType.PROCESS_EXITED);
      assertThat(journal.isFinished(agent)).isTrue();
      assertThat(journal.unfinishedStarts()).isEmpty();
      assertThat(journal.processes()).isEmpty();
    }
  }

  @Test
  void credentials() throws Exception {
    try (Journal journal = Journal.open(dir.resolve("j.db"))) {
      assertThat(journal.credentials()).isEmpty();
      UUID id = UUID.randomUUID();
      journal.saveCredentials(new Journal.Credentials("http://cp", id, "t1"));
      journal.saveCredentials(new Journal.Credentials("http://cp", id, "t2"));
      assertThat(journal.credentials()).contains(new Journal.Credentials("http://cp", id, "t2"));
    }
  }
}
