package dev.skynet.protocol.runner;

/**
 * Respuesta al latido de un runner.
 *
 * @param refreshQuotas si alguien ha pulsado «Actualizar» en la página Uso: el runner consulta las
 *     cuotas de sus CLIs ya, sin esperar a su intervalo, y las manda en el siguiente latido
 */
public record HeartbeatReply(boolean refreshQuotas) {}
