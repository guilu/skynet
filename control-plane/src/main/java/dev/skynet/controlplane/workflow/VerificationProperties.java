package dev.skynet.controlplane.workflow;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Verificación independiente de los worktrees.
 *
 * @param timeout tiempo máximo del comando de validación; el runner lo mata al agotarse
 */
@ConfigurationProperties("skynet.verification")
record VerificationProperties(Duration timeout) {

  VerificationProperties {
    timeout = timeout == null ? Duration.ofMinutes(30) : timeout;
  }
}
