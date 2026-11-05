package dev.skynet.controlplane.github;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Un webhook {@code deployment} o {@code deployment_status} (DL-B) de un repositorio de Skynet: un
 * despliegue de GitHub Deployments y, si es un {@code deployment_status}, su estado.
 *
 * @param repositoryIds los repositorios de Skynet enlazados con el de GitHub
 * @param deploymentId el id del despliegue en GitHub
 * @param pullRequestNumber la PR, si quien desplegó la puso en {@code payload.pull_request}
 * @param status el estado, o {@code null} en un webhook {@code deployment}
 */
public record GitHubDeployment(
    String deliveryId,
    String fullName,
    List<UUID> repositoryIds,
    long deploymentId,
    String sha,
    String ref,
    String environment,
    Integer pullRequestNumber,
    String creator,
    Instant createdAt,
    Status status) {

  public GitHubDeployment {
    repositoryIds = List.copyOf(repositoryIds);
  }

  /**
   * @param state el de GitHub: {@code pending}, {@code queued}, {@code in_progress}, {@code
   *     success}, {@code failure}, {@code error} o {@code inactive}
   * @param environmentUrl dónde queda la aplicación; solo se enseña
   */
  public record Status(
      long id, String state, String description, String environmentUrl, Instant createdAt) {}
}
