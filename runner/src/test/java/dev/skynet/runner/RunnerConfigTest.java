package dev.skynet.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RunnerConfigTest {

  @Test
  void theQuotaPollIsTenMinutesByDefaultAndCanBeTurnedOff() {
    assertThat(RunnerConfig.fromEnvironment(Map.of()).quotaPoll())
        .isEqualTo(Duration.ofMinutes(10));
    assertThat(RunnerConfig.fromEnvironment(Map.of()).agyExecutable()).isEqualTo("agy");
    assertThat(RunnerConfig.interval("30s")).isEqualTo(Duration.ofSeconds(30));
    assertThat(RunnerConfig.interval("2h")).isEqualTo(Duration.ofHours(2));
    assertThat(RunnerConfig.interval("off")).isNull();
    assertThatThrownBy(() -> RunnerConfig.interval("diez"))
        .hasMessageContaining("SKYNET_QUOTA_POLL");
  }
}
