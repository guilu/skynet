package dev.skynet.runner.transport;

import java.io.IOException;

/** El control plane rechazó un artefacto por algo que no cambiará al reintentar. */
public class ArtifactRejectedException extends IOException {

  private static final long serialVersionUID = 1L;

  public ArtifactRejectedException(String message) {
    super(message);
  }
}
