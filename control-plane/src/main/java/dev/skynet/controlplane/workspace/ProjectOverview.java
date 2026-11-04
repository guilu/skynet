package dev.skynet.controlplane.workspace;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Centro de operaciones de un proyecto (FS-B): cifras clave, sus repositorios y sus trabajos.
 * {@code costUsd} y el de cada repositorio cuentan las ejecuciones creadas desde {@code since}.
 */
public record ProjectOverview(
    UUID projectId,
    Instant since,
    long openWorkItems,
    long activeRuns,
    long openPullRequests,
    long mergedPullRequests,
    long pendingApprovals,
    BigDecimal costUsd,
    List<RepositoryOverview> repositories,
    List<WorkItemSummary> workItems) {

  /**
   * Un repositorio del proyecto: trabajos abiertos en él, ejecuciones en marcha, PRs abiertas y
   * coste desde {@code since}.
   */
  public record RepositoryOverview(
      UUID id,
      String name,
      String defaultBranch,
      long openWorkItems,
      long activeRuns,
      long openPullRequests,
      BigDecimal costUsd) {}
}
