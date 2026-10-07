package dev.skynet.runner.provider.claude;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CostEstimatorTest {

  /** El coste que declara el CLI en su línea {@code result} (sin reanudación previa). */
  @ParameterizedTest
  @CsvSource({
    "01-simple-text, 0.048965",
    "02-tools, 0.0573992",
    "05-max-turns, 0.0426406",
    "06-permission-denied, 0.0611548",
    "08-budget-exceeded, 0.0636206",
    "09-json-schema, 0.13326"
  })
  void estimatesTheCostTheCliReports(String fixture, BigDecimal reported) {
    CostEstimator estimator = estimate(fixture);

    assertThat(estimator.estimatedUsd())
        .isCloseTo(reported, Offset.offset(new BigDecimal("0.000001")));
    assertThat(estimator.unpricedModels()).isEmpty();
  }

  @Test
  void aResumedInvocationOnlyCountsItsOwnMessages() {
    // El result de 03-resume acumula la sesión (0.081472); la invocación anterior costó 0.0573992.
    assertThat(estimate("03-resume").estimatedUsd())
        .isCloseTo(new BigDecimal("0.0240728"), Offset.offset(new BigDecimal("0.000001")));
  }

  @Test
  void theCostGrowsWhileTheMessageStreams() {
    CostEstimator estimator = new CostEstimator(ModelPrices.defaults());
    estimator.accept(
        """
        {"type":"stream_event","event":{"type":"message_start","message":{"id":"m1",\
        "model":"claude-sonnet-5-5","usage":{"input_tokens":1000,"output_tokens":1}}}}""");
    BigDecimal started = estimator.estimatedUsd();
    estimator.accept(
        """
        {"type":"stream_event","event":{"type":"message_delta","usage":{"output_tokens":100000}}}""");

    assertThat(started).isEqualByComparingTo("0.00201");
    assertThat(estimator.estimatedUsd()).isEqualByComparingTo("1.002");
  }

  @Test
  void aModelWithoutPriceIsReportedAndNotCounted() {
    CostEstimator estimator = new CostEstimator(ModelPrices.defaults());
    estimator.accept(
        """
        {"type":"assistant","message":{"id":"m1","model":"otro-modelo",\
        "usage":{"input_tokens":1000000,"output_tokens":1000000}},"content":[]}""");

    assertThat(estimator.estimatedUsd()).isZero();
    assertThat(estimator.unpricedModels()).containsExactly("otro-modelo");
  }

  @Test
  void thePriceTableUsesTheLongestPrefixAndCanBeExtended() {
    ModelPrices prices =
        ModelPrices.parse(
            List.of("# propios", "", "mi-modelo = 1, 2, 0.1", "claude-opus-5 = 6,30,1"));

    assertThat(prices.of("claude-opus-5-5[1m]").orElseThrow().input()).isEqualByComparingTo("4");
    assertThat(prices.of("claude-opus-5").orElseThrow().input()).isEqualByComparingTo("6");
    assertThat(prices.of("claude-haiku-4-5-20251001").orElseThrow().output())
        .isEqualByComparingTo("5");
    assertThat(prices.of("mi-modelo-v2").orElseThrow().cacheRead()).isEqualByComparingTo("0.1");
    assertThat(prices.of("gpt")).isEmpty();
    assertThatThrownBy(() -> ModelPrices.parse(List.of("mal = 1, 2")))
        .hasMessageContaining("Línea 1");
  }

  private static CostEstimator estimate(String fixture) {
    CostEstimator estimator = new CostEstimator(ModelPrices.defaults());
    try (InputStream in =
            CostEstimatorTest.class.getResourceAsStream("/claude/" + fixture + ".ndjson");
        BufferedReader reader =
            new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      reader.lines().forEach(estimator::accept);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    return estimator;
  }
}
