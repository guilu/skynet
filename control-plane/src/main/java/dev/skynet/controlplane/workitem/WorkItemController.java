package dev.skynet.controlplane.workitem;

import dev.skynet.controlplane.shared.ArchiveState;
import dev.skynet.controlplane.shared.Archived;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
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

@RestController
class WorkItemController {

  private final WorkItemService service;

  WorkItemController(WorkItemService service) {
    this.service = service;
  }

  record CreateWorkItem(
      @NotBlank @Size(max = 300) String title,
      @Size(max = 20000) String description,
      @NotNull WorkItemType type,
      @Size(max = 500) String externalRef) {}

  record WorkItemView(
      UUID id,
      UUID projectId,
      String key,
      String title,
      String description,
      WorkItemType type,
      String externalRef,
      WorkItemStatus status,
      Instant createdAt,
      Instant archivedAt) {
    static WorkItemView of(WorkItem w) {
      return new WorkItemView(
          w.getId(),
          w.getProjectId(),
          w.getKey(),
          w.getTitle(),
          w.getDescription(),
          w.getType(),
          w.getExternalRef(),
          w.getStatus(),
          w.getCreatedAt(),
          w.getArchivedAt());
    }
  }

  @PostMapping("/api/projects/{projectId}/work-items")
  @ResponseStatus(HttpStatus.CREATED)
  WorkItemView create(@PathVariable UUID projectId, @Valid @RequestBody CreateWorkItem request) {
    return WorkItemView.of(
        service.create(
            projectId,
            request.title(),
            request.description(),
            request.type(),
            request.externalRef()));
  }

  @GetMapping("/api/projects/{projectId}/work-items")
  List<WorkItemView> list(
      @PathVariable UUID projectId, @RequestParam(defaultValue = "false") String archived) {
    return service.list(projectId, Archived.of(archived)).stream().map(WorkItemView::of).toList();
  }

  @GetMapping("/api/work-items/{id}")
  WorkItemView get(@PathVariable UUID id) {
    return WorkItemView.of(service.get(id));
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
