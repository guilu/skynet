package dev.skynet.controlplane.memory;

import java.util.List;

/**
 * Lo que se guarda de una observación, con la estructura de engram.
 *
 * @param topicKey tema estable (p. ej. {@code arquitectura/auth}); sin él, cada guardado es una
 *     observación nueva
 * @param what qué se decidió, se arregló o se descubrió
 * @param why por qué
 * @param where dónde (ficheros, módulos, comandos)
 * @param learned qué conviene recordar
 */
public record MemoryContent(
    MemoryKind kind,
    String title,
    String topicKey,
    String what,
    String why,
    String where,
    String learned,
    List<String> tags) {

  public MemoryContent {
    tags = tags == null ? List.of() : List.copyOf(tags);
  }
}
