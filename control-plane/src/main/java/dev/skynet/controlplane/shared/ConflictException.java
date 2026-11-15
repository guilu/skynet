package dev.skynet.controlplane.shared;

import java.util.Map;

/**
 * La operación es incompatible con el estado actual: transición no permitida, clave duplicada, etc.
 * Se traduce a HTTP 409.
 */
public class ConflictException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Datos que acompañan a la respuesta 409 (como propiedades del {@code ProblemDetail}). */
  private final transient Map<String, Object> properties;

  public ConflictException(String message) {
    this(message, Map.of());
  }

  public ConflictException(String message, Map<String, Object> properties) {
    super(message);
    this.properties = Map.copyOf(properties);
  }

  public Map<String, Object> properties() {
    return properties;
  }
}
