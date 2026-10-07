package dev.skynet.controlplane.project;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Política global de los agentes: la de los repositorios sin política propia y el punto de partida
 * de la suya. El modelo solo se configura aquí.
 *
 * @param allowedTools herramientas permitidas; con {@code dontAsk} el resto se deniega
 * @param maxBudgetUsd presupuesto por invocación, que el runner hace cumplir
 */
@ConfigurationProperties("skynet.agent")
public record AgentDefaults(
    List<String> allowedTools,
    String permissionMode,
    String model,
    Integer maxTurns,
    BigDecimal maxBudgetUsd,
    Duration timeout) {

  public AgentDefaults {
    allowedTools =
        allowedTools == null ? List.of("Read", "Glob", "Grep", "Edit", "Write") : allowedTools;
    permissionMode = permissionMode == null ? "dontAsk" : permissionMode;
    maxBudgetUsd = maxBudgetUsd == null ? new BigDecimal("2.00") : maxBudgetUsd;
    timeout = timeout == null ? Duration.ofMinutes(30) : timeout;
  }
}
