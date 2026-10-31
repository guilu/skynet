package dev.skynet.controlplane.github;

/** GitHub no respondió o respondió con un error. El mensaje dice cuál, sin tokens. */
public class GitHubException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final int status;

  GitHubException(int status, String message) {
    super(message);
    this.status = status;
  }

  GitHubException(String message, Throwable cause) {
    super(message, cause);
    this.status = 0;
  }

  /** El código HTTP de GitHub, o 0 si no llegó a responder. */
  public int status() {
    return status;
  }
}
