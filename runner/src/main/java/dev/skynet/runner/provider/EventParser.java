package dev.skynet.runner.provider;

import java.util.List;

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
}
