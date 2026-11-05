package dev.skynet.controlplane.deployment;

import dev.skynet.controlplane.deployment.Deployments.NewDeployment;
import dev.skynet.controlplane.deployment.Deployments.Status;
import dev.skynet.controlplane.deployment.Deployments.StatusUpdate;
import dev.skynet.controlplane.github.DeploymentHandler;
import dev.skynet.controlplane.github.GitHubDeployment;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Los despliegues que llegan por GitHub Deployments (DL-B): cada webhook {@code deployment} o
 * {@code deployment_status} registra el despliegue en cada repositorio de Skynet enlazado y, si
 * trae estado, lo aplica. Un {@code deployment_status} basta aunque el {@code deployment} no haya
 * llegado o llegue después.
 */
@Component
class DeploymentWebhooks implements DeploymentHandler {

  /** El entorno de un despliegue sin entorno; GitHub pone {@code production} por defecto. */
  static final String DEFAULT_ENVIRONMENT = "production";

  private final Deployments deployments;

  DeploymentWebhooks(Deployments deployments) {
    this.deployments = deployments;
  }

  @Override
  public void handle(GitHubDeployment deployment) {
    String environment = environment(deployment.environment());
    deployments.lockEnvironments(deployment.repositoryIds(), environment);
    for (UUID repository : deployment.repositoryIds()) {
      Deployments.Deployment registered =
          deployments.register(
              new NewDeployment(
                  repository,
                  environment,
                  Long.toString(deployment.deploymentId()),
                  deployment.sha(),
                  deployment.ref(),
                  deployment.pullRequestNumber(),
                  null,
                  deployment.creator(),
                  deployment.createdAt()));
      GitHubDeployment.Status status = deployment.status();
      Status state = status == null ? null : state(status.state());
      if (state != null) {
        deployments.record(
            registered.id(),
            new StatusUpdate(
                Long.toString(status.id()),
                state,
                status.description(),
                status.createdAt(),
                status.environmentUrl()));
      }
    }
  }

  /**
   * El estado de GitHub en Skynet. {@code inactive} no cuenta: GitHub lo pone a los despliegues
   * anteriores al terminar uno nuevo, y eso ya lo hace {@link Status#SUPERSEDED}.
   */
  static Status state(String state) {
    return switch (state.toLowerCase(Locale.ROOT)) {
      case "pending", "queued" -> Status.PENDING;
      case "in_progress" -> Status.IN_PROGRESS;
      case "success" -> Status.SUCCEEDED;
      case "failure", "error" -> Status.FAILED;
      default -> null;
    };
  }

  /** Un entorno válido en Skynet: GitHub admite espacios y otros caracteres. */
  static String environment(String name) {
    if (name == null || name.isBlank()) {
      return DEFAULT_ENVIRONMENT;
    }
    String clean =
        name.strip().replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^[^A-Za-z0-9]+", "");
    if (clean.isEmpty()) {
      return DEFAULT_ENVIRONMENT;
    }
    return clean.length() > 64 ? clean.substring(0, 64) : clean;
  }
}
