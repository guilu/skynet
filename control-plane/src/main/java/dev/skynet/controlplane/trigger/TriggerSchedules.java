package dev.skynet.controlplane.trigger;

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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Los disparadores programados (DS-C): cada regla {@code SCHEDULE} lanza su workflow a su hora,
 * siempre sobre el mismo trabajo. Si la ejecución anterior sigue en marcha, ese disparo se salta.
 * Si Skynet estaba apagado a la hora, al volver dispara una sola vez: la próxima hora se calcula
 * desde ahora, no desde la que tocaba.
 *
 * <p>Se reparte entre instancias con {@code FOR UPDATE SKIP LOCKED} sobre {@code next_fire_at}. La
 * hora siguiente se guarda antes de lanzar: si el control plane cae a medias, ese disparo se
 * pierde, pero nunca se repite.
 */
@Service
class TriggerSchedules {

  private static final Logger log = LoggerFactory.getLogger(TriggerSchedules.class);

  static final String LAUNCHED = "LAUNCHED";
  static final String SKIPPED = "SKIPPED";
  static final String REJECTED = "REJECTED";

  private final JdbcClient jdbc;
  private final TimeSource time;
  private final TransactionTemplate transactions;
  private final TriggerSettings settings;
  private final TriggerRules rules;
  private final ProjectService projects;
  private final WorkItemService workItems;
  private final RunService runs;
  private final EventStore events;

  TriggerSchedules(
      JdbcClient jdbc,
      TimeSource time,
      TransactionTemplate transactions,
      TriggerSettings settings,
      TriggerRules rules,
      ProjectService projects,
      WorkItemService workItems,
      RunService runs,
      EventStore events) {
    this.jdbc = jdbc;
    this.time = time;
    this.transactions = transactions;
    this.settings = settings;
    this.rules = rules;
    this.projects = projects;
    this.workItems = workItems;
    this.runs = runs;
    this.events = events;
  }

  @Scheduled(fixedDelayString = "${skynet.triggers.schedule-interval:15s}")
  void poll() {
    for (int i = 0; i < 20; i++) {
      if (!fireNext()) {
        return;
      }
    }
  }

  /**
   * Dispara la siguiente regla vencida, si hay alguna.
   *
   * @return si había alguna
   */
  boolean fireNext() {
    Instant now = time.now();
    TriggerRules.Rule rule =
        transactions.execute(
            status -> {
              UUID id =
                  jdbc.sql(
                          "SELECT id FROM trigger_rule WHERE kind = 'SCHEDULE' AND enabled"
                              + " AND next_fire_at <= ? ORDER BY next_fire_at LIMIT 1"
                              + " FOR UPDATE SKIP LOCKED")
                      .param(Timestamp.from(now))
                      .query(UUID.class)
                      .optional()
                      .orElse(null);
              if (id == null) {
                return null;
              }
              TriggerRules.Rule due = rules.find(id).orElseThrow();
              jdbc.sql("UPDATE trigger_rule SET next_fire_at = ?, last_fired_at = ? WHERE id = ?")
                  .params(TriggerRules.nextFire(due, now), Timestamp.from(now), id)
                  .update();
              return due;
            });
    if (rule == null) {
      return false;
    }
    try {
      transactions.executeWithoutResult(status -> fire(rule));
    } catch (InvalidRequestException | ConflictException | NotFoundException e) {
      transactions.executeWithoutResult(status -> outcome(rule, REJECTED, e.getMessage(), null));
    } catch (RuntimeException e) {
      log.warn("No se pudo lanzar el disparador programado {}", rule.id(), e);
      transactions.executeWithoutResult(
          status ->
              outcome(
                  rule,
                  REJECTED,
                  "Error interno (" + e.getClass().getSimpleName() + "); se intentará a su hora",
                  null));
    }
    return true;
  }

  private void fire(TriggerRules.Rule rule) {
    if (!settings.enabled()) {
      outcome(rule, REJECTED, "Los disparadores están apagados (Ajustes → GitHub)", null);
      return;
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
      outcome(rule, SKIPPED, "La ejecución anterior sigue en marcha", active);
      return;
    }
    WorkflowRun run =
        runs.launch(
            workItemId,
            new RunService.Launch(repository.getId(), definition.id(), rule.inputs(), null, null));
    outcome(rule, LAUNCHED, null, run.getId());
  }

  /** El trabajo de la regla: el que ya tiene o uno nuevo con su título. */
  private UUID workItem(TriggerRules.Rule rule, CodeRepository repository) {
    UUID existing = rule.schedule().workItemId();
    if (existing != null) {
      return existing;
    }
    UUID created =
        workItems
            .create(
                repository.getProjectId(),
                rule.schedule().workItemTitle(),
                "Trabajo del disparador programado `"
                    + rule.schedule().cron()
                    + "` ("
                    + rule.schedule().timeZone()
                    + ") de "
                    + rule.workflowKey()
                    + ". Cada disparo es una ejecución nueva.",
                rule.workItemType(),
                "skynet:schedule:" + rule.id(),
                repository.getId())
            .getId();
    jdbc.sql("UPDATE trigger_rule SET work_item_id = ? WHERE id = ?")
        .params(created, rule.id())
        .update();
    return created;
  }

  private void outcome(TriggerRules.Rule rule, String outcome, String reason, UUID runId) {
    jdbc.sql(
            "UPDATE trigger_rule SET last_outcome = ?, last_reason = ?, last_run_id = ?"
                + " WHERE id = ?")
        .params(outcome, reason == null ? null : IssueTriggers.cut(reason, 1000), runId, rule.id())
        .update();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("triggerId", rule.id());
    payload.put("workflow", rule.workflowKey());
    payload.put("cron", rule.schedule().cron());
    if (reason != null) {
      payload.put("reason", reason);
    }
    events.append(
        EventDraft.of(
            IssueTriggers.AGGREGATE,
            rule.repositoryId(),
            "trigger.schedule." + outcome.toLowerCase(java.util.Locale.ROOT),
            runId,
            payload,
            time.now()));
  }
}
