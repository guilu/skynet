package dev.skynet.controlplane.shared;

/**
 * La operación es incompatible con el estado actual: transición no permitida, clave duplicada, etc.
 * Se traduce a HTTP 409.
 */
public class ConflictException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public ConflictException(String message) {
    super(message);
  }
}
