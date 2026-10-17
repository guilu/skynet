package dev.skynet.runner.provider.codex;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skynet.protocol.runner.QuotaSample;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class CodexQuotaTest {

  private static final String THREAD = "01a149c9-d542-7f82-9f24-d6f2ea253867";

  @TempDir Path home;

  @Test
  void theLastTokenCountOfTheSessionFileHasBothWindows() throws IOException {
    // Grabado con codex-cli 0.163.0, en su sitio: sessions/AAAA/MM/DD/rollout-<fecha>-<sesión>.
    Path file =
        home.resolve("sessions/2026/10/17/rollout-2026-10-17T14-15-27-" + THREAD + ".jsonl");
    Files.createDirectories(file.getParent());
    String recorded;
    try (InputStream in =
        CodexQuotaTest.class.getResourceAsStream("/quota/codex-rollout-token-count.jsonl")) {
      recorded = new String(in.readAllBytes()).strip();
    }
    Files.writeString(
        file,
        "{\"type\":\"session_meta\",\"payload\":{}}\n"
            + recorded.replace("\"used_percent\":23.0", "\"used_percent\":20.0")
            + "\n"
            + recorded
            + "\n");

    List<QuotaSample> quota = CodexQuota.fromSession(home, THREAD);

    assertThat(quota)
        .extracting(QuotaSample::window, QuotaSample::usedFraction, QuotaSample::resetsAt)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                "five_hour", 0.23, Instant.ofEpochSecond(1792246811)),
            org.assertj.core.groups.Tuple.tuple(
                "seven_day", 0.13, Instant.ofEpochSecond(1792649703)));
    assertThat(quota)
        .allSatisfy(
            q -> {
              assertThat(q.provider()).isEqualTo("codex");
              assertThat(q.bucket()).isEqualTo("default");
              assertThat(q.plan()).isEqualTo("plus");
              assertThat(q.observedAt()).isEqualTo(Instant.parse("2026-10-17T12:15:38.500Z"));
            });
  }

  @Test
  void withoutTheSessionFileThereIsNoQuota() throws IOException {
    assertThat(CodexQuota.fromSession(home, THREAD)).isEmpty();
    assertThat(CodexQuota.fromSession(home, null)).isEmpty();
  }

  @Test
  void theAppServerSpellsItInCamelCaseAndOlderVersionsGiveSecondsToReset() {
    Instant now = Instant.parse("2026-10-17T12:00:00Z");
    var json = JsonMapper.builder().build();

    assertThat(
            CodexQuota.fromRateLimits(
                json.readTree(
                    "{\"primary\":{\"usedPercent\":40,\"windowDurationMins\":300,"
                        + "\"resetsAt\":1792246811},\"secondary\":{\"usedPercent\":5,"
                        + "\"windowDurationMins\":10080},\"planType\":\"pro\"}"),
                now))
        .extracting(QuotaSample::window, QuotaSample::usedFraction, QuotaSample::plan)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("five_hour", 0.4, "pro"),
            org.assertj.core.groups.Tuple.tuple("seven_day", 0.05, "pro"));
    assertThat(
            CodexQuota.fromRateLimits(
                json.readTree("{\"primary\":{\"used_percent\":10,\"resets_in_seconds\":60}}"), now))
        .singleElement()
        .satisfies(q -> assertThat(q.resetsAt()).isEqualTo(now.plusSeconds(60)));
  }

  @Test
  void theCodexHomeIsCodexHomeOrTheUsersDotCodex() {
    assertThat(CodexQuota.home(Map.of("CODEX_HOME", "/srv/codex")))
        .isEqualTo(Path.of("/srv/codex"));
    assertThat(CodexQuota.home(Map.of("HOME", "/home/diego")))
        .isEqualTo(Path.of("/home/diego/.codex"));
  }

  @Test
  void theAppServerOfFakeCodexAnswersTheQuota() throws Exception {
    String fake = Path.of(System.getProperty("skynet.fakeCodex")).toAbsolutePath().toString();
    Map<String, String> env =
        Map.of(
            "PATH", System.getenv("PATH"),
            "JAVA_HOME", System.getProperty("java.home"),
            "FAKE_CODEX_QUOTA", "61,8");

    assertThat(new CodexAppServerQuota(fake, env, "test").read())
        .extracting(QuotaSample::window, QuotaSample::usedFraction)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("five_hour", 0.61),
            org.assertj.core.groups.Tuple.tuple("seven_day", 0.08));
  }
}
