package dev.skynet.runner.provider;

import java.util.List;
import java.util.Map;

/**
 * Traduce la salida de una invocación, línea a línea, a eventos normalizados. Hay uno por
 * invocación: puede guardar estado entre líneas (p. ej. el nombre de cada herramienta en curso).
 */
public interface EventParser {

  List<ParsedEvent> parse(String line);

  /**
   * Si ha llegado el resultado final. Una invocación cancelada no lo tiene: su estado final se
   * deduce de la señal y el código de salida.
   */
  boolean sawResult();

  /**
   * Tokens que el CLI ya contó pero que aún no ha llevado ningún evento, como {@code usage} ({@code
   * input}, {@code output}, {@code cacheRead}), o {@code null}. El runner los manda con el fin del
   * proceso para que una invocación cancelada antes del resultado no los pierda; los devuelve una
   * sola vez.
   */
  default Map<String, Object> unreportedUsage() {
    return null;
  }
}
