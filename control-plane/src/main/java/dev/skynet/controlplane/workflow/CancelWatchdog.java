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
 * Cierra las cancelaciones que el runner no confirma (W6-B): un agente con la cancelación pedida
 * hace más de {@code skynet.monitoring.cancel-timeout} se da por terminado, para que la fase y la
 * ejecución terminen aunque el runner esté caído. Cada agente se cierra en su propia transacción.
 */
@Component
class CancelWatchdog {

  private static final Logger log = LoggerFactory.getLogger(CancelWatchdog.class);

  private final RunService runs;
  private final MonitoringProperties properties;
  private final TimeSource time;

  CancelWatchdog(RunService runs, MonitoringProperties properties, TimeSource time) {
    this.runs = runs;
    this.properties = properties;
    this.time = time;
  }

  @Scheduled(fixedDelayString = "${skynet.monitoring.check-interval:15s}")
  void check() {
    force(time.now().minus(properties.cancelTimeout()));
  }

  /** Cierra los agentes con la cancelación pedida antes de {@code before}; devuelve cuántos. */
  int force(Instant before) {
    int forced = 0;
    for (UUID agentRunId : runs.unconfirmedCancels(before)) {
      try {
        if (runs.forceCancel(agentRunId)) {
          log.warn(
              "El runner no confirmó la cancelación del agente {}: se da por terminado",
              agentRunId);
          forced++;
        }
      } catch (OptimisticLockingFailureException e) {
        log.debug("El agente {} ha cambiado mientras se cerraba", agentRunId);
      }
    }
    return forced;
  }
}
