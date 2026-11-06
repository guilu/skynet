package dev.skynet.controlplane.trigger;

import dev.skynet.controlplane.deployment.Deployments;
import dev.skynet.controlplane.deployment.Deployments.Deployment;
import dev.skynet.controlplane.deployment.Deployments.Health;
import dev.skynet.controlplane.event.EventDraft;
import dev.skynet.controlplane.event.EventStore;
import dev.skynet.controlplane.project.CodeRepository;
import dev.skynet.controlplane.project.ProjectService;
import dev.skynet.controlplane.shared.ConflictException;
import dev.skynet.controlplane.shared.InvalidRequestException;
import dev.skynet.controlplane.shared.NotFoundException;
import dev.skynet.controlplane.shared.TimeSource;
import dev.skynet.controlplane.workflow.RunService;
import dev.skynet.controlplane.workflow.WorkflowDefinitionView;
import dev.skynet.controlplane.workflow.WorkflowRun;
import dev.skynet.controlplane.workitem.WorkItemService;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Los disparadores por despliegue (DL-D): cada regla {@code DEPLOYMENT} lanza su workflow cuando lo
 * desplegado en su entorno queda sano o falla la salud, siempre sobre el mismo trabajo.
 *
 * <ul>
 *   <li>Cada despliegue dispara cada regla una sola vez: la fila de {@code trigger_deployment_fire}
 *       (con su clave primaria) se escribe en la misma transacción que el lanzamiento, así que ni
 *       una pasada repetida ni otra instancia lo repiten.
 *   <li>Solo mira lo desplegado ahora ({@code SUCCEEDED}): un SHA ya sustituido no dispara nada.
 *   <li>Solo mira las comprobaciones terminadas después de crear o cambiar la regla, y como mucho
 *       del último día: encender una regla no lanza por lo viejo.
 *   <li>Respeta el interruptor y el freno diario de los disparadores, y la cola del repositorio. Si
 *       la ejecución anterior de la regla sigue en marcha, se salta.
 * </ul>
 *
 * <p>No cambia nada de la política de aprobación y fusión ni del despliegue de Hermes: solo lanza
 * un workflow después, si alguien lo ha configurado.
 */
@Service
class TriggerDeployments {

  private static final Logger log = LoggerFactory.getLogger(TriggerDeployments.class);

  /** Hasta dónde mira atrás, aunque la regla sea más vieja. */
  static final Duration LOOKBACK = Duration.ofDays(1);

  private final JdbcClient jdbc;
  private final TimeSource time;
  private final TransactionTemplate transactions;
  private final TriggerSettings settings;
  private final TriggerRules rules;
  private final Deployments deployments;
  private final ProjectService projects;
  private final WorkItemService workItems;
  private final RunService runs;
  private final EventStore events;

  TriggerDeployments(
      JdbcClient jdbc,
      TimeSource time,
      TransactionTemplate transactions,
      TriggerSettings settings,
      TriggerRules rules,
      Deployments deployments,
      ProjectService projects,
      WorkItemService workItems,
      RunService runs,
      EventStore events) {
    this.jdbc = jdbc;
    this.time = time;
    this.transactions = transactions;
    this.settings = settings;
    this.rules = rules;
    this.deployments = deployments;
    this.projects = projects;
    this.workItems = workItems;
    this.runs = runs;
    this.events = events;
  }

  @Scheduled(
      fixedDelayString = "${skynet.triggers.deployment-interval:15s}",
      initialDelayString = "${skynet.triggers.deployment-initial-delay:30s}")
  void poll() {
    try {
      fireAll();
    } catch (RuntimeException e) {
      log.warn("Falló la pasada de los disparadores por despliegue", e);
    }
  }

