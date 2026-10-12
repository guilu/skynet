package dev.skynet.runner.provider.codex;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skynet.runner.provider.ModelPrices;
import org.junit.jupiter.api.Test;

class CodexCostMeterTest {

  @Test
  void addsTheUsageOfEveryTurn() {
    CodexCostMeter meter = new CodexCostMeter(ModelPrices.defaults(), "gpt-5.5");
    CodexEventParserTest.lines("01-simple-text").forEach(meter::accept);
    CodexEventParserTest.lines("01-simple-text").forEach(meter::accept);

    assertThat(meter.estimatedUsd()).isEqualByComparingTo("0.022862");
    assertThat(meter.unpricedModels()).isEmpty();
  }

  @Test
  void withoutAModelThereIsNoPrice() {
    CodexCostMeter meter = new CodexCostMeter(ModelPrices.defaults(), null);
    CodexEventParserTest.lines("02-tools").forEach(meter::accept);
    meter.accept("no es json");

    assertThat(meter.estimatedUsd()).isZero();
    assertThat(meter.unpricedModels()).containsExactly(CodexCostMeter.DEFAULT_MODEL);
  }
}
