package dev.skynet.controlplane.definition;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Un agente con nombre: su prompt, lo que puede usar y cuánto puede gastar. Lo que no indica lo
 * pone la política del repositorio, y lo que indica nunca puede pasar de ella.
 *
 * @param prompt plantilla del prompt, con variables {@code {{...}}}, o {@code null}
 * @param tools herramientas permitidas, o {@code null} para las del repositorio
 * @param permissionMode modo de permisos, o {@code null} para el del repositorio
 * @param model modelo, o {@code null} para el global ({@code skynet.agent.model})
 * @param provider proveedor; por ahora solo {@code claude-code}
 * @param output JSON Schema (un objeto con {@code properties}) de la salida estructurada que
 *     devuelve el agente, o {@code null}; las condiciones leen sus campos
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
    String provider,
    Map<String, Object> output) {

  /** Proveedor por defecto. */
  public static final String CLAUDE_CODE = "claude-code";

  /** Codex de OpenAI ({@code codex exec --json}). */
  public static final String CODEX = "codex";

  public AgentDefinition {
    tools = tools == null ? null : List.copyOf(tools);
  }

  /** Un agente sin salida estructurada. */
  public AgentDefinition(
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
    this(
        name,
        description,
        prompt,
        tools,
        permissionMode,
        maxTurns,
        maxBudgetUsd,
        timeoutMinutes,
        model,
        provider,
        null);
  }

  /**
   * Los campos de su {@code output} con su tipo JSON ({@code boolean}, {@code string}...), en
   * orden; vacío si no tiene.
   */
  public Map<String, String> outputFields() {
    Map<String, String> fields = new LinkedHashMap<>();
    if (output != null && output.get("properties") instanceof Map<?, ?> properties) {
      properties.forEach(
          (name, schema) ->
              fields.put(
                  name.toString(),
                  schema instanceof Map<?, ?> m && m.get("type") instanceof String type
                      ? type
                      : "object"));
    }
    return fields;
  }
}
