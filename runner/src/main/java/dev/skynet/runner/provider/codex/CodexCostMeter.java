package dev.skynet.runner.provider.codex;

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
 * Coste de una invocación de Codex: suma el {@code usage} de cada {@code turn.completed} con el
 * precio del modelo. Codex no dice qué modelo usa, así que sin {@code model} en el agente (el
 * predeterminado de su configuración) no hay precio.
 */
final class CodexCostMeter implements CostMeter {

  /** Nombre con el que sale en los modelos sin precio el predeterminado de Codex. */
  static final String DEFAULT_MODEL = "codex (modelo predeterminado)";

  private static final ObjectMapper JSON = JsonMapper.builder().build();

  private final ModelPrices prices;
  private final String model;
  private final Set<String> unpriced = new TreeSet<>();
  private BigDecimal total = BigDecimal.ZERO;

  CodexCostMeter(ModelPrices prices, String model) {
    this.prices = prices;
    this.model = model;
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
    if (!"turn.completed".equals(node.path("type").asString(null))) {
      return;
    }
    CodexUsage usage = CodexUsage.of(node.path("usage"));
    usage
        .cost(prices, model)
        .ifPresentOrElse(
            cost -> total = total.add(cost),
            () -> unpriced.add(model == null ? DEFAULT_MODEL : model));
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
