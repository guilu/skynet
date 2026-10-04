package dev.skynet.protocol.runner;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Límites de una invocación. Cualquiera puede ser {@code null} si no se aplica.
 *
 * @param maxTurns se pasa al proveedor; solo informativo, porque Claude Code los cuenta de otra
 *     forma
 * @param maxBudgetUsd presupuesto de la invocación; el runner lo hace cumplir por su cuenta
 * @param timeout tiempo máximo de reloj antes de cancelar
 */
public record AgentLimits(Integer maxTurns, BigDecimal maxBudgetUsd, Duration timeout) {

  public static AgentLimits none() {
    return new AgentLimits(null, null, null);
  }
}
