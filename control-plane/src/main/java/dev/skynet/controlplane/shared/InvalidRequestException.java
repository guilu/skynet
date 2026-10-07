package dev.skynet.controlplane.shared;

/**
 * La petición es válida en su forma pero no se admite, por ejemplo porque supera un límite
 * configurado. Se traduce a HTTP 400.
 */
public class InvalidRequestException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public InvalidRequestException(String message) {
    super(message);
  }
}
