package dev.skynet.runner;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RunnerMainTest {

  @Test
  void reportsDevVersionWhenNotPackaged() {
    assertThat(RunnerMain.version()).isEqualTo("dev");
  }
}
