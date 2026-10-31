package dev.skynet.controlplane.github;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * La cola de entregas de webhooks de GitHub ({@code github_webhook_delivery}).
 *
 * @param pollInterval cada cuánto se buscan entregas pendientes
 * @param lease tiempo que un worker se queda una entrega; si el control plane cae, otro la retoma
 * @param retryDelay espera antes del primer reintento de una entrega que falló; se dobla en cada
 *     intento
 * @param maxAttempts intentos tras los que una entrega queda {@code FAILED}
 * @param retention tiempo que se guardan las entregas
 * @param redeliveryInterval cada cuánto se piden a GitHub las entregas que no llegaron
 * @param maxPayload lo más que ocupa una entrega (GitHub no manda más de 25 MB)
 */
@ConfigurationProperties("skynet.github.webhooks")
record WebhookProperties(
    Duration pollInterval,
    Duration lease,
    Duration retryDelay,
    int maxAttempts,
    Duration retention,
    Duration redeliveryInterval,
    DataSize maxPayload) {

  WebhookProperties {
    pollInterval = pollInterval == null ? Duration.ofSeconds(1) : pollInterval;
    lease = lease == null ? Duration.ofMinutes(2) : lease;
    retryDelay = retryDelay == null ? Duration.ofSeconds(5) : retryDelay;
    maxAttempts = maxAttempts <= 0 ? 5 : maxAttempts;
    retention = retention == null ? Duration.ofDays(30) : retention;
    redeliveryInterval = redeliveryInterval == null ? Duration.ofMinutes(10) : redeliveryInterval;
    maxPayload = maxPayload == null ? DataSize.ofMegabytes(25) : maxPayload;
  }
}
