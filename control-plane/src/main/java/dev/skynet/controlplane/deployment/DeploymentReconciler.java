package dev.skynet.controlplane.deployment;

import dev.skynet.controlplane.deployment.Deployments.Status;
import dev.skynet.controlplane.github.GitHubDeployment;
import dev.skynet.controlplane.github.GitHubRepositories;
import dev.skynet.controlplane.shared.ConflictException;
import dev.skynet.controlplane.shared.TimeSource;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * La red de seguridad de los despliegues (DL-C).
 *
 * <ul>
 *   <li><b>Reconciliación</b>: pide a GitHub los últimos despliegues de cada repositorio con
 *       actividad y aplica lo que falte, como si hubiera llegado por webhook. Así se recupera un
 *       evento perdido, por ejemplo mientras Skynet se desplegaba a sí mismo.
 *   <li><b>Atascados</b>: un despliegue sin estado final pasado {@code stuck-after} queda marcado
 *       ({@code stuckAt}); el siguiente estado lo desmarca.
 * </ul>
 */
@Component
class DeploymentReconciler {

  private static final Logger log = LoggerFactory.getLogger(DeploymentReconciler.class);

  /** Cuántos despliegues recientes se piden a GitHub por repositorio. */
  static final int RECENT = 20;

  /** Los repositorios con despliegues en estos días se reconcilian. */
  static final Duration ACTIVE = Duration.ofDays(7);

  /** El resultado de reconciliar un repositorio. */
  record Reconciled(UUID repositoryId, int deployments, int statuses, int changed) {}

  private final JdbcClient jdbc;
  private final Deployments deployments;
  private final DeploymentWebhooks webhooks;
  private final GitHubRepositories github;
  private final TransactionTemplate transactions;
  private final TimeSource time;
  private final Duration stuckAfter;

  DeploymentReconciler(
      JdbcClient jdbc,
      Deployments deployments,
      DeploymentWebhooks webhooks,
      GitHubRepositories github,
      TransactionTemplate transactions,
      TimeSource time,
      @Value("${skynet.deployments.stuck-after:30m}") Duration stuckAfter) {
    this.jdbc = jdbc;
    this.deployments = deployments;
    this.webhooks = webhooks;
    this.github = github;
    this.transactions = transactions;
    this.time = time;
    this.stuckAfter = stuckAfter;
  }

  @Scheduled(
      initialDelayString = "${skynet.deployments.reconcile-initial-delay:2m}",
      fixedDelayString = "${skynet.deployments.reconcile-interval:10m}")
  void reconcileAll() {
    if (!github.configured()) {
      return;
    }
    List<UUID> active =
        jdbc.sql(
                "SELECT repository_id FROM deployment WHERE requested_at > ?"
                    + " UNION SELECT repository_id FROM deployment_health_check")
            .param(Timestamp.from(time.now().minus(ACTIVE)))
            .query(UUID.class)
            .list();
    for (UUID repository : active) {
      try {
        if (github.link(repository).isPresent()) {
          reconcile(repository);
        }
      } catch (RuntimeException e) {
        log.warn("No se pudieron reconciliar los despliegues de {}: {}", repository, e.toString());
      }
    }
  }

  /**
   * Reconcilia un repositorio con GitHub ya.
   *
   * @throws ConflictException si el repositorio no está enlazado con la App
   */
  Reconciled reconcile(UUID repositoryId) {
    var link =
        github
            .link(repositoryId)
            .orElseThrow(
                () ->
                    new ConflictException(
                        "El repositorio no está enlazado con la GitHub App: no se puede reconciliar"));
    List<GitHubDeployment> found =
        github.recentDeployments(
            link, List.of(repositoryId), RECENT, id -> unfinished(repositoryId, id));
    int statuses = 0;
    int changed = 0;
    for (GitHubDeployment deployment : found) {
      if (deployment.status() != null) {
        statuses++;
      }
      Integer before = changes(repositoryId);
      transactions.executeWithoutResult(s -> webhooks.handle(deployment));
      if (!before.equals(changes(repositoryId))) {
        changed++;
      }
    }
    int count = (int) found.stream().filter(d -> d.status() == null).count();
    return new Reconciled(repositoryId, count, statuses, changed);
  }

  /** Si hace falta pedir los estados: no está en Skynet o aún no ha terminado. */
  private boolean unfinished(UUID repositoryId, long githubId) {
    Optional<Status> status =
        deployments.findByExternalId(repositoryId, Long.toString(githubId)).map(d -> d.status());
    return status.isEmpty() || status.get() == Status.PENDING || status.get() == Status.IN_PROGRESS;
  }

  /** Un contador de lo guardado del repositorio, para saber si una entrega añadió algo. */
  private Integer changes(UUID repositoryId) {
    return jdbc.sql(
            "SELECT (SELECT count(*) FROM deployment WHERE repository_id = :r)"
                + " + (SELECT count(*) FROM deployment_status s JOIN deployment d"
                + " ON d.id = s.deployment_id WHERE d.repository_id = :r)")
        .param("r", repositoryId)
        .query(Integer.class)
        .single();
  }

  @Scheduled(fixedDelayString = "${skynet.deployments.stuck-interval:1m}")
  void markStuck() {
    deployments.markStuck(stuckAfter);
  }
}
