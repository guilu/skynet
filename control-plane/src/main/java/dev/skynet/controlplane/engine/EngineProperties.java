package dev.skynet.controlplane.engine;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Workers del motor de workflows.
 *
 * @param workers evaluaciones a la vez ({@code SKYNET_ENGINE_WORKERS})
 * @param pollInterval cada cuánto se buscan trabajos pendientes
 * @param lease tiempo que un worker se queda un trabajo; si el control plane cae, otro lo retoma al
 *     vencer
 * @param retryDelay espera antes de reintentar una evaluación que falló o tuvo que esperar
 */
@ConfigurationProperties("skynet.engine")
record EngineProperties(int workers, Duration pollInterval, Duration lease, Duration retryDelay) {

  EngineProperties {
    workers = workers <= 0 ? 2 : workers;
    pollInterval = pollInterval == null ? Duration.ofSeconds(1) : pollInterval;
    lease = lease == null ? Duration.ofMinutes(2) : lease;
    retryDelay = retryDelay == null ? Duration.ofSeconds(5) : retryDelay;
  }
}
