package dev.skynet.controlplane.workflow;

import java.util.UUID;

/**
 * Un turno del chat con Skyny ha terminado (CH-C), en la transacción del evento que lo cierra.
 *
 * @param agentRunId el turno
 * @param conversationId su conversación
 * @param messageId el mensaje al que contestaba
 * @param completed si terminó bien
 * @param text lo último que dijo el agente, o {@code null} si no dijo nada
 * @param error por qué no terminó bien, o {@code null}
 */
public record ChatTurnFinished(
    UUID agentRunId,
    UUID conversationId,
    UUID messageId,
    boolean completed,
    String text,
    String error) {}
