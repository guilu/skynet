package dev.skynet.controlplane.trigger;

import dev.skynet.controlplane.github.GitHubRepositories;
import dev.skynet.controlplane.github.GitHubRepositories.Link;
import dev.skynet.controlplane.shared.TimeSource;
import dev.skynet.controlplane.workflow.PullRequests;
import dev.skynet.controlplane.workflow.RunService;
import dev.skynet.controlplane.workflow.RunView;
import dev.skynet.controlplane.workflow.WorkflowRunFinished;
import dev.skynet.protocol.WorkflowRunStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Los avisos en GitHub de las peticiones (DS-A): una reacción 👀, un comentario en la issue y sus
 * etiquetas {@code skynet:en-curso}, {@code skynet:hecho} o {@code skynet:fallido}. Todo como la
 * App, con un token limitado al repositorio y a {@code issues: write}. Un aviso que falla se
 * reintenta con espera creciente y, tras {@link #MAX_ATTEMPTS}, se deja con su error.
 */
@Service
class TriggerNotices {

  private static final Logger log = LoggerFactory.getLogger(TriggerNotices.class);

  static final int MAX_ATTEMPTS = 5;
  static final Duration RETRY_DELAY = Duration.ofSeconds(5);

  /** Lo que tarda como mucho en mandarse un aviso: después, otra pasada puede volver a tomarlo. */
  static final Duration LEASE = Duration.ofMinutes(2);

  private static final TypeReference<List<String>> LABELS = new TypeReference<>() {};

  private final JdbcClient jdbc;
  private final ObjectMapper json;
  private final TimeSource time;
  private final TransactionTemplate transactions;
  private final GitHubRepositories github;
  private final IssueTriggers triggers;
  private final RunService runs;
  private final PullRequests pullRequests;

  TriggerNotices(
      JdbcClient jdbc,
      ObjectMapper json,
      TimeSource time,
      TransactionTemplate transactions,
      GitHubRepositories github,
      IssueTriggers triggers,
      RunService runs,
      PullRequests pullRequests) {
    this.jdbc = jdbc;
    this.json = json;
    this.time = time;
    this.transactions = transactions;
    this.github = github;
    this.triggers = triggers;
    this.runs = runs;
    this.pullRequests = pullRequests;
  }

  /** Al terminar una ejecución lanzada desde una issue, deja el aviso del resultado. */
  @EventListener
  void finished(WorkflowRunFinished finished) {
    List<UUID> launches =
        jdbc.sql("SELECT id FROM trigger_launch WHERE workflow_run_id = ? AND status = 'LAUNCHED'")
            .param(finished.workflowRunId())
            .query(UUID.class)
            .list();
    if (launches.isEmpty()) {
      return;
    }
    RunView run = runs.run(finished.workflowRunId());
    boolean ok = run.status() == WorkflowRunStatus.SUCCEEDED;
    StringBuilder text = new StringBuilder();
    text.append(
        switch (run.status()) {
          case SUCCEEDED -> "Hecho: **" + run.workItemKey() + "** ha terminado bien.";
          case CANCELLED -> "Cancelada: la ejecución de **" + run.workItemKey() + "** se canceló.";
          default -> "Ha fallado: la ejecución de **" + run.workItemKey() + "** terminó mal.";
        });
    BigDecimal cost = run.totals() == null ? null : run.totals().costUsd();
    if (cost != null && cost.signum() > 0) {
      text.append(" Coste: ")
          .append(cost.setScale(2, RoundingMode.HALF_UP).toPlainString())
          .append(" US$.");
    }
    for (PullRequests.View pr : pullRequests.ofRun(finished.workflowRunId())) {
      if (pr.url() != null) {
        text.append("\n\nPR: ").append(pr.url());
      }
    }
    for (UUID launch : launches) {
      jdbc.sql("UPDATE trigger_launch SET status = 'FINISHED', updated_at = ? WHERE id = ?")
          .params(Timestamp.from(time.now()), launch)
          .update();
      triggers.notice(
          launch,
          text.toString(),
          List.of(ok ? TriggerRules.DONE : TriggerRules.FAILED),
          List.of(TriggerRules.RUNNING, ok ? TriggerRules.FAILED : TriggerRules.DONE),
          false);
    }
  }

  @Scheduled(fixedDelayString = "${skynet.triggers.notice-interval:2s}")
  void poll() {
    for (int i = 0; i < 20; i++) {
      if (!sendNext()) {
        return;
      }
    }
  }

  private record Pending(
      UUID id,
      String source,
      long number,
      String text,
      List<String> add,
      List<String> remove,
      boolean react,
      int attempts) {}

  /**
   * Manda el siguiente aviso pendiente.
   *
   * @return si había alguno
   */
  boolean sendNext() {
    Instant now = time.now();
    Pending pending =
        transactions.execute(
            status -> {
              Optional<Pending> next =
                  jdbc.sql(
                          "SELECT id, source, issue_number, notice, notice_add, notice_remove,"
                              + " notice_react, notice_attempts FROM trigger_launch"
                              + " WHERE notice_after <= ? ORDER BY notice_after LIMIT 1"
                              + " FOR UPDATE SKIP LOCKED")
                      .param(Timestamp.from(now))
                      .query(
                          (rs, n) ->
                              new Pending(
                                  rs.getObject("id", UUID.class),
                                  rs.getString("source"),
                                  rs.getLong("issue_number"),
                                  rs.getString("notice"),
                                  json.readValue(rs.getString("notice_add"), LABELS),
                                  json.readValue(rs.getString("notice_remove"), LABELS),
                                  rs.getBoolean("notice_react"),
                                  rs.getInt("notice_attempts")))
                      .optional();
              next.ifPresent(
                  p ->
                      jdbc.sql("UPDATE trigger_launch SET notice_after = ? WHERE id = ?")
                          .params(Timestamp.from(now.plus(LEASE)), p.id())
                          .update());
              return next.orElse(null);
            });
    if (pending == null) {
      return false;
    }
    try {
      Link link =
          github
              .linkNamed(pending.source())
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "La App no llega a " + pending.source() + " o no está configurada"));
      if (pending.react()) {
        github.reactToIssue(link, pending.number(), "eyes");
      }
      if (pending.text() != null) {
        github.commentOnIssue(link, pending.number(), pending.text());
      }
      if (!pending.add().isEmpty() || !pending.remove().isEmpty()) {
        github.relabelIssue(link, pending.number(), pending.add(), pending.remove());
      }
      sent(pending);
    } catch (RuntimeException e) {
      failed(pending, e);
    }
    return true;
  }

  private void sent(Pending pending) {
    // Solo se borra lo que se ha mandado: si mientras tanto llegó otro aviso, se queda.
    transactions.executeWithoutResult(
        status ->
            jdbc.sql(
                    "UPDATE trigger_launch SET"
                        + " notice = CASE WHEN notice IS NOT DISTINCT FROM ? THEN NULL"
                        + "   ELSE substr(notice, length(?) + 3) END,"
                        + " notice_add = CASE WHEN notice IS NOT DISTINCT FROM ?"
                        + "   THEN '[]'::jsonb ELSE notice_add END,"
                        + " notice_remove = CASE WHEN notice IS NOT DISTINCT FROM ?"
                        + "   THEN '[]'::jsonb ELSE notice_remove END,"
                        + " notice_react = false, notice_attempts = 0, notice_error = NULL,"
                        + " notice_after = CASE WHEN notice IS NOT DISTINCT FROM ? THEN NULL"
                        + "   ELSE ? END"
                        + " WHERE id = ?")
                .params(
                    pending.text(),
                    pending.text() == null ? "" : pending.text(),
                    pending.text(),
                    pending.text(),
                    pending.text(),
                    Timestamp.from(time.now()),
                    pending.id())
                .update());
  }

  private void failed(Pending pending, RuntimeException e) {
    int attempts = pending.attempts() + 1;
    String error = IssueTriggers.cut(e.getClass().getSimpleName() + ": " + e.getMessage(), 1000);
    if (attempts >= MAX_ATTEMPTS) {
      log.warn("Aviso en {}#{} abandonado: {}", pending.source(), pending.number(), error);
    }
    transactions.executeWithoutResult(
        status ->
            jdbc.sql(
                    "UPDATE trigger_launch SET notice_attempts = ?, notice_error = ?,"
                        + " notice_after = ? WHERE id = ?")
                .params(
                    attempts,
                    error,
                    attempts >= MAX_ATTEMPTS
                        ? null
                        : Timestamp.from(
                            time.now().plus(RETRY_DELAY.multipliedBy(1L << (attempts - 1)))),
                    pending.id())
                .update());
  }
}
