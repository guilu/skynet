package dev.skynet.protocol.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skynet.protocol.AgentEventType;
import dev.skynet.protocol.NormalizedEvent;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class RunnerMessagesJsonTest {

  private static final ObjectMapper JSON = JsonMapper.builder().build();
  private static final Instant NOW = Instant.parse("2026-10-04T09:00:00Z");

  @Test
  void startCommandRoundTrip() {
    StartAgent start =
        new StartAgent(
            UUID.randomUUID(),
            "TKM-1",
            "/home/dev/demo",
            "main",
            UUID.randomUUID(),
            "Arregla add()",
            List.of("Read", "Edit"),
            "dontAsk",
            null,
            new AgentLimits(10, new BigDecimal("0.50"), Duration.ofMinutes(30)));
    RunnerCommand command = RunnerCommand.start(UUID.randomUUID(), UUID.randomUUID(), NOW, start);

    assertThat(roundTrip(command, RunnerCommand.class)).isEqualTo(command);
  }

  @Test
  void cancelCommandRoundTrip() {
    RunnerCommand command = RunnerCommand.cancel(UUID.randomUUID(), UUID.randomUUID(), NOW);

    assertThat(roundTrip(command, RunnerCommand.class)).isEqualTo(command);
  }

  @Test
  void startPayloadOnlyInStartCommands() {
    assertThatThrownBy(
            () ->
                new RunnerCommand(
                    UUID.randomUUID(), RunnerCommandType.START, UUID.randomUUID(), NOW, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void eventBatchRoundTripKeepsWireNameAndPayload() {
    NormalizedEvent event =
        new NormalizedEvent(
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            NOW,
            AgentEventType.TOOL_STARTED,
            Map.of("name", "Read", "input", Map.of("file_path", "/w/calc.py")));
    EventBatch batch = new EventBatch(List.of(event));

    EventBatch back = roundTrip(batch, EventBatch.class);

    assertThat(back).isEqualTo(batch);
    assertThat(JSON.writeValueAsString(event)).contains("\"type\":\"TOOL_STARTED\"");
  }

  @Test
  void wireNamesAreUniqueAndResolvable() {
    for (AgentEventType type : AgentEventType.values()) {
      assertThat(AgentEventType.fromWireName(type.wireName())).isSameAs(type);
      assertThat(type.wireName()).startsWith("agent.");
    }
    assertThatThrownBy(() -> AgentEventType.fromWireName("agent.nope"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void registrationAndHeartbeatRoundTrip() {
    RunnerRegistration registration =
        new RunnerRegistration("laptop", "secret", "dev", 2, "2.1.288");
    RunnerHeartbeat heartbeat = new RunnerHeartbeat(2, List.of(UUID.randomUUID()));
    RunnerRegistered registered = new RunnerRegistered(UUID.randomUUID(), "token");

    assertThat(roundTrip(registration, RunnerRegistration.class)).isEqualTo(registration);
    assertThat(roundTrip(heartbeat, RunnerHeartbeat.class)).isEqualTo(heartbeat);
    assertThat(roundTrip(registered, RunnerRegistered.class)).isEqualTo(registered);
  }

  private static <T> T roundTrip(T value, Class<T> type) {
    return JSON.readValue(JSON.writeValueAsString(value), type);
  }
}
