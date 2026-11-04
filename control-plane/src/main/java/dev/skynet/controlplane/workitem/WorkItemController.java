package dev.skynet.controlplane.workitem;

import dev.skynet.controlplane.shared.ArchiveState;
import dev.skynet.controlplane.shared.Archived;
import dev.skynet.controlplane.workspace.WorkPhase;
import dev.skynet.controlplane.workspace.WorkspaceQueries;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class WorkItemController {

  private final WorkItemService service;
  private final WorkspaceQueries workspace;

  WorkItemController(WorkItemService service, WorkspaceQueries workspace) {
    this.service = service;
    this.workspace = workspace;
  }

  record CreateWorkItem(
      @NotBlank @Size(max = 300) String title,
      @Size(max = 20000) String description,
      @NotNull WorkItemType type,
      @Size(max = 500) String externalRef,
      UUID repositoryId) {}

  record WorkItemView(
      UUID id,
      UUID projectId,
      String key,
      String title,
      String description,
      WorkItemType type,
      String externalRef,
      WorkItemStatus status,
      UUID repositoryId,
      WorkPhase phase,
      Instant createdAt,
      Instant archivedAt) {
    /** {@code phase}: la del trabajo; null si está archivado (FS-B). */
    static WorkItemView of(WorkItem w, WorkPhase phase) {
      return new WorkItemView(
          w.getId(),
          w.getProjectId(),
          w.getKey(),
          w.getTitle(),
          w.getDescription(),
          w.getType(),
          w.getExternalRef(),
          w.getStatus(),
          w.getRepositoryId(),
          phase,
          w.getCreatedAt(),
          w.getArchivedAt());
    }
  }

  @PostMapping("/api/projects/{projectId}/work-items")
  @ResponseStatus(HttpStatus.CREATED)
  WorkItemView create(@PathVariable UUID projectId, @Valid @RequestBody CreateWorkItem request) {
    return view(
        service.create(
            projectId,
            request.title(),
            request.description(),
            request.type(),
            request.externalRef(),
            request.repositoryId()));
  }

  @GetMapping("/api/projects/{projectId}/work-items")
  List<WorkItemView> list(
      @PathVariable UUID projectId, @RequestParam(defaultValue = "false") String archived) {
    List<WorkItem> items = service.list(projectId, Archived.of(archived));
    Map<UUID, WorkPhase> phases = workspace.phases(items.stream().map(WorkItem::getId).toList());
    return items.stream().map(w -> WorkItemView.of(w, phases.get(w.getId()))).toList();
  }

  @GetMapping("/api/work-items/{id}")
  WorkItemView get(@PathVariable UUID id) {
    return view(service.get(id));
  }

  private WorkItemView view(WorkItem item) {
    return WorkItemView.of(item, workspace.phases(List.of(item.getId())).get(item.getId()));
  }

  @PostMapping("/api/work-items/{id}/archive")
  ArchiveState archive(@PathVariable UUID id) {
    return service.archive(id);
  }

  @PostMapping("/api/work-items/{id}/restore")
  ArchiveState restore(@PathVariable UUID id) {
    return service.restore(id);
  }
}
