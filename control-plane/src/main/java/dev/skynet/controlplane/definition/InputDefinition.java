package dev.skynet.controlplane.definition;

/**
 * Un dato que se pide al lanzar el workflow y que los prompts usan como {@code {{inputs.nombre}}}.
 *
 * @param type {@code string} (una línea), {@code text} (varias), {@code number} o {@code boolean}
 * @param defaultValue valor por defecto, ya del tipo indicado, o {@code null}
 */
public record InputDefinition(
    String name, String type, boolean required, Object defaultValue, String description) {

  static final java.util.List<String> TYPES =
      java.util.List.of("string", "text", "number", "boolean");
}
