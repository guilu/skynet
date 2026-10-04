package dev.skynet.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class StatusTest {

  @Test
  void terminalStageStatuses() {
    assertThat(Arrays.stream(StageStatus.values()).filter(StageStatus::isTerminal))
        .containsExactlyInAnyOrder(
            StageStatus.SUCCEEDED, StageStatus.FAILED, StageStatus.CANCELLED, StageStatus.SKIPPED);
  }

  @ParameterizedTest
  @EnumSource(StageStatus.class)
  void stagesNeverTransitionToThemselvesOrBackToPending(StageStatus status) {
    assertThat(status.canTransitionTo(status)).isFalse();
    assertThat(status.canTransitionTo(StageStatus.PENDING)).isFalse();
  }

  @Test
  void stageHappyPathAndRetry() {
    assertThat(StageStatus.PENDING.canTransitionTo(StageStatus.READY)).isTrue();
    assertThat(StageStatus.READY.canTransitionTo(StageStatus.STARTING)).isTrue();
    assertThat(StageStatus.STARTING.canTransitionTo(StageStatus.RUNNING)).isTrue();
    assertThat(StageStatus.RUNNING.canTransitionTo(StageStatus.SUCCEEDED)).isTrue();
    assertThat(StageStatus.FAILED.canTransitionTo(StageStatus.READY)).isTrue();
    assertThat(StageStatus.SUCCEEDED.allowedNext()).isEmpty();
    assertThat(StageStatus.PENDING.canTransitionTo(StageStatus.SUCCEEDED)).isFalse();
  }

  @ParameterizedTest
  @EnumSource(AgentObservableStatus.class)
  void agentTerminalStatusesHaveNoExit(AgentObservableStatus status) {
    assertThat(status.allowedNext().isEmpty()).isEqualTo(status.isTerminal());
    assertThat(status.canTransitionTo(status)).isFalse();
  }

  @Test
  void agentActiveStatusesAlternateFreely() {
    assertThat(AgentObservableStatus.THINKING.canTransitionTo(AgentObservableStatus.EXECUTING))
        .isTrue();
    assertThat(AgentObservableStatus.EXECUTING.canTransitionTo(AgentObservableStatus.UNRESPONSIVE))
        .isTrue();
    assertThat(AgentObservableStatus.UNRESPONSIVE.canTransitionTo(AgentObservableStatus.THINKING))
        .isTrue();
    assertThat(AgentObservableStatus.UNRESPONSIVE.isTerminal()).isFalse();
  }

  @Test
  void agentCannotSkipStartup() {
    assertThat(AgentObservableStatus.QUEUED.canTransitionTo(AgentObservableStatus.THINKING))
        .isFalse();
    assertThat(AgentObservableStatus.QUEUED.canTransitionTo(AgentObservableStatus.CANCELLED))
        .isTrue();
    assertThat(AgentObservableStatus.STARTING.canTransitionTo(AgentObservableStatus.COMPLETED))
        .isFalse();
  }

  @ParameterizedTest
  @EnumSource(WorkflowRunStatus.class)
  void workflowTerminalStatusesHaveNoExit(WorkflowRunStatus status) {
    assertThat(status.allowedNext().isEmpty()).isEqualTo(status.isTerminal());
  }
}
