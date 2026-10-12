package dev.skynet.runner.provider.gemini;

import dev.skynet.runner.provider.CostMeter;
import dev.skynet.runner.provider.ModelPrices;
import java.math.BigDecimal;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Coste de una invocación de Gemini CLI: los tokens solo llegan en el {@code result} final, por
 * modelo, y cada uno se cobra con su precio de la tabla.
 */
final class GeminiCostMeter implements CostMeter {

  private static final ObjectMapper JSON = JsonMapper.builder().build();

  private final ModelPrices prices;
  private final Set<String> unpriced = new TreeSet<>();
  private BigDecimal total = BigDecimal.ZERO;

  GeminiCostMeter(ModelPrices prices) {
    this.prices = prices;
  }

  @Override
  public void accept(String line) {
    if (line == null || line.isBlank()) {
      return;
    }
    JsonNode node;
    try {
      node = JSON.readTree(line);
    } catch (JacksonException e) {
      return;
    }
    if (!"result".equals(node.path("type").asString(null))) {
      return;
    }
    GeminiStats stats = GeminiStats.of(node.path("stats"));
    stats.cost(prices).ifPresent(cost -> total = total.add(cost));
    unpriced.addAll(stats.unpriced(prices));
  }

  @Override
  public BigDecimal estimatedUsd() {
    return total;
  }

  @Override
  public Set<String> unpricedModels() {
    return Set.copyOf(unpriced);
  }
}
