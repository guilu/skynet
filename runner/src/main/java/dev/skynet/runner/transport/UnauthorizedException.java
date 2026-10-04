package dev.skynet.runner.transport;

import java.io.IOException;

/** El control plane ha rechazado el token (HTTP 401). */
public class UnauthorizedException extends IOException {

  private static final long serialVersionUID = 1L;

  public UnauthorizedException(String message) {
    super(message);
  }
}
