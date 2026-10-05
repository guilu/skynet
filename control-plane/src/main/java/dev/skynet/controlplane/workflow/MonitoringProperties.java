package dev.skynet.controlplane.workflow;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Vigilancia de los agentes en ejecución.
 *
 * @param unresponsiveAfter tiempo sin actividad tras el que un agente activo pasa a {@code
 *     UNRESPONSIVE}. Una herramienta larga (un build, unos tests) no emite eventos mientras dura,
 *     así que el umbral debe cubrirla
 */
@ConfigurationProperties("skynet.monitoring")
record MonitoringProperties(Duration unresponsiveAfter) {

  MonitoringProperties {
    unresponsiveAfter = unresponsiveAfter == null ? Duration.ofMinutes(5) : unresponsiveAfter;
  }
}
