package dev.skynet.controlplane.memory;

import dev.skynet.controlplane.shared.Archived;
import dev.skynet.controlplane.shared.InvalidRequestException;
import java.security.Principal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** La memoria de un proyecto desde la web. Lo que se guarda aquí lo escribe una persona. */
@RestController
class MemoryController {

  private final MemoryService service;

  MemoryController(MemoryService service) {
    this.service = service;
  }

  /**
   * Una observación nueva, o una versión nueva si ya hay una viva con el mismo {@code topicKey}.
   *
   * @param repositoryId repositorio del proyecto, o {@code null} para todo el proyecto
   */
  record SaveObservation(
      UUID repositoryId,
      MemoryKind kind,
      String title,
      String topicKey,
      String what,
      String why,
      String where,
      String learned,
      List<String> tags) {

    MemoryContent content() {
      return new MemoryContent(kind, title, topicKey, what, why, where, learned, tags);
    }
  }

  record EditObservation(
      MemoryKind kind,
      String title,
      String topicKey,
      String what,
      String why,
      String where,
      String learned,
      List<String> tags) {

    MemoryContent content() {
      return new MemoryContent(kind, title, topicKey, what, why, where, learned, tags);
    }
  }

  /**
   * Busca en la memoria del proyecto. {@code repository} es el id de un repositorio o {@code
   * project} para lo que es de todo el proyecto; sin él, todo. Con un repositorio, {@code
   * withProject=true} añade lo de todo el proyecto. {@code kind} se puede repetir.
   */
  @GetMapping("/api/projects/{projectId}/memory")
  MemoryPage list(
      @PathVariable UUID projectId,
      @RequestParam(required = false) String repository,
      @RequestParam(defaultValue = "false") boolean withProject,
      @RequestParam(required = false) Set<MemoryKind> kind,
      @RequestParam(required = false) MemoryOrigin origin,
      @RequestParam(required = false) Boolean pinned,
      @RequestParam(defaultValue = "false") String archived,
      @RequestParam(required = false) String q,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    MemoryQuery.Scope scope;
    UUID repositoryId = null;
    if (repository == null || repository.isBlank()) {
      scope = MemoryQuery.Scope.ALL;
    } else if (repository.strip().equals("project")) {
      scope = MemoryQuery.Scope.PROJECT;
    } else {
      repositoryId = uuid(repository.strip());
      scope = withProject ? MemoryQuery.Scope.REPOSITORY_AND_PROJECT : MemoryQuery.Scope.REPOSITORY;
    }
    return service.search(
        projectId,
        new MemoryQuery(
            scope, repositoryId, kind, origin, pinned, Archived.of(archived), q, page, size));
  }

  @PostMapping("/api/projects/{projectId}/memory")
  ResponseEntity<MemoryObservation> save(
      @PathVariable UUID projectId, @RequestBody SaveObservation request, Principal principal) {
    MemorySaved saved =
        service.save(
            projectId,
            request.repositoryId(),
            request.content(),
            MemorySource.human(principal.getName()));
    return ResponseEntity.status(saved.created() ? HttpStatus.CREATED : HttpStatus.OK)
        .body(saved.observation());
  }

  @GetMapping("/api/memory/{id}")
  MemoryObservation get(@PathVariable UUID id) {
    return service.get(id);
  }

  /** Lo que una invocación de agente recibió, buscó, leyó y guardó. */
  @GetMapping("/api/agent-runs/{id}/memory")
  AgentMemory agentMemory(@PathVariable UUID id) {
    return service.agentMemory(id);
  }

  @PutMapping("/api/memory/{id}")
  MemoryObservation edit(
      @PathVariable UUID id, @RequestBody EditObservation request, Principal principal) {
    return service.edit(id, request.content(), principal.getName());
  }

  @GetMapping("/api/memory/{id}/history")
  List<MemoryRevision> history(@PathVariable UUID id) {
    return service.history(id);
  }

  @PostMapping("/api/memory/{id}/archive")
  MemoryObservation archive(@PathVariable UUID id, Principal principal) {
    return service.archive(id, principal.getName());
  }

  @PostMapping("/api/memory/{id}/restore")
  MemoryObservation restore(@PathVariable UUID id, Principal principal) {
    return service.restore(id, principal.getName());
  }

  @PostMapping("/api/memory/{id}/pin")
  MemoryObservation pin(@PathVariable UUID id, Principal principal) {
    return service.pin(id, true, principal.getName());
  }

  @PostMapping("/api/memory/{id}/unpin")
  MemoryObservation unpin(@PathVariable UUID id, Principal principal) {
    return service.pin(id, false, principal.getName());
  }

  private static UUID uuid(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      throw new InvalidRequestException(
          "repository debe ser el id de un repositorio o project: " + value);
    }
  }
}
