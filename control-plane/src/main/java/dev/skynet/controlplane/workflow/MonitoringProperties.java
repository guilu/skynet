package dev.skynet.controlplane.workflow;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Vigilancia de los agentes en ejecución.
 *
 * @param unresponsiveAfter tiempo sin actividad tras el que un agente activo pasa a {@code
 *     UNRESPONSIVE}. Una herramienta larga (un build, unos tests) no emite eventos mientras dura,
 *     así que el umbral debe cubrirla
 * @param cancelTimeout tiempo que se espera a que el runner confirme una cancelación antes de dar
 *     el agente por terminado (W6-B): la gracia del runner antes de SIGKILL (10 s) y un margen
 */
@ConfigurationProperties("skynet.monitoring")
record MonitoringProperties(Duration unresponsiveAfter, Duration cancelTimeout) {

  MonitoringProperties {
    unresponsiveAfter = unresponsiveAfter == null ? Duration.ofMinutes(5) : unresponsiveAfter;
    cancelTimeout = cancelTimeout == null ? Duration.ofSeconds(70) : cancelTimeout;
  }
}
