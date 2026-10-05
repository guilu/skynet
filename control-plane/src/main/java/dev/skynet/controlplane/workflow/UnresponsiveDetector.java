package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.shared.TimeSource;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Pasa a {@code UNRESPONSIVE} los agentes activos que llevan demasiado tiempo sin actividad. Cada
 * agente se marca en su propia transacción; si a la vez llega un evento suyo, gana el evento y el
 * agente se vuelve a evaluar en la siguiente pasada.
 */
@Component
class UnresponsiveDetector {

  private static final Logger log = LoggerFactory.getLogger(UnresponsiveDetector.class);

  private final RunService runs;
  private final MonitoringProperties properties;
  private final TimeSource time;

  UnresponsiveDetector(RunService runs, MonitoringProperties properties, TimeSource time) {
    this.runs = runs;
    this.properties = properties;
    this.time = time;
  }

  @Scheduled(fixedDelayString = "${skynet.monitoring.check-interval:15s}")
  void check() {
    detect(time.now().minus(properties.unresponsiveAfter()));
  }

  /** Marca los agentes activos sin actividad desde {@code before}; devuelve cuántos. */
  int detect(Instant before) {
    int marked = 0;
    for (UUID agentRunId : runs.silentAgents(before)) {
      try {
        if (runs.markUnresponsive(agentRunId, before)) {
          marked++;
        }
      } catch (OptimisticLockingFailureException e) {
        log.debug("El agente {} ha cambiado mientras se evaluaba", agentRunId);
      }
    }
    return marked;
  }
}
