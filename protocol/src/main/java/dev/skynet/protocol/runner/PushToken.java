package dev.skynet.protocol.runner;

import java.util.Objects;

/**
 * Token de instalación de GitHub para el push de una fase {@code github-pr} ({@code POST
 * /api/runner/agent-runs/{id}/push-token}): limitado al repositorio y a {@code contents: write}, y
 * de corta vida. El runner lo usa una vez y lo olvida.
 */
public record PushToken(String token) {

  public PushToken {
    Objects.requireNonNull(token, "token");
  }

  /** Para no dejarlo en los logs. */
  @Override
  public String toString() {
    return "PushToken[…]";
  }
}
