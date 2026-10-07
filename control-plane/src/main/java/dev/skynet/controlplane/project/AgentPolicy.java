package dev.skynet.controlplane.project;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

/**
 * Política de los agentes que trabajan en un repositorio: qué pueden usar y cuánto pueden gastar.
 *
 * @param allowedTools herramientas permitidas (p. ej. {@code Bash(git:*)}); con {@code dontAsk} el
 *     resto se deniega
 * @param permissionMode modo de permisos de Claude Code
 * @param environment variables adicionales que recibe el agente, siempre dentro de las que permite
 *     el runner ({@code SKYNET_AGENT_ENV}); {@code null} para todas las que permite
 * @param maxTurns turnos máximos de un lanzamiento, o {@code null} sin límite
 * @param maxBudgetUsd presupuesto máximo de una invocación, o {@code null} sin límite
 * @param timeoutMinutes tiempo máximo de una invocación, o {@code null} sin límite
 */
public record AgentPolicy(
    List<String> allowedTools,
    String permissionMode,
    List<String> environment,
    Integer maxTurns,
    BigDecimal maxBudgetUsd,
    Integer timeoutMinutes) {

  /** Modos de permisos admitidos. {@code bypassPermissions} no, porque ignora las herramientas. */
  public static final List<String> PERMISSION_MODES =
      List.of("dontAsk", "acceptEdits", "default", "plan");

  public AgentPolicy {
    allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
    environment = environment == null ? null : List.copyOf(environment);
  }

  /** La política global, la de los repositorios sin política propia. */
  static AgentPolicy of(AgentDefaults defaults) {
    return new AgentPolicy(
        defaults.allowedTools(),
        defaults.permissionMode(),
        null,
        defaults.maxTurns(),
        defaults.maxBudgetUsd(),
        defaults.timeout() == null ? null : Math.toIntExact(defaults.timeout().toMinutes()));
  }

  public Duration timeout() {
    return timeoutMinutes == null ? null : Duration.ofMinutes(timeoutMinutes);
  }
}
