package dev.skynet.protocol.runner;

import java.time.Instant;

/**
 * El token de la conversación de un turno del chat (CH-C), para el MCP de Skynet del agente: solo
 * lee la API y propone acciones. Caduca solo y deja de valer al terminar el turno.
 *
 * @param token {@code skc_…}
 * @param expiresAt cuándo caduca
 */
public record ChatToken(String token, Instant expiresAt) {}
