package dev.skynet.controlplane.purge;

import dev.skynet.controlplane.purge.PurgeService.Kind;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Eliminar lo archivado. {@code GET …/deletion-preview} dice qué se borraría y qué lo impide;
 * {@code DELETE} lo borra (409 con los motivos si algo lo impide) y devuelve lo borrado.
 */
@RestController
class PurgeController {

  private final PurgeService service;

  PurgeController(PurgeService service) {
    this.service = service;
  }

  /**
   * @param requested worktrees cuya eliminación se ha pedido ahora; los que tienen algo en curso se
   *     saltan
   */
  record WorkspaceCleanupResult(int requested) {}

  @GetMapping("/api/projects/{id}/deletion-preview")
  DeletionPreview previewProject(@PathVariable UUID id) {
    return service.preview(Kind.PROJECT, id, null);
  }

  @DeleteMapping("/api/projects/{id}")
  DeletionPreview.Counts deleteProject(@PathVariable UUID id) {
    return service.delete(Kind.PROJECT, id, null);
  }

  @PostMapping("/api/projects/{id}/workspaces/cleanup")
  WorkspaceCleanupResult cleanupProject(@PathVariable UUID id) {
    return new WorkspaceCleanupResult(service.cleanupWorkspaces(Kind.PROJECT, id));
  }

  @GetMapping("/api/projects/{projectId}/repositories/{id}/deletion-preview")
  DeletionPreview previewRepository(@PathVariable UUID projectId, @PathVariable UUID id) {
    return service.preview(Kind.REPOSITORY, id, projectId);
  }

  @DeleteMapping("/api/projects/{projectId}/repositories/{id}")
  DeletionPreview.Counts deleteRepository(@PathVariable UUID projectId, @PathVariable UUID id) {
    return service.delete(Kind.REPOSITORY, id, projectId);
  }

  @GetMapping("/api/work-items/{id}/deletion-preview")
  DeletionPreview previewWorkItem(@PathVariable UUID id) {
    return service.preview(Kind.WORK_ITEM, id, null);
  }

  @DeleteMapping("/api/work-items/{id}")
  DeletionPreview.Counts deleteWorkItem(@PathVariable UUID id) {
    return service.delete(Kind.WORK_ITEM, id, null);
  }

  @PostMapping("/api/work-items/{id}/workspaces/cleanup")
  WorkspaceCleanupResult cleanupWorkItem(@PathVariable UUID id) {
    return new WorkspaceCleanupResult(service.cleanupWorkspaces(Kind.WORK_ITEM, id));
  }

  @GetMapping("/api/workflow-runs/{id}/deletion-preview")
  DeletionPreview previewRun(@PathVariable UUID id) {
    return service.preview(Kind.RUN, id, null);
  }

  @DeleteMapping("/api/workflow-runs/{id}")
  DeletionPreview.Counts deleteRun(@PathVariable UUID id) {
    return service.delete(Kind.RUN, id, null);
  }

  @PostMapping("/api/workflow-runs/{id}/workspaces/cleanup")
  WorkspaceCleanupResult cleanupRun(@PathVariable UUID id) {
    return new WorkspaceCleanupResult(service.cleanupWorkspaces(Kind.RUN, id));
  }

  @GetMapping("/api/runners/{id}/deletion-preview")
  DeletionPreview previewRunner(@PathVariable UUID id) {
    return service.preview(Kind.RUNNER, id, null);
  }

  @DeleteMapping("/api/runners/{id}")
  DeletionPreview.Counts deleteRunner(@PathVariable UUID id) {
    return service.delete(Kind.RUNNER, id, null);
  }
}
