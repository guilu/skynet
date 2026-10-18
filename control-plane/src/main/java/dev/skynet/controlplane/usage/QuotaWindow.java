package dev.skynet.controlplane.usage;

import dev.skynet.protocol.runner.QuotaSample;
import java.time.Duration;
import java.time.Instant;

/**
 * Una ventana de cuota: cuánto dura y, a partir de su renovación, cuándo empezó y a qué ritmo va.
 */
final class QuotaWindow {

  private QuotaWindow() {}

  static Duration length(String window) {
    return QuotaSample.SEVEN_DAY.equals(window) ? Duration.ofDays(7) : Duration.ofHours(5);
  }

  /** Cuándo empezó la ventana que se renueva en {@code resetsAt}, o {@code null} si no se sabe. */
  static Instant start(String window, Instant resetsAt) {
    return resetsAt == null ? null : resetsAt.minus(length(window));
  }

  /**
   * Ritmo uniforme: la parte que se habría gastado a {@code now} gastando igual toda la ventana, de
   * 0 a 1; {@code null} si no se sabe cuándo se renueva o ya se ha renovado.
   */
  static Double pace(String window, Instant resetsAt, Instant now) {
    if (resetsAt == null || !now.isBefore(resetsAt)) {
      return null;
    }
    double left = Duration.between(now, resetsAt).toMillis() / (double) length(window).toMillis();
    return Math.clamp(1 - left, 0, 1);
  }
}
