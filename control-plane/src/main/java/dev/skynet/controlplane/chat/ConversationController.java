package dev.skynet.controlplane.chat;

import dev.skynet.controlplane.shared.Archived;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Las conversaciones del chat con Skyny desde la web (CH-A). */
@RestController
class ConversationController {

  private final Conversations conversations;
  private final ChatTokens tokens;

  ConversationController(Conversations conversations, ChatTokens tokens) {
    this.conversations = conversations;
    this.tokens = tokens;
  }

  /**
   * Una conversación nueva.
   *
   * @param projectId proyecto de contexto, o {@code null}
   * @param workItemId trabajo de contexto, o {@code null}; su proyecto es el de la conversación
   * @param title título, o {@code null} para sacarlo del primer mensaje
   * @param message primer mensaje, o {@code null}
   */
  record CreateConversation(UUID projectId, UUID workItemId, String title, String message) {}

  record PostMessage(String body) {}

  @PostMapping("/api/conversations")
  @ResponseStatus(HttpStatus.CREATED)
  Conversations.Thread create(@RequestBody CreateConversation request, Principal principal) {
    return conversations.create(
        request.projectId(),
        request.workItemId(),
        request.title(),
        request.message(),
        principal.getName());
  }

  /** Las conversaciones, la más reciente primero; {@code projectId} las de un proyecto. */
  @GetMapping("/api/conversations")
  List<Conversations.Conversation> list(
      @RequestParam(required = false) UUID projectId,
      @RequestParam(required = false) String archived,
      @RequestParam(defaultValue = "20") int limit) {
    return conversations.list(projectId, Archived.of(archived), limit);
  }

  @GetMapping("/api/conversations/{id}")
  Conversations.Thread get(@PathVariable UUID id) {
    return conversations.thread(id);
  }

  @PostMapping("/api/conversations/{id}/messages")
  @ResponseStatus(HttpStatus.CREATED)
  Conversations.Posted post(@PathVariable UUID id, @RequestBody PostMessage request) {
    return conversations.post(id, request.body());
  }

  /** Para el turno de Skyny en curso, si lo hay (CH-C). */
  @PostMapping("/api/conversations/{id}/stop")
  Conversations.Conversation stop(@PathVariable UUID id) {
    return conversations.stop(id);
  }

  @PostMapping("/api/conversations/{id}/archive")
  Conversations.Conversation archive(@PathVariable UUID id, Principal principal) {
    return conversations.archive(id, principal.getName());
  }

  /**
   * Un token de la conversación para el MCP de Skynet (CH-B), para probarlo a mano: solo lee. El
   * turno de Skyny (CH-C) pide el suyo por la API del runner.
   */
  @PostMapping("/api/conversations/{id}/chat-token")
  ChatTokens.Issued chatToken(@PathVariable UUID id) {
    return tokens.issue(id);
  }
}
