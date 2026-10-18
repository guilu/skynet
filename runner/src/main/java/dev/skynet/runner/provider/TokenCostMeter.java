package dev.skynet.runner.provider;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;

/**
 * Coste de los proveedores que no lo informan: se estima con la tabla de precios a partir de los
 * eventos de un tipo que llevan los tokens.
 */
public abstract class TokenCostMeter implements CostMeter {

  private final String field;
  private final String type;
  private final Set<String> unpriced = new TreeSet<>();
  private BigDecimal total = BigDecimal.ZERO;

  /**
   * @param type el {@code type} de los eventos que llevan los tokens
   */
  protected TokenCostMeter(String type) {
    this("type", type);
  }

  /**
   * @param field el campo que dice el tipo de cada evento ({@code type}; {@code event} en agy)
   * @param type el valor de ese campo en los eventos que llevan los tokens
   */
  protected TokenCostMeter(String field, String type) {
    this.field = field;
    this.type = type;
  }

  /** Suma el coste de un evento del tipo con los tokens. */
  protected abstract void count(JsonNode event);

  protected final void add(BigDecimal cost) {
    total = total.add(cost);
  }

  protected final void unpriced(Collection<String> models) {
    unpriced.addAll(models);
  }

  @Override
  public final void accept(String line) {
    if (line == null || line.isBlank()) {
      return;
    }
    JsonNode node = EventJson.object(line);
    if (node != null && type.equals(node.path(field).asString(null))) {
      count(node);
    }
  }

  @Override
  public final BigDecimal estimatedUsd() {
    return total;
  }

  @Override
  public final Set<String> unpricedModels() {
    return Set.copyOf(unpriced);
  }
}
