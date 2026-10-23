package dev.skynet.controlplane.memory;

import dev.skynet.controlplane.shared.Archived;
import java.util.Set;
import java.util.UUID;

/**
 * Filtros de una búsqueda dentro de un proyecto.
 *
 * @param scope qué parte del proyecto
 * @param repositoryId el repositorio de {@link Scope#REPOSITORY} y {@link
 *     Scope#REPOSITORY_AND_PROJECT}
 * @param kinds tipos; vacío, todos
 * @param origin origen, o {@code null} para todos
 * @param pinned solo las fijadas ({@code true}), solo las no fijadas ({@code false}) o todas
 * @param text texto a buscar en título, tema, contenido y etiquetas, o {@code null}
 */
public record MemoryQuery(
    Scope scope,
    UUID repositoryId,
    Set<MemoryKind> kinds,
    MemoryOrigin origin,
    Boolean pinned,
    Archived archived,
    String text,
    int page,
    int size) {

  public enum Scope {
    /** Todo el proyecto, con sus repositorios. */
    ALL,
    /** Solo las observaciones de todo el proyecto, sin repositorio. */
    PROJECT,
    /** Solo las de un repositorio. */
    REPOSITORY,
    /** Las de un repositorio y las de todo el proyecto: lo que ve un agente que trabaja en él. */
    REPOSITORY_AND_PROJECT
  }

  public MemoryQuery {
    kinds = kinds == null ? Set.of() : Set.copyOf(kinds);
    archived = archived == null ? Archived.EXCLUDE : archived;
  }
}