  /**
   * Una pasada: cada regla activa con cada despliegue que aún no la ha disparado.
   *
   * @return cuántos disparos se han registrado (lanzados, saltados o rechazados)
   */
  int fireAll() {
    List<UUID> ids =
        jdbc.sql(
                "SELECT id FROM trigger_rule WHERE kind = 'DEPLOYMENT' AND enabled"
                    + " ORDER BY created_at")
            .query(UUID.class)
            .list();
    int fired = 0;
    for (UUID id : ids) {
      TriggerRules.Rule rule = rules.find(id).orElse(null);
      if (rule == null || !rule.enabled() || rule.deployment() == null) {
        continue;
      }
      Instant since = rule.updatedAt();
      Instant floor = time.now().minus(LOOKBACK);
      if (since.isBefore(floor)) {
        since = floor;
      }
      for (Deployment d :
          deployments.checkedSince(
              rule.repositoryId(), rule.deployment().environment(), results(rule), since)) {
        if (fire(rule, d)) {
          fired++;
        }
      }
    }
    return fired;
  }

  private static Set<Health> results(TriggerRules.Rule rule) {
    return rule.deployment().health() == TriggerRules.DeployHealth.HEALTHY
        ? Set.of(Health.HEALTHY)
        : Set.of(Health.UNHEALTHY, Health.TIMEOUT);
  }

  /**
   * Dispara la regla con el despliegue, si no lo ha hecho ya.
   *
   * @return si ha registrado un disparo
   */
  private boolean fire(TriggerRules.Rule rule, Deployment deployment) {
    try {
      Boolean done = transactions.execute(status -> launch(rule, deployment));
      return Boolean.TRUE.equals(done);
    } catch (InvalidRequestException | ConflictException | NotFoundException e) {
      return Boolean.TRUE.equals(
          transactions.execute(
              status -> record(rule, deployment, TriggerSchedules.REJECTED, e.getMessage(), null)));
    } catch (RuntimeException e) {
      log.warn(
          "No se pudo lanzar el disparador {} por el despliegue {}", rule.id(), deployment.id(), e);
      String reason = "Error interno (" + e.getClass().getSimpleName() + ")";
      return Boolean.TRUE.equals(
          transactions.execute(
              status -> record(rule, deployment, TriggerSchedules.REJECTED, reason, null)));
    }
  }

  /** En una transacción: se reserva el disparo y lanza; si algo falla, no queda nada. */
  private boolean launch(TriggerRules.Rule rule, Deployment deployment) {
    if (!claim(rule, deployment, TriggerSchedules.LAUNCHED)) {
      return false;
    }
    // Entre la consulta y aquí pudo llegar otro despliegue que lo sustituyera.
    if (deployments.get(deployment.id()).status() != Deployments.Status.SUCCEEDED) {
      finish(rule, deployment, TriggerSchedules.SKIPPED, "Ya no es lo desplegado", null);
      return true;
    }
    if (!settings.enabled()) {
      finish(
          rule,
          deployment,
          TriggerSchedules.REJECTED,
          "Los disparadores están apagados (Ajustes → GitHub)",
          null);
      return true;
    }
    CodeRepository repository = projects.getRepository(rule.repositoryId());
    projects.requireActive(repository);
    WorkflowDefinitionView definition =
        runs.definitions().stream()
            .filter(d -> d.key().equals(rule.workflowKey()))
            .findFirst()
            .orElseThrow(
                () ->
                    new InvalidRequestException(
                        "El workflow `"
                            + rule.workflowKey()
                            + "` no tiene ninguna versión publicada"));
    UUID workItemId = workItem(rule, repository);
    UUID active =
        jdbc.sql(
                "SELECT id FROM workflow_run WHERE work_item_id = ?"
                    + " AND status IN ('PENDING', 'RUNNING') ORDER BY created_at DESC LIMIT 1")
            .param(workItemId)
            .query(UUID.class)
            .optional()
            .orElse(null);
    if (active != null) {
      finish(
          rule,
          deployment,
          TriggerSchedules.SKIPPED,
          "La ejecución anterior sigue en marcha",
          active);
      return true;
    }
    int limit = settings.dailyLimit();
    // La fila reservada ya cuenta como lanzada: se descuenta.
    if (settings.launchedToday() - 1 >= limit) {
      throw new InvalidRequestException(
          "Límite diario alcanzado: ya van "
              + limit
              + " ejecuciones lanzadas por disparadores hoy. Se puede subir en Ajustes → GitHub");
    }
    WorkflowRun run =
        runs.launch(
            workItemId,
            new RunService.Launch(repository.getId(), definition.id(), rule.inputs(), null, null));
    finish(rule, deployment, TriggerSchedules.LAUNCHED, null, run.getId());
    return true;
  }

