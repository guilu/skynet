package dev.skynet.controlplane.chat;

import dev.skynet.controlplane.workflow.ChatTurnFinished;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Lleva a la conversación lo que dijo Skyny al terminar un turno (CH-C), en la misma transacción, y
 * deja sin valor el token de la conversación: nadie más lo usa hasta el turno siguiente.
 */
@Component
class ChatTurnListener {

  private final Conversations conversations;
  private final ChatTokens tokens;

  ChatTurnListener(Conversations conversations, ChatTokens tokens) {
    this.conversations = conversations;
    this.tokens = tokens;
  }

  @EventListener
  void on(ChatTurnFinished finished) {
    tokens.revoke(finished.conversationId());
    // El de un resumen no deja mensaje: lo guarda ChatSummaries en la memoria.
    if (!finished.summary()) {
      conversations.answered(finished);
    }
  }
}
