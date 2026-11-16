package dev.skynet.controlplane.runner;

import dev.skynet.protocol.runner.ChatToken;
import java.util.UUID;

/**
 * Quien da el token de la conversación a un turno del chat con Skyny (CH-C). Lo implementa el
 * módulo del chat; el runner lo pide por su API al arrancar el turno.
 */
public interface ChatTurnTokens {

  /**
   * El token para el turno {@code agentRunId}, si es un turno del chat de {@code runnerId} que no
   * ha terminado; si no, {@link dev.skynet.controlplane.shared.NotFoundException}.
   */
  ChatToken issue(UUID runnerId, UUID agentRunId);
}
