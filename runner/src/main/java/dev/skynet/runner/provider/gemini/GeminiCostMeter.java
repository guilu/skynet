package dev.skynet.runner.provider.gemini;

import dev.skynet.runner.provider.ModelPrices;
import dev.skynet.runner.provider.TokenCostMeter;
import tools.jackson.databind.JsonNode;

/**
 * Coste de una invocación de Gemini CLI: los tokens solo llegan en el {@code result} final, por
 * modelo, y cada uno se cobra con su precio de la tabla.
 */
final class GeminiCostMeter extends TokenCostMeter {

  private final ModelPrices prices;

  GeminiCostMeter(ModelPrices prices) {
    super("result");
    this.prices = prices;
  }

  @Override
  protected void count(JsonNode event) {
    GeminiStats stats = GeminiStats.of(event.path("stats"));
    stats.cost(prices).ifPresent(this::add);
    unpriced(stats.unpriced(prices));
  }
}
