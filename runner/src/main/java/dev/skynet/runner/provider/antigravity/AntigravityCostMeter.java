package dev.skynet.runner.provider.antigravity;

import dev.skynet.runner.provider.ModelPrices;
import dev.skynet.runner.provider.TokenCostMeter;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Coste de una invocación de agy: suma el {@code usage} de cada paso del agente que termina, según
 * llegan (el del {@code result} es su total), con el precio del modelo. agy no dice qué modelo usa,
 * así que sin {@code model} en el agente (el predeterminado de la cuenta) no hay precio.
 */
final class AntigravityCostMeter extends TokenCostMeter {

  /** Nombre con el que sale en los modelos sin precio el predeterminado de agy. */
  static final String DEFAULT_MODEL = "agy (modelo predeterminado)";

  private final ModelPrices prices;
  private final String model;

  AntigravityCostMeter(ModelPrices prices, String model) {
    super("event", "step_update");
    this.prices = prices;
    this.model = model;
  }

  @Override
  protected void count(JsonNode event) {
    JsonNode step = event.path("step_update");
    if (!"DONE".equals(step.path("state").asString(null)) || !step.path("usage").isObject()) {
      return;
    }
    AntigravityUsage.of(step.path("usage"))
        .cost(prices, model)
        .ifPresentOrElse(this::add, () -> unpriced(List.of(model == null ? DEFAULT_MODEL : model)));
  }
}
