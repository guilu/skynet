package dev.skynet.protocol.runner;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Los repositorios git que el runner encontró en sus carpetas raíz ({@code POST
 * /api/runner/repository-catalog}, IP-A). Es una foto completa: sustituye a la anterior del mismo
 * runner.
 *
 * @param scannedAt cuándo terminó la búsqueda
 * @param roots carpetas raíz en las que buscó ({@code SKYNET_REPO_ROOTS})
 * @param repositories lo que encontró, ordenado por ruta
 * @param truncated si paró al llegar al máximo de repositorios
 */
public record RepositoryCatalog(
    Instant scannedAt,
    List<String> roots,
    List<DiscoveredRepository> repositories,
    boolean truncated) {

  public RepositoryCatalog {
    Objects.requireNonNull(scannedAt, "scannedAt");
    roots = roots == null ? List.of() : List.copyOf(roots);
    repositories = repositories == null ? List.of() : List.copyOf(repositories);
  }
}
