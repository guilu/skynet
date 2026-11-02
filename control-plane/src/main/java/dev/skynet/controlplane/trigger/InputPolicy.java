package dev.skynet.controlplane.trigger;

import dev.skynet.controlplane.shared.InvalidRequestException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Los datos de entrada que puede dar una issue a un workflow (la {@code WORKFLOW_INPUT_POLICY} del
 * puente de {@code guilu/skynet-commands}): solo los que nombra la regla, de su tipo y dentro de
 * sus límites. Después, el lanzamiento los comprueba otra vez contra los {@code inputs} del
 * workflow.
 */
final class InputPolicy {

  /** Lo más largo que admite un texto si la regla no dice otra cosa. */
  static final int DEFAULT_MAX_LENGTH = 1_000;

  static final Set<String> TYPES = Set.of("string", "text", "number", "boolean");

  /** Nombres que parecen meter instrucciones: no se admiten nunca desde una issue. */
  private static final Pattern PROMPT_LIKE_KEY =
      Pattern.compile("(?i)prompt|instruction|command|script|shell|message");

  /**
   * La regla de un dato.
   *
   * @param maxLength para los textos; {@code null}: {@link #DEFAULT_MAX_LENGTH}
   * @param allowedValues si no es {@code null}, solo esos valores
   */
  record Rule(String type, Integer maxLength, List<Object> allowedValues) {}

  private InputPolicy() {}

  /** Comprueba una política al guardarla en una regla. */
  static void validate(Map<String, Rule> policy) {
    policy.forEach(
        (name, rule) -> {
          if (PROMPT_LIKE_KEY.matcher(name).find()) {
            throw new InvalidRequestException(
                "El dato `" + name + "` no se puede dar desde GitHub: parece una instrucción");
          }
          if (rule == null || !TYPES.contains(rule.type())) {
            throw new InvalidRequestException(
                "El dato `" + name + "` necesita un tipo: string, text, number o boolean");
          }
          if (rule.maxLength() != null && (rule.maxLength() < 1 || rule.maxLength() > 20_000)) {
            throw new InvalidRequestException(
                "El largo máximo de `" + name + "` tiene que estar entre 1 y 20000");
          }
        });
  }

  /**
   * Los datos que da la issue, comprobados contra la política.
   *
   * @throws InvalidRequestException si sobra alguno o no cumple su regla
   */
  static Map<String, Object> apply(Map<String, Rule> policy, Map<String, Object> given) {
    if (given.size() > 50) {
      throw new InvalidRequestException("Demasiados datos: como mucho 50");
    }
    Map<String, Object> values = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : given.entrySet()) {
      String name = entry.getKey();
      Rule rule = policy.get(name);
      if (rule == null) {
        throw new InvalidRequestException(
            "El dato `"
                + name
                + "` no se puede dar desde GitHub"
                + (policy.isEmpty()
                    ? ": este disparador no admite ninguno"
                    : "; admite " + String.join(", ", policy.keySet())));
      }
      Object value = entry.getValue();
      boolean ok =
          switch (rule.type()) {
            case "string", "text" -> value instanceof String;
            case "number" ->
                value instanceof Number n
                    && !(n instanceof Double d && (d.isNaN() || d.isInfinite()))
                    && new BigDecimal(n.toString()).abs().compareTo(new BigDecimal("1e12")) <= 0;
            case "boolean" -> value instanceof Boolean;
            default -> false;
          };
      if (!ok) {
        throw new InvalidRequestException(
            "El dato `" + name + "` tiene que ser de tipo " + rule.type());
      }
      if (value instanceof String s) {
        int max = rule.maxLength() == null ? DEFAULT_MAX_LENGTH : rule.maxLength();
        if (s.length() > max) {
          throw new InvalidRequestException(
              "El dato `" + name + "` es demasiado largo: como mucho " + max + " caracteres");
        }
      }
      if (rule.allowedValues() != null && !allowed(rule.allowedValues(), value)) {
        throw new InvalidRequestException("El valor de `" + name + "` no está entre los admitidos");
      }
      values.put(name, value);
    }
    return values;
  }

  private static boolean allowed(List<Object> allowed, Object value) {
    for (Object candidate : allowed) {
      if (candidate instanceof Number a && value instanceof Number b) {
        if (new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0) {
          return true;
        }
      } else if (candidate != null && candidate.equals(value)) {
        return true;
      }
    }
    return false;
  }
}
