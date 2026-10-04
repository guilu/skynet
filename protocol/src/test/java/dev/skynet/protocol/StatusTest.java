package dev.skynet.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class StatusTest {

  @Test
  void terminalStageStatuses() {
    assertThat(Arrays.stream(StageStatus.values()).filter(StageStatus::isTerminal))
        .containsExactlyInAnyOrder(
            StageStatus.SUCCEEDED, StageStatus.FAILED, StageStatus.CANCELLED, StageStatus.SKIPPED);
  }

  @Test
  void unresponsiveIsNotTerminal() {
    assertThat(AgentObservableStatus.UNRESPONSIVE.isTerminal()).isFalse();
    assertThat(AgentObservableStatus.COMPLETED.isTerminal()).isTrue();
  }
}
