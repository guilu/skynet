package dev.skynet.controlplane.artifact;

/** La subida supera {@code skynet.artifacts.max-upload}. Se responde 413. */
public class ArtifactTooLargeException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public ArtifactTooLargeException(String message) {
    super(message);
  }
}
