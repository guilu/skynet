package dev.skynet.controlplane.shared;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

/** Hora actual con la precisión de PostgreSQL (microsegundos), inyectable en tests. */
@Component
public class TimeSource {

  private final Clock clock;

  public TimeSource(Clock clock) {
    this.clock = clock;
  }

  public Instant now() {
    return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
  }
}
