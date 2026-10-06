package dev.skynet.controlplane.workflow;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Verificaciones del worktree de un agente. */
@RestController
class VerificationController {

  private final VerificationService service;

  VerificationController(VerificationService service) {
    this.service = service;
  }

  /** De la más reciente a la más antigua. */
  @GetMapping("/api/agent-runs/{id}/verifications")
  List<VerificationResult> verifications(@PathVariable UUID id) {
    return service.ofAgent(id);
  }

  /** Reejecuta la verificación; responde 409 si el worktree está ocupado o no hay comando. */
  @PostMapping("/api/agent-runs/{id}/verifications")
  @ResponseStatus(HttpStatus.CREATED)
  VerificationResult verify(@PathVariable UUID id) {
    return service.request(id);
  }
}
