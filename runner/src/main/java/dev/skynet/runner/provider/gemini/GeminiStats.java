package dev.skynet.runner.provider.gemini;

import dev.skynet.runner.provider.ModelPrices;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;

/**
 * Tokens del {@code result.stats} de Gemini CLI: en total y por modelo (con {@code auto}, cada
 * petición puede ir a un modelo distinto). {@code input_tokens} incluye los leídos de la caché
 * ({@code cached}); {@code input} es el resto.
 */
record GeminiStats(Usage total, List<ModelUsage> models) {

  private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

  record Usage(long input, long cached, long output) {
    static Usage of(JsonNode node) {
      long inputTokens = node.path("input_tokens").asLong(0);
      long cached = node.path("cached").asLong(0);
      long input =
          node.has("input") ? node.path("input").asLong(0) : Math.max(0, inputTokens - cached);
      return new Usage(input, cached, node.path("output_tokens").asLong(0));
    }
  }

  record ModelUsage(String model, Usage usage) {}

  static GeminiStats of(JsonNode stats) {
    List<ModelUsage> models = new ArrayList<>();
    JsonNode byModel = stats.path("models");
    if (byModel.isObject()) {
      for (String name : byModel.propertyNames()) {
        models.add(new ModelUsage(name, Usage.of(byModel.get(name))));
      }
    }
    return new GeminiStats(Usage.of(stats), models);
  }

  /** Modelos que respondieron. */
  List<String> modelNames() {
    return models.stream().map(ModelUsage::model).toList();
  }

  /** Modelos sin precio en la tabla. */
  Set<String> unpriced(ModelPrices prices) {
    Set<String> out = new TreeSet<>();
    models.stream().filter(m -> prices.of(m.model()).isEmpty()).forEach(m -> out.add(m.model()));
    return out;
  }

  /** Coste de los modelos con precio (vacío si ninguno lo tiene). */
  Optional<BigDecimal> cost(ModelPrices prices) {
    BigDecimal total = null;
    for (ModelUsage m : models) {
      Optional<ModelPrices.Price> price = prices.of(m.model());
      if (price.isPresent()) {
        ModelPrices.Price p = price.get();
        Usage u = m.usage();
        BigDecimal cost =
            p.input()
                .multiply(BigDecimal.valueOf(u.input()))
                .add(p.cacheRead().multiply(BigDecimal.valueOf(u.cached())))
                .add(p.output().multiply(BigDecimal.valueOf(u.output())))
                .divide(MILLION, MathContext.DECIMAL64);
        total = total == null ? cost : total.add(cost);
      }
    }
    return Optional.ofNullable(total);
  }
}