  /** Reserva el disparo; {@code false} si este despliegue ya disparó la regla. */
  private boolean claim(TriggerRules.Rule rule, Deployment deployment, String outcome) {
    return jdbc.sql(
                "INSERT INTO trigger_deployment_fire (trigger_id, deployment_id, commit_sha, outcome,"
                    + " created_at) VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING")
            .params(
                rule.id(),
                deployment.id(),
                deployment.commitSha(),
                outcome,
                Timestamp.from(time.now()))
            .update()
        == 1;
  }

  /** Registra un disparo que no lanzó, si este despliegue aún no tenía uno. */
  private boolean record(
      TriggerRules.Rule rule, Deployment deployment, String outcome, String reason, UUID runId) {
    if (!claim(rule, deployment, outcome)) {
      return false;
    }
    finish(rule, deployment, outcome, reason, runId);
    return true;
  }

  private void finish(
      TriggerRules.Rule rule, Deployment deployment, String outcome, String reason, UUID runId) {
    String cut = reason == null ? null : IssueTriggers.cut(reason, 1000);
    Timestamp now = Timestamp.from(time.now());
    jdbc.sql(
            "UPDATE trigger_deployment_fire SET outcome = ?, reason = ?, workflow_run_id = ?"
                + " WHERE trigger_id = ? AND deployment_id = ?")
        .params(outcome, cut, runId, rule.id(), deployment.id())
        .update();
    jdbc.sql(
            "UPDATE trigger_rule SET last_outcome = ?, last_reason = ?, last_run_id = ?,"
                + " last_fired_at = ? WHERE id = ?")
        .params(outcome, cut, runId, now, rule.id())
        .update();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("triggerId", rule.id());
    payload.put("workflow", rule.workflowKey());
    payload.put("environment", deployment.environment());
    payload.put("health", deployment.health().name());
    payload.put("deploymentId", deployment.id());
    payload.put("commitSha", deployment.commitSha());
    if (reason != null) {
      payload.put("reason", reason);
    }
    events.append(
        EventDraft.of(
            IssueTriggers.AGGREGATE,
            rule.repositoryId(),
            "trigger.deployment." + outcome.toLowerCase(Locale.ROOT),
            runId,
            payload,
            time.now()));
  }

  /** El trabajo de la regla: el que ya tiene o uno nuevo con su título. */
  private UUID workItem(TriggerRules.Rule rule, CodeRepository repository) {
    UUID existing = rule.deployment().workItemId();
    if (existing != null) {
      return existing;
    }
    UUID created =
        workItems
            .create(
                repository.getProjectId(),
                rule.deployment().workItemTitle(),
                "Trabajo del disparador por despliegue de "
                    + rule.workflowKey()
                    + ": se lanza cuando lo desplegado en `"
                    + rule.deployment().environment()
                    + "` "
                    + (rule.deployment().health() == TriggerRules.DeployHealth.HEALTHY
                        ? "queda sano"
                        : "falla la salud")
                    + ". Cada disparo es una ejecución nueva.",
                rule.workItemType(),
                "skynet:deployment:" + rule.id(),
                repository.getId())
            .getId();
    jdbc.sql("UPDATE trigger_rule SET work_item_id = ? WHERE id = ?")
        .params(created, rule.id())
        .update();
    return created;
  }
}
