package dev.skynet.controlplane.chat;

import dev.skynet.controlplane.runner.ChatTurnTokens;
import dev.skynet.controlplane.shared.NotFoundException;
import dev.skynet.controlplane.workflow.ChatAgents;
import dev.skynet.protocol.runner.ChatToken;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * El token de la conversación para el MCP de Skynet de un turno (CH-C): solo para un turno del chat
 * del runner que lo pide y que no ha terminado.
 */
@Component
class ChatTurnTokenIssuer implements ChatTurnTokens {

  private final ChatAgents chats;
  private final ChatTokens tokens;

  ChatTurnTokenIssuer(ChatAgents chats, ChatTokens tokens) {
    this.chats = chats;
    this.tokens = tokens;
  }

  @Override
  @Transactional
  public ChatToken issue(UUID runnerId, UUID agentRunId) {
    UUID conversation =
        chats
            .activeTurn(runnerId, agentRunId)
            .orElseThrow(() -> new NotFoundException("Turno del chat", agentRunId));
    ChatTokens.Issued issued = tokens.issue(conversation);
    return new ChatToken(issued.token(), issued.expiresAt());
  }
}
