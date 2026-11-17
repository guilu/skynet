/**
 * El chat con Skyny (hito CH, docs/chat.md): conversaciones que se guardan y se retoman, con sus
 * mensajes en orden. Cada conversación puede tener de contexto el proyecto o el trabajo desde el
 * que se abrió.
 *
 * <p>CH-A solo guarda: todavía no hay quien conteste, así que cada mensaje deja la conversación en
 * {@link dev.skynet.controlplane.chat.ConversationState#OFFLINE}. Cada cambio deja un evento {@code
 * chat.*} en el registro, y la web lo recibe por el flujo en vivo de siempre.
 *
 * <p>CH-B añade el token de la conversación ({@link dev.skynet.controlplane.chat.ChatTokens}), con
 * el que el MCP de Skynet lee la API en nombre de Skyny, y las acciones por confirmar ({@link
 * dev.skynet.controlplane.chat.ChatActions}): Skyny las propone y la persona las confirma desde la
 * web.
 *
 * <p>CH-F resume las conversaciones con proyecto para su memoria ({@link
 * dev.skynet.controlplane.chat.ChatSummaries}): al archivarlas o tras un rato sin mensajes, un
 * turno corto de Skyny escribe lo decidido y se guarda como observación sin confirmar.
 */
package dev.skynet.controlplane.chat;
