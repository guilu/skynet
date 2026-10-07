package dev.skynet.runner.provider.claude;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Estima el coste de una invocación a partir de su salida {@code stream-json}, línea a línea, para
 * que el runner pueda cortarla en cuanto pasa del presupuesto: {@code --max-budget-usd} solo se
 * comprueba al final de cada turno.
 *
 * <p>Suma, por mensaje, los tokens que declaran las líneas {@code assistant} y los eventos {@code
 * message_start} y {@code message_delta}, quedándose con el mayor valor de cada contador (el CLI
 * repite el mensaje y los deltas son acumulados). Los mensajes de un modelo sin precio no cuentan y
 * se anotan en {@link #unpricedModels()}.
 *
 * <p>Tiene estado y no es seguro entre hilos: una instancia por invocación, alimentada desde el
 * hilo que lee su salida.
 */
public final class CostEstimator {

  private static final ObjectMapper JSON = JsonMapper.builder().build();
  private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
  private static final BigDecimal WRITE_5M = new BigDecimal("1.25");
  private static final BigDecimal WRITE_1H = BigDecimal.valueOf(2);

  private final ModelPrices prices;
  private final Map<String, Usage> messages = new HashMap<>();

  /** Mensaje en curso por agente ({@code parent_tool_use_id}), para atribuir sus deltas. */
  private final Map<String, String> current = new HashMap<>();

  private final Set<String> unpriced = new TreeSet<>();
  private BigDecimal total = BigDecimal.ZERO;

  public CostEstimator(ModelPrices prices) {
    this.prices = prices;
  }

  /** Coste estimado hasta ahora, en US$. */
  public BigDecimal estimatedUsd() {
    return total;
  }

  /** Modelos que han aparecido sin precio en la tabla. */
  public Set<String> unpricedModels() {
    return Set.copyOf(unpriced);
  }

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
    String agent = text(node, "parent_tool_use_id");
    String key = agent == null ? "" : agent;
    switch (text(node, "type")) {
      case "assistant" -> {
        JsonNode message = node.path("message");
        record(text(message, "id"), text(message, "model"), message.path("usage"));
      }
      case "stream_event" -> {
        JsonNode event = node.path("event");
        switch (text(event, "type")) {
          case "message_start" -> {
            JsonNode message = event.path("message");
            String id = text(message, "id");
            if (id != null) {
              current.put(key, id);
            }
            record(id, text(message, "model"), message.path("usage"));
          }
          case "message_delta" -> record(current.get(key), null, event.path("usage"));
          case null, default -> {
            // Los demás eventos parciales no traen tokens.
          }
        }
      }
      case null, default -> {
        // El resto de líneas no traen tokens por mensaje.
      }
    }
  }

  private void record(String messageId, String model, JsonNode usage) {
    if (messageId == null || !usage.isObject()) {
      return;
    }
    Usage previous = messages.get(messageId);
    Usage next = Usage.of(model, usage);
    if (previous != null) {
      next = previous.max(next);
    }
    messages.put(messageId, next);
    total = total.subtract(cost(previous)).add(cost(next));
  }

  private BigDecimal cost(Usage usage) {
    if (usage == null || usage.model() == null) {
      return BigDecimal.ZERO;
    }
    ModelPrices.Price price = prices.of(usage.model()).orElse(null);
    if (price == null) {
      unpriced.add(usage.model());
      return BigDecimal.ZERO;
    }
    BigDecimal writes5m =
        BigDecimal.valueOf(Math.max(0, usage.cacheWrite() - usage.cacheWrite1h()))
            .multiply(WRITE_5M);
    BigDecimal writes1h = BigDecimal.valueOf(usage.cacheWrite1h()).multiply(WRITE_1H);
    return price
        .input()
        .multiply(BigDecimal.valueOf(usage.input()).add(writes5m).add(writes1h))
        .add(price.output().multiply(BigDecimal.valueOf(usage.output())))
        .add(price.cacheRead().multiply(BigDecimal.valueOf(usage.cacheRead())))
        .divide(MILLION, MathContext.DECIMAL64);
  }

  /** Tokens de un mensaje; {@code cacheWrite1h} es la parte de la escritura con duración 1 h. */
  private record Usage(
      String model, long input, long output, long cacheRead, long cacheWrite, long cacheWrite1h) {

    static Usage of(String model, JsonNode usage) {
      return new Usage(
          model,
          count(usage, "input_tokens"),
          count(usage, "output_tokens"),
          count(usage, "cache_read_input_tokens"),
          count(usage, "cache_creation_input_tokens"),
          count(usage.path("cache_creation"), "ephemeral_1h_input_tokens"));
    }

    Usage max(Usage other) {
      return new Usage(
          model != null ? model : other.model,
          Math.max(input, other.input),
          Math.max(output, other.output),
          Math.max(cacheRead, other.cacheRead),
          Math.max(cacheWrite, other.cacheWrite),
          Math.max(cacheWrite1h, other.cacheWrite1h));
    }

    private static long count(JsonNode node, String field) {
      JsonNode value = node.get(field);
      return value == null || !value.isNumber() ? 0 : value.asLong();
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asString();
  }
}
