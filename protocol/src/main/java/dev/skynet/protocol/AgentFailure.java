package dev.skynet.protocol;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Por qué falló una invocación (W6): decide si la fase se reintenta ({@code retry.on} en el YAML).
 * El runner manda la categoría al terminar el proceso; el control plane la deduce para lo que ve él
 * (una invocación perdida, una salida que no cumple el contrato).
 */
public enum AgentFailure {
  /** El runner se cayó o perdió la invocación. */
  LOST,
  /** El agente no llegó a arrancar: no se pudo preparar el worktree o lanzar el proceso. */
  START,
  /** El proceso terminó con error: código distinto de 0, resultado con error del proveedor. */
  ERROR,
  /** Se agotó el tiempo máximo del agente o de la fase. */
  TIMEOUT,
  /** Superó el presupuesto o el número de turnos. */
  BUDGET,
  /** La salida estructurada o las comprobaciones de la fase no pasaron (W4). */
  VERIFICATION;

  /** El nombre en el YAML y en el protocolo: {@code lost}, {@code start}… */
  public String id() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static Optional<AgentFailure> fromId(String id) {
    return Arrays.stream(values()).filter(f -> f.id().equals(id)).findFirst();
  }
}
