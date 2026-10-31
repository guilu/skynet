package dev.skynet.controlplane.github;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Qué entregas de webhook despiertan la espera de una CI (EN-B) y el final de un log de Actions.
 */
class CiWebhookTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void ciDeliveriesNameTheCommitTheyAreAbout() {
    assertThat(
            WebhookProcessor.checkedSha(
                "check_run", JSON.readTree("{\"check_run\":{\"head_sha\":\"abc\"}}")))
        .isEqualTo("abc");
    assertThat(
            WebhookProcessor.checkedSha(
                "check_suite", JSON.readTree("{\"check_suite\":{\"head_sha\":\"def\"}}")))
        .isEqualTo("def");
    assertThat(WebhookProcessor.checkedSha("status", JSON.readTree("{\"sha\":\"123\"}")))
        .isEqualTo("123");
    assertThat(WebhookProcessor.checkedSha("pull_request", JSON.readTree("{\"sha\":\"123\"}")))
        .isNull();
    assertThat(WebhookProcessor.checkedSha("check_run", JSON.readTree("{}"))).isNull();
  }

  @Test
  void theTailOfAnActionsLogDropsItsTimestamps() {
    String log =
        "2026-10-31T10:00:00.1234567Z uno\n2026-10-31T10:00:01.0000000Z dos\r\n"
            + "2026-10-31T10:00:02.0000000Z tres\n";
    assertThat(GitHubRepositories.tail(log, 2)).isEqualTo("dos\ntres");
    assertThat(GitHubRepositories.tail("sin marca", 5)).isEqualTo("sin marca");
  }
}
