/**
 * El chat con Skyny (hito CH, docs/chat.md): conversaciones que se guardan y se retoman, con sus
 * mensajes en orden. Cada conversación puede tener de contexto el proyecto o el trabajo desde el
 * que se abrió.
 *
 * <p>CH-A solo guarda: todavía no hay quien conteste, así que cada mensaje deja la conversación en
 * {@link dev.skynet.controlplane.chat.ConversationState#OFFLINE}. Cada cambio deja un evento {@code
 * chat.*} en el registro, y la web lo recibe por el flujo en vivo de siempre.
 */
package dev.skynet.controlplane.chat;
