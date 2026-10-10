package dev.skynet.controlplane.definition;

import java.math.BigDecimal;
import java.util.List;

/**
 * Un agente con nombre: su prompt, lo que puede usar y cuánto puede gastar. Lo que no indica lo
 * pone la política del repositorio, y lo que indica nunca puede pasar de ella.
 *
 * @param prompt plantilla del prompt, con variables {@code {{...}}}, o {@code null}
 * @param tools herramientas permitidas, o {@code null} para las del repositorio
 * @param permissionMode modo de permisos, o {@code null} para el del repositorio
 */
public record AgentDefinition(
    String name,
    String description,
    String prompt,
    List<String> tools,
    String permissionMode,
    Integer maxTurns,
    BigDecimal maxBudgetUsd,
    Integer timeoutMinutes) {

  public AgentDefinition {
    tools = tools == null ? null : List.copyOf(tools);
  }
}
