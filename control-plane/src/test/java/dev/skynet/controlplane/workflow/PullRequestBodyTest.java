package dev.skynet.controlplane.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PullRequestBodyTest {

  @Test
  void closesTheIssueTheWorkItemCameFrom() {
    assertThat(PullRequestBody.closing("github:guilu/demo-repo#7", "guilu/demo-repo"))
        .isEqualTo("Closes #7");
    assertThat(PullRequestBody.closing("github:Guilu/Demo-Repo#7", "guilu/demo-repo"))
        .isEqualTo("Closes #7");
    assertThat(PullRequestBody.closing("github:guilu/skynet-commands#3", "guilu/demo-repo"))
        .isEqualTo("Closes guilu/skynet-commands#3");
    assertThat(PullRequestBody.closing(null, "guilu/demo-repo")).isNull();
    assertThat(PullRequestBody.closing("JIRA-12", "guilu/demo-repo")).isNull();
    assertThat(PullRequestBody.closing("github:guilu/demo-repo#7 y más", "guilu/demo-repo"))
        .isNull();

    String body =
        PullRequestBody.of(
            null, "DEMO-1", "Resta", null, List.of(), null, null, UUID.randomUUID(), "Closes #7");
    assertThat(body).contains("\nCloses #7\n").endsWith("Abierta por Skynet.\n");
    assertThat(
            PullRequestBody.of(
                null, "DEMO-1", "Resta", null, List.of(), null, null, UUID.randomUUID(), null))
        .doesNotContain("Closes");
  }
}
