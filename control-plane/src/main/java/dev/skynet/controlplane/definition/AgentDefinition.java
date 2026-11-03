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
 * @param onQuota qué hace si su cuenta se queda sin cuota (CQ-A): {@link #ON_QUOTA_WAIT} espera a
 *     que se renueve, {@link #ON_QUOTA_FAIL} falla y {@link #ON_QUOTA_FALLBACK} (CQ-B) cambia a
 *     {@code fallback} si la espera es larga. Con {@code null}, {@code fallback} si tiene reserva y
 *     {@code wait} si no
 * @param fallback proveedor de reserva (CQ-B), o {@code null}
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
    Map<String, Object> output,
    String onQuota,
    Fallback fallback) {

  /** Esperar a que se renueve la cuota (CQ-A): lo que se hace sin reserva si no se dice nada. */
  public static final String ON_QUOTA_WAIT = "wait";

  /** Fallar en cuanto se acaba la cuota, como antes de CQ. */
  public static final String ON_QUOTA_FAIL = "fail";

  /**
   * Cambiar al proveedor de reserva si la cuota tarda en renovarse (CQ-B): lo que se hace con
   * reserva si no se dice nada.
   */
  public static final String ON_QUOTA_FALLBACK = "fallback";

  /**
   * El proveedor de reserva de un agente (CQ-B): quién sigue su trabajo si su cuenta se queda sin
   * cuota para un rato.
   *
   * @param provider proveedor, distinto del del agente
   * @param model modelo, o {@code null} para el predeterminado del proveedor
   */
  public record Fallback(String provider, String model) {}

  /** Proveedor por defecto. */
  public static final String CLAUDE_CODE = "claude-code";

  /** Codex de OpenAI ({@code codex exec --json}). */
  public static final String CODEX = "codex";

  /** Antigravity CLI de Google ({@code agy --output-format stream-json}). */
  public static final String ANTIGRAVITY = "antigravity";

  /** Gemini CLI de Google, retirado en AR-B: publicar con él es un error. */
  public static final String GEMINI = "gemini";

  public AgentDefinition {
    tools = tools == null ? null : List.copyOf(tools);
  }

  /** Un agente sin proveedor de reserva. */
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
      String provider,
      Map<String, Object> output,
      String onQuota) {
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
        output,
        onQuota,
        null);
  }

  /** Un agente con salida estructurada que espera a la cuota. */
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
      String provider,
      Map<String, Object> output) {
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
        output,
        null,
        null);
  }

  /** Si espera a que se renueve la cuota (o cambia de proveedor) en vez de fallar (CQ-A). */
  public boolean waitsForQuota() {
    return !ON_QUOTA_FAIL.equals(onQuota);
  }

  /**
   * Si cambia a su proveedor de reserva cuando la cuota tarda en renovarse (CQ-B): tiene reserva y
   * no dice {@code wait} ni {@code fail}.
   */
  public boolean fallsBack() {
    return fallback != null && (onQuota == null || ON_QUOTA_FALLBACK.equals(onQuota));
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
    return fieldsOf(output);
  }

  /**
   * Los campos de un JSON Schema de salida ({@code output} de un agente u {@code outputSchema} de
   * una fase) con su tipo JSON, en orden; vacío si es {@code null}.
   */
  public static Map<String, String> fieldsOf(Map<String, Object> schema) {
    Map<String, String> fields = new LinkedHashMap<>();
    if (schema != null && schema.get("properties") instanceof Map<?, ?> properties) {
      properties.forEach(
          (name, field) ->
              fields.put(
                  name.toString(),
                  field instanceof Map<?, ?> m && m.get("type") instanceof String type
                      ? type
                      : "object"));
    }
    return fields;
  }
}
