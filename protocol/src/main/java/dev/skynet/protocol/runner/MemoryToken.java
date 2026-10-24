package dev.skynet.protocol.runner;

import java.util.Objects;

/**
 * Token de memoria de una invocación ({@code POST /api/runner/agent-runs/{id}/memory-token}). El
 * runner se lo da al puente MCP de memoria del agente; vale mientras la invocación no termina.
 *
 * @param mode {@code READ} o {@code WRITE}
 */
public record MemoryToken(String token, String mode) {

  public MemoryToken {
    Objects.requireNonNull(token, "token");
    Objects.requireNonNull(mode, "mode");
  }

  /** Para no dejarlo en los logs. */
  @Override
  public String toString() {
    return "MemoryToken[mode=" + mode + "]";
  }
}
