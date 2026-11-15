package dev.skynet.controlplane.memory;

import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Los informes del curador (MV-E) y sus propuestas, que decide una persona: aceptar una propuesta
 * archiva sus entradas; rechazarla no cambia nada.
 */
@RestController
class MemoryCurationController {

  private final MemoryCuration curation;

  MemoryCurationController(MemoryCuration curation) {
    this.curation = curation;
  }

  @GetMapping("/api/projects/{projectId}/memory/curations")
  List<MemoryCuration.Curation> curations(
      @PathVariable UUID projectId, @RequestParam(defaultValue = "10") int limit) {
    return curation.curations(projectId, limit);
  }

  @GetMapping("/api/projects/{projectId}/memory/proposals")
  List<MemoryCuration.Proposal> proposals(
      @PathVariable UUID projectId, @RequestParam(required = false) MemoryCuration.Status status) {
    return curation.proposals(projectId, status);
  }

  @PostMapping("/api/memory/proposals/{id}/accept")
  MemoryCuration.Proposal accept(@PathVariable UUID id, Principal principal) {
    return curation.accept(id, principal.getName());
  }

  @PostMapping("/api/memory/proposals/{id}/reject")
  MemoryCuration.Proposal reject(@PathVariable UUID id, Principal principal) {
    return curation.reject(id, principal.getName());
  }
}
