package dev.skynet.runner.provider;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Estima el coste de una invocación a partir de su salida, línea a línea, para hacer cumplir su
 * presupuesto en el runner. Hay uno por invocación.
 */
public interface CostMeter {

  void accept(String line);

  /** Coste estimado hasta ahora, en US$. */
  BigDecimal estimatedUsd();

  /** Modelos que han aparecido sin precio en la tabla. */
  Set<String> unpricedModels();
}
