package dev.skynet.controlplane.github;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

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
   * Desde el JSON de GitHub, igual en un webhook que en la API.
   *
   * @param status el {@code deployment_status}, o {@code null}
   * @param fallback la hora si GitHub no trae {@code created_at}
   */
  static GitHubDeployment of(
      String deliveryId,
      String fullName,
      List<UUID> repositoryIds,
      JsonNode deployment,
      JsonNode status,
      Instant fallback) {
    if (!deployment.path("id").isIntegralNumber() || !deployment.path("sha").isString()) {
      throw new IllegalArgumentException("Falta el id o el SHA del despliegue");
    }
    Status parsed = null;
    if (status != null) {
      if (!status.path("id").isIntegralNumber() || !status.path("state").isString()) {
        throw new IllegalArgumentException("Falta el id o el estado del despliegue");
      }
      parsed =
          new Status(
              status.path("id").asLong(),
              status.path("state").asString(),
              status.path("description").asString(null),
              status.path("environment_url").asString(null),
              instant(status.path("created_at"), fallback));
    }
    return new GitHubDeployment(
        deliveryId,
        fullName,
        repositoryIds,
        deployment.path("id").asLong(),
        deployment.path("sha").asString(),
        deployment.path("ref").asString(null),
        deployment.path("environment").asString(null),
        pullRequest(deployment.path("payload")),
        deployment.path("creator").path("login").asString(null),
        instant(deployment.path("created_at"), fallback),
        parsed);
  }

  /** {@code payload.pull_request} del despliegue; GitHub lo guarda como objeto o como texto. */
  private static Integer pullRequest(JsonNode payload) {
    JsonNode number = payload.path("pull_request");
    if (number.isIntegralNumber() && number.asLong() > 0 && number.asLong() <= Integer.MAX_VALUE) {
      return number.asInt();
    }
    if (number.isString() && number.asString().matches("[1-9][0-9]{0,8}")) {
      return Integer.valueOf(number.asString());
    }
    return null;
  }

  private static Instant instant(JsonNode value, Instant fallback) {
    if (!value.isString()) {
      return fallback;
    }
    try {
      return Instant.parse(value.asString());
    } catch (DateTimeParseException e) {
      return fallback;
    }
  }

  /**
   * @param state el de GitHub: {@code pending}, {@code queued}, {@code in_progress}, {@code
   *     success}, {@code failure}, {@code error} o {@code inactive}
   * @param environmentUrl dónde queda la aplicación; solo se enseña
   */
  public record Status(
      long id, String state, String description, String environmentUrl, Instant createdAt) {}
}
