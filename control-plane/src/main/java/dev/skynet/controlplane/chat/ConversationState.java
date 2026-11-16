package dev.skynet.controlplane.chat;

/** Si alguien está contestando en una conversación. */
public enum ConversationState {
  /** Nadie está contestando ni falta nada por contestar. */
  IDLE,
  /** Hay un turno de Skyny en marcha (CH-C). */
  ANSWERING,
  /** El último mensaje no tiene quien lo conteste: no hay runner, o el chat aún no contesta. */
  OFFLINE
}
