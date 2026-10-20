package dev.skynet.runner.provider.antigravity;

import dev.skynet.runner.provider.ModelPrices;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * Tokens de un {@code usage} de agy (de un paso o del {@code result}). {@code input_tokens} no
 * incluye los leídos de caché ({@code cache_read_tokens}) y el razonamiento ({@code
 * thinking_tokens}) se cobra como salida.
 */
record AntigravityUsage(long input, long output, long thinking, long cacheRead) {

  private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

  static AntigravityUsage of(JsonNode usage) {
    return new AntigravityUsage(
        usage.path("input_tokens").asLong(0),
        usage.path("output_tokens").asLong(0),
        usage.path("thinking_tokens").asLong(0),
        usage.path("cache_read_tokens").asLong(0));
  }

  /** La suma con {@code other}, que puede ser {@code null}. */
  AntigravityUsage plus(AntigravityUsage other) {
    if (other == null) {
      return this;
    }
    return new AntigravityUsage(
        input + other.input,
        output + other.output,
        thinking + other.thinking,
        cacheRead + other.cacheRead);
  }

  /** Salida que se cobra: la respuesta y el razonamiento. */
  long billedOutput() {
    return output + thinking;
  }

  /**
   * Coste con el precio de {@code model}; vacío sin modelo (agy no dice cuál usa por defecto) o si
   * no está en la tabla.
   */
  Optional<BigDecimal> cost(ModelPrices prices, String model) {
    if (model == null) {
      return Optional.empty();
    }
    return prices
        .of(model)
        .map(
            p ->
                p.input()
                    .multiply(BigDecimal.valueOf(input))
                    .add(p.cacheRead().multiply(BigDecimal.valueOf(cacheRead)))
                    .add(p.output().multiply(BigDecimal.valueOf(billedOutput())))
                    .divide(MILLION, MathContext.DECIMAL64));
  }
}
