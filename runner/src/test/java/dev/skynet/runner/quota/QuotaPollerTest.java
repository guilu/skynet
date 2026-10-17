package dev.skynet.runner.quota;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skynet.protocol.runner.QuotaSample;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuotaPollerTest {

  private static QuotaSample sample(String provider, double used) {
    return new QuotaSample(provider, null, null, "five_hour", used, null, null, Instant.now());
  }

  private static QuotaSource source(String provider, double used) {
    return new QuotaSource() {
      @Override
      public String provider() {
        return provider;
      }

      @Override
      public List<QuotaSample> read() {
        return List.of(sample(provider, used));
      }
    };
  }

  @Test
  void aSourceThatFailsDoesNotStopTheOthersAndSamplesWaitForTheHeartbeat() {
    QuotaSource broken =
        new QuotaSource() {
          @Override
          public String provider() {
            return "antigravity";
          }

          @Override
          public List<QuotaSample> read() throws IOException {
            throw new IOException("agy no responde");
          }
        };
    try (QuotaPoller poller = new QuotaPoller(List.of(broken, source("codex", 0.2)), null)) {
      poller.poll();

      List<QuotaSample> drained = poller.drain();
      assertThat(drained).extracting(QuotaSample::provider).containsExactly("codex");
      assertThat(poller.drain()).isEmpty();
      // Un latido que no llegó las devuelve para el siguiente.
      poller.restore(drained);
      poller.poll();
      assertThat(poller.drain()).extracting(QuotaSample::usedFraction).containsExactly(0.2, 0.2);
    }
  }
}
