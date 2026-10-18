package dev.skynet.controlplane.usage;

import dev.skynet.controlplane.shared.InvalidRequestException;
import java.time.Duration;

/** Los periodos de la página Uso: 24 h, 7 días o 30 días. */
final class UsagePeriod {

  private UsagePeriod() {}

  static Duration parse(String period) {
    return switch (period) {
      case "24h" -> Duration.ofHours(24);
      case "7d" -> Duration.ofDays(7);
      case "30d" -> Duration.ofDays(30);
      default ->
          throw new InvalidRequestException("Periodo no válido: " + period + " (24h, 7d o 30d)");
    };
  }
}
