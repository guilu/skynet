package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.memory.MemoryService;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Confirma lo que guardaron los agentes de una ejecución cuando una persona acepta su trabajo
 * (MV-A, docs/memoria.md «Confianza»): al fusionarse su PR o, si la ejecución termina bien, cuando
 * una persona aprobó alguna de sus puertas. Las aprobaciones automáticas no cuentan.
 *
 * <p>Va después del commit y en su propia transacción: si falla, la fusión o la ejecución quedan
 * igual y lo guardado sigue sin confirmar.
 */
@Component
class MemoryConfirmations {

  private static final Logger log = LoggerFactory.getLogger(MemoryConfirmations.class);

  static final String MERGED = "Se fusionó la PR de su ejecución";
  static final String APPROVED = "Una persona aceptó su ejecución";

  private final JdbcClient jdbc;
  private final MemoryService memory;

  MemoryConfirmations(JdbcClient jdbc, MemoryService memory) {
    this.jdbc = jdbc;
    this.memory = memory;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void on(PullRequestMerged merged) {
    confirm(merged.workflowRunId(), MERGED);
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void on(WorkflowRunFinished finished) {
    UUID runId = finished.workflowRunId();
    boolean accepted =
        jdbc.sql(
                """
                SELECT EXISTS (
                  SELECT 1 FROM workflow_run r
                  WHERE r.id = :run AND r.status = 'SUCCEEDED' AND (
                    EXISTS (SELECT 1 FROM approval_request q
                            JOIN approval_decision d ON d.approval_request_id = q.id
                            WHERE q.workflow_run_id = r.id AND d.action = 'APPROVE'
                              AND d.actor <> :system)
                    OR EXISTS (SELECT 1 FROM github_pull_request p
                               WHERE p.workflow_run_id = r.id AND p.status = 'MERGED')))
                """)
            .param("run", runId)
            .param("system", Approvals.SYSTEM_ACTOR)
            .query(Boolean.class)
            .single();
    if (accepted) {
      confirm(runId, APPROVED);
    }
  }

  private void confirm(UUID runId, String reason) {
    try {
      int confirmed = memory.confirmRun(runId, reason);
      if (confirmed > 0) {
        log.info("Confirmadas {} observaciones de la ejecución {} ({})", confirmed, runId, reason);
      }
    } catch (RuntimeException e) {
      log.warn("No se pudo confirmar la memoria de la ejecución {}", runId, e);
    }
  }
}
