package dev.skynet.runner.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import dev.skynet.protocol.runner.QuotaSample;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgyQuotaTest {

  @Test
  void eachGroupHasItsWindowsAsUsedFractions() throws IOException {
    String recorded;
    // Grabado con agy 1.4.0 en la máquina de Diego.
    try (InputStream in = AgyQuotaTest.class.getResourceAsStream("/agy/usage.json")) {
      recorded = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    Instant now = Instant.parse("2026-10-17T18:50:56Z");

    assertThat(AgyQuota.parse(recorded, now))
        .extracting(
            QuotaSample::bucket, QuotaSample::label, QuotaSample::window, QuotaSample::resetsAt)
        .containsExactly(
            tuple("gemini", "Gemini Models", "seven_day", Instant.parse("2026-10-24T11:47:30Z")),
            tuple("gemini", "Gemini Models", "five_hour", Instant.parse("2026-10-17T23:50:56Z")),
            tuple(
                "3p", "Claude and GPT models", "seven_day", Instant.parse("2026-10-24T18:50:56Z")),
            tuple(
                "3p", "Claude and GPT models", "five_hour", Instant.parse("2026-10-17T23:50:56Z")),
            tuple(
                "gemini-argon-wtu",
                "Gemini 4 Argon",
                "seven_day",
                Instant.parse("2026-10-24T11:49:06Z")));
    assertThat(AgyQuota.parse(recorded, now).getLast().usedFraction())
        .isCloseTo(0.0419, org.assertj.core.data.Offset.offset(0.0001));
    assertThat(AgyQuota.parse(recorded, now))
        .allSatisfy(q -> assertThat(q.provider()).isEqualTo("antigravity"));
  }

  @Test
  void anUnknownOutputGivesNoQuota() {
    assertThat(AgyQuota.parse("Gemini Models\tWeekly Limit Remaining\t100%", Instant.now()))
        .isEmpty();
    assertThat(AgyQuota.parse("{\"status\":\"SUCCESS\"}", Instant.now())).isEmpty();
  }

  @Test
  void fakeAgyAnswersUsage() throws Exception {
    String fake = Path.of(System.getProperty("skynet.fakeAgy")).toAbsolutePath().toString();
    Map<String, String> env =
        Map.of("PATH", System.getenv("PATH"), "JAVA_HOME", System.getProperty("java.home"));

    assertThat(new AgyQuota(fake, env).read()).hasSize(5);
  }
}
