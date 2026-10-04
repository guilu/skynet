package dev.skynet.controlplane.runner;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param registrationToken secreto compartido para registrar runners; sin él, el registro está
 *     desactivado
 * @param maxWait espera máxima de un long-poll de órdenes
 * @param redeliverAfter tiempo tras el que una orden entregada y no confirmada se vuelve a entregar
 */
@ConfigurationProperties("skynet.runner")
record RunnerProperties(String registrationToken, Duration maxWait, Duration redeliverAfter) {

  RunnerProperties {
    maxWait = maxWait == null ? Duration.ofSeconds(30) : maxWait;
    redeliverAfter = redeliverAfter == null ? Duration.ofSeconds(30) : redeliverAfter;
  }
}
