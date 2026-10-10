package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.definition.InputDefinition;
import dev.skynet.controlplane.shared.InvalidRequestException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Datos de entrada de un lanzamiento, comprobados contra los {@code inputs} del workflow: los que
 * faltan toman su valor por defecto y cada uno tiene que ser de su tipo.
 */
final class LaunchInputs {

  private LaunchInputs() {}

  /**
   * Valores de cada dato, en el orden del YAML; los que no tienen valor ni valor por defecto no
   * aparecen.
   *
   * @throws InvalidRequestException si sobra alguno, falta uno obligatorio o no es de su tipo
   */
  static Map<String, Object> resolve(List<InputDefinition> inputs, Map<String, Object> given) {
    Set<String> names = inputs.stream().map(InputDefinition::name).collect(Collectors.toSet());
    List<String> unknown = given.keySet().stream().filter(k -> !names.contains(k)).toList();
    if (!unknown.isEmpty()) {
      throw new InvalidRequestException(
          (unknown.size() == 1
                  ? "El dato `" + unknown.getFirst() + "` no es"
                  : "Los datos " + list(unknown) + " no son")
              + " de este workflow"
              + (inputs.isEmpty()
                  ? ", que no pide ninguno"
                  : "; admite " + list(inputs.stream().map(InputDefinition::name).toList())));
    }
    Map<String, Object> values = new LinkedHashMap<>();
    for (InputDefinition input : inputs) {
      Object value = given.get(input.name());
      if (value == null || (value instanceof String s && s.isBlank())) {
        value = input.defaultValue();
      }
      if (value == null) {
        if (input.required()) {
          throw new InvalidRequestException("Falta el dato obligatorio `" + input.name() + "`");
        }
        continue;
      }
      values.put(input.name(), typed(input, value));
    }
    return values;
  }

  private static Object typed(InputDefinition input, Object value) {
    return switch (input.type()) {
      case "number" ->
          switch (value) {
            case Number n -> new BigDecimal(n.toString());
            case String s when isNumber(s) -> new BigDecimal(s.strip());
            default -> throw wrongType(input, "un número");
          };
      case "boolean" ->
          switch (value) {
            case Boolean b -> b;
            case String s when s.strip().equals("true") || s.strip().equals("false") ->
                Boolean.parseBoolean(s.strip());
            default -> throw wrongType(input, "true o false");
          };
      default -> {
        if (!(value instanceof String s)) {
          throw wrongType(input, "un texto");
        }
        yield s;
      }
    };
  }

  private static boolean isNumber(String s) {
    try {
      new BigDecimal(s.strip());
      return true;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  private static InvalidRequestException wrongType(InputDefinition input, String expected) {
    return new InvalidRequestException("El dato `" + input.name() + "` tiene que ser " + expected);
  }

  private static String list(List<String> names) {
    return names.stream().map(n -> "`" + n + "`").collect(Collectors.joining(", "));
  }
}
