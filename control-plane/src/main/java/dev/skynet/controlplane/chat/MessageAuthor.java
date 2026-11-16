package dev.skynet.controlplane.chat;

/** Quién escribe un mensaje de una conversación. */
public enum MessageAuthor {
  /** La persona, desde la web. */
  USER,
  /** Skyny, el agente que contesta (CH-C). */
  SKYNY
}
