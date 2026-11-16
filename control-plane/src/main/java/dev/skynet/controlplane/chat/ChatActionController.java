package dev.skynet.controlplane.chat;

import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Las acciones por confirmar del chat (CH-B). Skyny las propone por la API del runner con el token
 * de la conversación; la persona las ve, las confirma y las rechaza desde la web, con su sesión. El
 * token de la conversación no llega a las rutas de la web (403 en {@code ChatTokenFilter}).
 */
@RestController
class ChatActionController {

  private final ChatActions actions;
  private final ChatTokens tokens;

  ChatActionController(ChatActions actions, ChatTokens tokens) {
    this.actions = actions;
    this.tokens = tokens;
  }

  /** Lo que propone Skyny: el tipo y sus datos. */
  record Proposal(ChatActions.Kind kind, JsonNode input) {}

  /**
   * Una propuesta de Skyny, con el token de su conversación; el token se mira antes que el cuerpo.
   */
  @PostMapping("/api/runner/chat/actions")
  @ResponseStatus(HttpStatus.CREATED)
  ChatActions.Action propose(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestBody(required = false) Proposal request) {
    UUID conversation = tokens.authenticate(authorization).orElseThrow(ChatTokenRejected::new);
    return request == null
        ? actions.propose(conversation, null, null)
        : actions.propose(conversation, request.kind(), request.input());
  }

  @GetMapping("/api/conversations/{id}/actions")
  List<ChatActions.Action> list(@PathVariable UUID id) {
    return actions.list(id);
  }

  @PostMapping("/api/conversations/{id}/actions/{actionId}/confirm")
  ChatActions.Action confirm(
      @PathVariable UUID id, @PathVariable UUID actionId, Principal principal) {
    return actions.confirm(id, actionId, principal.getName());
  }

  @PostMapping("/api/conversations/{id}/actions/{actionId}/reject")
  ChatActions.Action reject(
      @PathVariable UUID id, @PathVariable UUID actionId, Principal principal) {
    return actions.reject(id, actionId, principal.getName());
  }

  static final class ChatTokenRejected extends RuntimeException {
    private static final long serialVersionUID = 1L;

    ChatTokenRejected() {
      super("El token de la conversación no vale: caducado, revocado o agotado");
    }
  }

  @ExceptionHandler(ChatTokenRejected.class)
  ProblemDetail unauthorized(ChatTokenRejected e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
  }
}
