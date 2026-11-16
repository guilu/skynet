package dev.skynet.protocol.runner;

import java.util.Objects;
import java.util.UUID;

/**
 * Un turno del chat con Skyny (CH-C, docs/chat.md «El turno de Skyny»): el agente contesta a un
 * mensaje de la conversación, sin worktree ni repositorio, en una carpeta vacía y fija por
 * conversación para que {@code --resume} encuentre su sesión. Sus únicas herramientas son las del
 * MCP de Skynet, con el token de la conversación que el runner pide al arrancar.
 *
 * @param conversationId la conversación
 */
public record ChatTurn(UUID conversationId) {

  /**
   * Lo que declara entre sus proveedores un runner que sabe ejecutar turnos del chat: solo los
   * reciben esos runners. No es un proveedor de agentes.
   */
  public static final String RUNNER_FEATURE = "skyny-chat";

  /** El worktree que figura en la reanudación de un turno: el runner usa su carpeta del chat. */
  public static final String WORKSPACE = "chat";

  public ChatTurn {
    Objects.requireNonNull(conversationId, "conversationId");
  }
}
