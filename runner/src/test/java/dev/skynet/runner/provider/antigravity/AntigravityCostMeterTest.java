package dev.skynet.runner.provider.antigravity;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skynet.runner.provider.ModelPrices;
import org.junit.jupiter.api.Test;

class AntigravityCostMeterTest {

  @Test
  void addsTheUsageOfEachFinishedStep() {
    AntigravityCostMeter meter = new AntigravityCostMeter(ModelPrices.defaults(), "gemini-3-flash");
    AntigravityEventParserTest.lines("02-tools").forEach(meter::accept);
    meter.accept("no es json");

    // 16498 de entrada × 0,50 + 349 de salida × 3 + 19733 de caché × 0,05, por millón.
    assertThat(meter.estimatedUsd()).isEqualByComparingTo("0.01028265");
    assertThat(meter.unpricedModels()).isEmpty();
  }

  @Test
  void withoutAModelThereIsNoPrice() {
    AntigravityCostMeter meter = new AntigravityCostMeter(ModelPrices.defaults(), null);
    AntigravityEventParserTest.lines("01-simple-text").forEach(meter::accept);

    assertThat(meter.estimatedUsd()).isZero();
    assertThat(meter.unpricedModels()).containsExactly(AntigravityCostMeter.DEFAULT_MODEL);
  }
}
