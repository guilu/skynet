package dev.skynet.controlplane.deployment;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consulta de despliegues (DL-A). Solo lectura: los despliegues llegan con GitHub Deployments
 * (DL-B), no por esta API.
 */
@RestController
class DeploymentController {

  private final Deployments deployments;

  DeploymentController(Deployments deployments) {
    this.deployments = deployments;
  }

  /** Un despliegue con todos sus estados recibidos. */
  record DeploymentDetail(
      Deployments.Deployment deployment, List<Deployments.StatusRecord> statuses) {}

  /**
   * Los despliegues del repositorio, del más reciente al más viejo.
   *
   * @param sha el SHA completo o un prefijo de al menos 7 caracteres
   */
  @GetMapping("/api/repositories/{repositoryId}/deployments")
  List<Deployments.Deployment> list(
      @PathVariable UUID repositoryId,
      @RequestParam(required = false) String environment,
      @RequestParam(required = false) String sha,
      @RequestParam(defaultValue = "50") int limit) {
    return deployments.list(repositoryId, environment, sha, limit);
  }

  /** Cada entorno del repositorio: lo desplegado ahora y el último despliegue pedido. */
  @GetMapping("/api/repositories/{repositoryId}/environments")
  List<Deployments.EnvironmentState> environments(@PathVariable UUID repositoryId) {
    return deployments.environments(repositoryId);
  }

  @GetMapping("/api/deployments/{id}")
  DeploymentDetail get(@PathVariable UUID id) {
    return new DeploymentDetail(deployments.get(id), deployments.statuses(id));
  }
}
