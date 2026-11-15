package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.memory.MemoryCuration;
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
 * Aplica la salida del curador de la memoria cuando su invocación termina bien (MV-E): las páginas
 * de la wiki, el informe y las propuestas ({@link MemoryCuration}); después regenera el índice de
 * la wiki para que nombre las páginas nuevas. Si falla, la ejecución sigue igual.
 */
@Component
class MemoryCurator {

  private static final Logger log = LoggerFactory.getLogger(MemoryCurator.class);

  private final JdbcClient jdbc;
  private final MemoryCuration curation;
  private final WikiWriter wiki;

  MemoryCurator(JdbcClient jdbc, MemoryCuration curation, WikiWriter wiki) {
    this.jdbc = jdbc;
    this.curation = curation;
    this.wiki = wiki;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void on(CuratorFinished finished) {
    UUID agentRunId = finished.agentRunId();
    try {
      String output =
          jdbc.sql("SELECT structured_output FROM agent_run WHERE id = ?")
              .param(agentRunId)
              .query(String.class)
              .optional()
              .orElse(null);
      curation
          .apply(agentRunId, output)
          .filter(c -> c.pages().stream().anyMatch(MemoryCuration.WrittenPage::changed))
          .ifPresent(c -> wiki.refresh(c.projectId()));
    } catch (RuntimeException e) {
      log.warn("No se pudo aplicar la curación de la invocación {}", agentRunId, e);
    }
  }
}
