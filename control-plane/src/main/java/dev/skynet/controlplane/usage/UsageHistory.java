package dev.skynet.controlplane.usage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * La evolución de la ventana actual de un CLI ({@code GET /api/usage/history}).
 *
 * @param windowStart cuándo empezó la ventana (su renovación menos su duración); sin renovación
 *     conocida, hace una ventana
 * @param resetsAt cuándo se renueva, o {@code null} si el CLI no lo dice
 * @param points las muestras de la ventana, de la más antigua a la más reciente
 */
public record UsageHistory(
    UUID runnerId,
    String provider,
    String bucket,
    String window,
    Instant windowStart,
    Instant resetsAt,
    List<Point> points) {

  /**
   * Una muestra.
   *
   * @param usedFraction parte gastada de la ventana, de 0 a 1
   * @param skynet de lo gastado, lo que se atribuye a agentes de Skynet desde que empezó la
   *     ventana; el resto es de fuera
   */
  public record Point(Instant at, double usedFraction, double skynet, String source) {}
}
