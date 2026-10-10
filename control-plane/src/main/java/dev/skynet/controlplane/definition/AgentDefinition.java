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
 * @param model modelo, o {@code null} para el global ({@code skynet.agent.model})
 * @param provider proveedor; por ahora solo {@code claude-code}
 */
public record AgentDefinition(
    String name,
    String description,
    String prompt,
    List<String> tools,
    String permissionMode,
    Integer maxTurns,
    BigDecimal maxBudgetUsd,
    Integer timeoutMinutes,
    String model,
    String provider) {

  /** Proveedor por defecto, y por ahora el único que se ejecuta. */
  public static final String CLAUDE_CODE = "claude-code";

  public AgentDefinition {
    tools = tools == null ? null : List.copyOf(tools);
  }
}
