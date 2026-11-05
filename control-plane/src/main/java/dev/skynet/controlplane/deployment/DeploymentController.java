package dev.skynet.controlplane.deployment;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Los despliegues (DL): consulta (DL-A), comprobaciones de salud y reconciliación (DL-C). Los
 * despliegues en sí llegan con GitHub Deployments (DL-B), no por esta API.
 */
@RestController
class DeploymentController {

  private final Deployments deployments;
  private final HealthChecks healthChecks;
  private final DeploymentReconciler reconciler;

  DeploymentController(
      Deployments deployments, HealthChecks healthChecks, DeploymentReconciler reconciler) {
    this.deployments = deployments;
    this.healthChecks = healthChecks;
    this.reconciler = reconciler;
  }

  /** Un despliegue con todos sus estados recibidos y los intentos de comprobación de salud. */
  record DeploymentDetail(
      Deployments.Deployment deployment,
      List<Deployments.StatusRecord> statuses,
      List<HealthChecks.Probe> probes) {}

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
    return new DeploymentDetail(
        deployments.get(id), deployments.statuses(id), healthChecks.probes(id));
  }

  /** Vuelve a comprobar ya la salud de lo desplegado. */
  @PostMapping("/api/deployments/{id}/health-check")
  @ResponseStatus(HttpStatus.ACCEPTED)
  Deployments.Deployment recheck(@PathVariable UUID id) {
    return deployments.recheck(id);
  }

  @GetMapping("/api/repositories/{repositoryId}/health-checks")
  List<HealthChecks.HealthCheck> healthChecks(@PathVariable UUID repositoryId) {
    return healthChecks.list(repositoryId);
  }

  @PutMapping("/api/repositories/{repositoryId}/health-checks/{environment}")
  HealthChecks.HealthCheck saveHealthCheck(
      @PathVariable UUID repositoryId,
      @PathVariable String environment,
      @RequestBody HealthChecks.Draft draft) {
    return healthChecks.save(repositoryId, environment, draft);
  }

  @DeleteMapping("/api/repositories/{repositoryId}/health-checks/{environment}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void deleteHealthCheck(@PathVariable UUID repositoryId, @PathVariable String environment) {
    healthChecks.delete(repositoryId, environment);
  }

  /** Pide ya a GitHub los últimos despliegues del repositorio y aplica lo que falte. */
  @PostMapping("/api/repositories/{repositoryId}/deployments/reconcile")
  DeploymentReconciler.Reconciled reconcile(@PathVariable UUID repositoryId) {
    return reconciler.reconcile(repositoryId);
  }
}
