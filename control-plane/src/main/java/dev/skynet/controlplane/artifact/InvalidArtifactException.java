package dev.skynet.controlplane.artifact;

/** El artefacto subido no es válido (p. ej. su sha256 no coincide). Se responde 400. */
public class InvalidArtifactException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public InvalidArtifactException(String message) {
    super(message);
  }
}
