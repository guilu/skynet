package dev.skynet.runner.provider.codex;

import dev.skynet.runner.provider.ModelPrices;
import dev.skynet.runner.provider.TokenCostMeter;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Coste de una invocación de Codex: suma el {@code usage} de cada {@code turn.completed} con el
 * precio del modelo. Codex no dice qué modelo usa, así que sin {@code model} en el agente (el
 * predeterminado de su configuración) no hay precio.
 */
final class CodexCostMeter extends TokenCostMeter {

  /** Nombre con el que sale en los modelos sin precio el predeterminado de Codex. */
  static final String DEFAULT_MODEL = "codex (modelo predeterminado)";

  private final ModelPrices prices;
  private final String model;

  CodexCostMeter(ModelPrices prices, String model) {
    super("turn.completed");
    this.prices = prices;
    this.model = model;
  }

  @Override
  protected void count(JsonNode event) {
    CodexUsage.of(event.path("usage"))
        .cost(prices, model)
        .ifPresentOrElse(this::add, () -> unpriced(List.of(model == null ? DEFAULT_MODEL : model)));
  }
}
