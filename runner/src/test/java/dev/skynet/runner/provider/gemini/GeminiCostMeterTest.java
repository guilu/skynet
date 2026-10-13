package dev.skynet.runner.provider.gemini;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skynet.runner.provider.ModelPrices;
import org.junit.jupiter.api.Test;

class GeminiCostMeterTest {

  @Test
  void pricesEachModelOfTheResult() {
    GeminiCostMeter meter = new GeminiCostMeter(ModelPrices.defaults());
    GeminiEventParserTest.lines("02-tools").forEach(meter::accept);
    meter.accept("no es json");

    assertThat(meter.estimatedUsd()).isEqualByComparingTo("0.02078");
    assertThat(meter.unpricedModels()).isEmpty();
  }

  @Test
  void aModelWithoutPriceIsReported() {
    GeminiCostMeter meter = new GeminiCostMeter(ModelPrices.defaults());
    meter.accept(
        "{\"type\":\"result\",\"status\":\"success\",\"stats\":{\"models\":{\"gemini-9-ultra\":"
            + "{\"input_tokens\":10,\"output_tokens\":1}}}}");

    assertThat(meter.estimatedUsd()).isZero();
    assertThat(meter.unpricedModels()).containsExactly("gemini-9-ultra");
  }
}
