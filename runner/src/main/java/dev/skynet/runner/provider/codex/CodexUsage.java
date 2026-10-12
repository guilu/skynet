package dev.skynet.runner.provider.codex;

import dev.skynet.runner.provider.ModelPrices;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * Tokens de un {@code turn.completed} de Codex. En la API de OpenAI la entrada incluye la leída de
 * la caché y la salida incluye el razonamiento.
 */
record CodexUsage(long input, long cachedInput, long output) {

  private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

  static CodexUsage of(JsonNode usage) {
    return new CodexUsage(
        usage.path("input_tokens").asLong(0),
        usage.path("cached_input_tokens").asLong(0),
        usage.path("output_tokens").asLong(0));
  }

  /** Entrada que no viene de la caché. */
  long uncachedInput() {
    return Math.max(0, input - cachedInput);
  }

  /** Coste según la tabla de precios, o vacío si el modelo no tiene precio (o no se conoce). */
  Optional<BigDecimal> cost(ModelPrices prices, String model) {
    return prices
        .of(model)
        .map(
            p ->
                p.input()
                    .multiply(BigDecimal.valueOf(uncachedInput()))
                    .add(p.cacheRead().multiply(BigDecimal.valueOf(cachedInput)))
                    .add(p.output().multiply(BigDecimal.valueOf(output)))
                    .divide(MILLION, MathContext.DECIMAL64));
  }
}
