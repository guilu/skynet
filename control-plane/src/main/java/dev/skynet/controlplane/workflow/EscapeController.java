package dev.skynet.controlplane.workflow;

import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Los escapes del juez (EV-A): lo que aprobó y luego hubo que revertir o se marcó a mano. */
@RestController
class EscapeController {

  private final Escapes escapes;

  EscapeController(Escapes escapes) {
    this.escapes = escapes;
  }

  @GetMapping("/api/workflow-runs/{id}/escapes")
  List<Escapes.EscapeView> ofRun(@PathVariable UUID id) {
    return escapes.ofRun(id);
  }

  /** Marca a mano que a la ejecución se le escapó un fallo, con una nota. */
  @PostMapping("/api/workflow-runs/{id}/escapes")
  @ResponseStatus(HttpStatus.CREATED)
  Escapes.EscapeView mark(
      @PathVariable UUID id,
      @RequestBody(required = false) MarkRequest request,
      Principal principal) {
    return escapes.mark(id, request == null ? null : request.note(), actor(principal));
  }

  @DeleteMapping("/api/escapes/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void remove(@PathVariable UUID id, Principal principal) {
    escapes.remove(id, actor(principal));
  }

  @GetMapping("/api/factory/escapes")
  List<Escapes.EscapeView> since(@RequestParam(defaultValue = "30") int days) {
    return escapes.since(days);
  }

  private static String actor(Principal principal) {
    return principal == null ? "anónimo" : principal.getName();
  }

  record MarkRequest(String note) {}
}
