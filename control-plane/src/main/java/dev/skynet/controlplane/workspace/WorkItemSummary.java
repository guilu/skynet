package dev.skynet.controlplane.workspace;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Un trabajo visto desde la fábrica (FS-B): su repositorio, su fase, cuántos intentos lleva, lo que
 * han costado todos juntos, el último intento con su workflow y la PR que ha producido.
 *
 * @param attempts ejecuciones no archivadas del trabajo
 * @param costUsd suma del coste de todos sus agentes; null si ninguno lo ha informado
 * @param lastActivityAt cuándo empezó el último intento o, sin intentos, cuándo se creó
 */
public record WorkItemSummary(
    UUID id,
    UUID projectId,
    String projectKey,
    String projectName,
    String key,
    String title,
    String type,
    String status,
    UUID repositoryId,
    String repositoryName,
    WorkPhase phase,
    long attempts,
    BigDecimal costUsd,
    UUID lastRunId,
    String lastRunStatus,
    String workflowKey,
    PullRequestRef pullRequest,
    Instant createdAt,
    Instant lastActivityAt) {

  /** La PR del trabajo: la abierta o, si no hay, la última fusionada. */
  public record PullRequestRef(Integer number, String url, String status) {}
}
