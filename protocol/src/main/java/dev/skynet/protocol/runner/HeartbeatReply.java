package dev.skynet.protocol.runner;

/**
 * Respuesta al latido de un runner.
 *
 * @param refreshQuotas si alguien ha pulsado «Actualizar» en la página Uso: el runner consulta las
 *     cuotas de sus CLIs ya, sin esperar a su intervalo, y las manda en el siguiente latido
 * @param refreshRepositories si alguien ha pulsado «Volver a buscar» en el asistente de importación
 *     (IP-A): el runner busca ya los repositorios de sus carpetas raíz y manda el catálogo
 */
public record HeartbeatReply(boolean refreshQuotas, boolean refreshRepositories) {

  /** Respuesta sin búsqueda de repositorios pedida. */
  public HeartbeatReply(boolean refreshQuotas) {
    this(refreshQuotas, false);
  }
}
