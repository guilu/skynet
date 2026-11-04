package dev.skynet.controlplane.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WorkPhaseTest {

  @Test
  void closedWinsOverEverything() {
    assertThat(WorkPhase.of(true, true, true, "OPEN", "FAILED")).isEqualTo(WorkPhase.DONE);
  }

  @Test
  void aPendingApprovalComesBeforeTheRunningAttempt() {
    assertThat(WorkPhase.of(false, true, true, null, "RUNNING")).isEqualTo(WorkPhase.WAITING);
    assertThat(WorkPhase.of(false, false, true, "OPEN", "RUNNING")).isEqualTo(WorkPhase.RUNNING);
  }

  @Test
  void anOpenPullRequestIsInReviewEvenAfterAFailedAttempt() {
    assertThat(WorkPhase.of(false, false, false, "OPEN", "FAILED")).isEqualTo(WorkPhase.REVIEW);
  }

  @Test
  void mergedOrSucceededIsDoneAndFailedIsStuck() {
    assertThat(WorkPhase.of(false, false, false, "MERGED", "FAILED")).isEqualTo(WorkPhase.DONE);
    assertThat(WorkPhase.of(false, false, false, null, "SUCCEEDED")).isEqualTo(WorkPhase.DONE);
    assertThat(WorkPhase.of(false, false, false, null, "FAILED")).isEqualTo(WorkPhase.STUCK);
  }

  @Test
  void withoutAttemptsOrAfterACancelItIsToDo() {
    assertThat(WorkPhase.of(false, false, false, null, null)).isEqualTo(WorkPhase.TODO);
    assertThat(WorkPhase.of(false, false, false, null, "CANCELLED")).isEqualTo(WorkPhase.TODO);
  }
}
