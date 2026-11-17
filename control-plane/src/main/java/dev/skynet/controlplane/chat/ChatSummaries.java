package dev.skynet.controlplane.chat;

import dev.skynet.controlplane.memory.MemoryContent;
import dev.skynet.controlplane.memory.MemoryKind;
import dev.skynet.controlplane.memory.MemoryService;
import dev.skynet.controlplane.memory.MemorySource;
import dev.skynet.controlplane.shared.TimeSource;
import dev.skynet.controlplane.workflow.ChatTurnFinished;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * El resumen de las conversaciones a la memoria del proyecto (CH-F, docs/chat.md «Resumen a la
 * memoria»). Al archivar una conversación, o cuando lleva un rato sin mensajes, un turno corto de
 * Skyny resume lo que se decidió; lo que contesta se guarda como observación sin confirmar del
 * proyecto, con la etiqueta {@code chat} y un tema por conversación, así que el siguiente resumen
 * de la misma conversación es una versión nueva de la misma observación.
 */
@Component
class ChatSummaries {

  private static final Logger log = LoggerFactory.getLogger(ChatSummaries.class);

  /** Cuántas conversaciones resume cada pasada, como mucho. */
  static final int BATCH = 10;

  /** La etiqueta de lo que llega a la memoria desde el chat. */
  static final String TAG = "chat";

  private final Conversations conversations;
  private final MemoryService memory;
  private final TimeSource time;
  private final Duration idle;
  private final TransactionTemplate tx;

  ChatSummaries(
      Conversations conversations,
      MemoryService memory,
      TimeSource time,
      PlatformTransactionManager transactions,
      @Value("${skynet.chat.summary-idle:30m}") Duration idle) {
    this.conversations = conversations;
    this.memory = memory;
    this.time = time;
    this.idle = idle;
    this.tx = new TransactionTemplate(transactions);
    this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /** Resume las conversaciones que llevan {@code skynet.chat.summary-idle} sin mensajes. */
  @Scheduled(
      initialDelayString = "${skynet.chat.summary-interval:5m}",
      fixedDelayString = "${skynet.chat.summary-interval:5m}")
  void summarizeIdle() {
    List<UUID> ids = conversations.idleToSummarize(time.now().minus(idle), BATCH);
    for (UUID id : ids) {
      try {
        conversations.summarize(id);
      } catch (RuntimeException e) {
        log.warn("No se pudo resumir la conversación {}", id, e);
      }
    }
  }

  /**
   * Llega el resumen: se guarda en la memoria del proyecto en su propia transacción, para que un
   * fallo al guardarlo no deshaga el fin del turno, y la conversación vuelve a reposo.
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  void on(ChatTurnFinished finished) {
    if (!finished.summary()) {
      return;
    }
    UUID conversationId = finished.conversationId();
    UUID observation = null;
    String outcome = "failed";
    Optional<ChatSummary> summary =
        finished.completed() ? ChatSummary.parse(finished.text()) : Optional.empty();
    if (finished.completed() && summary.isEmpty()) {
      outcome = "nothing";
    }
    if (summary.isPresent()) {
      try {
        observation = tx.execute(s -> save(conversationId, finished.agentRunId(), summary.get()));
        outcome = "saved";
      } catch (RuntimeException e) {
        log.warn("No se pudo guardar el resumen de la conversación {}", conversationId, e);
      }
    }
    UUID saved = observation;
    String result = outcome;
    tx.executeWithoutResult(
        s -> conversations.summarized(conversationId, finished.agentRunId(), saved, result));
  }

  private UUID save(UUID conversationId, UUID agentRunId, ChatSummary summary) {
    Conversations.Conversation conversation = conversations.get(conversationId);
    return memory
        .save(
            conversation.projectId(),
            null,
            new MemoryContent(
                MemoryKind.SESSION_SUMMARY,
                summary.title(),
                "chat/" + conversationId,
                summary.what(),
                summary.why(),
                "Chat con Skyny: «"
                    + conversation.title()
                    + "» (conversación "
                    + conversationId
                    + ")",
                null,
                List.of(TAG)),
            MemorySource.agent(null, agentRunId))
        .observation()
        .id();
  }
}
