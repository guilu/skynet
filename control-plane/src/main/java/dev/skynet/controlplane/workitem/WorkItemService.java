package dev.skynet.controlplane.workitem;

import dev.skynet.controlplane.event.EventDraft;
import dev.skynet.controlplane.event.EventStore;
import dev.skynet.controlplane.project.Project;
import dev.skynet.controlplane.project.ProjectService;
import dev.skynet.controlplane.shared.NotFoundException;
import dev.skynet.controlplane.shared.TimeSource;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkItemService {

  private final WorkItemRepository workItems;
  private final ProjectService projects;
  private final EventStore events;
  private final TimeSource time;

  WorkItemService(
      WorkItemRepository workItems, ProjectService projects, EventStore events, TimeSource time) {
    this.workItems = workItems;
    this.projects = projects;
    this.events = events;
    this.time = time;
  }

  @Transactional
  public WorkItem create(
      UUID projectId, String title, String description, WorkItemType type, String externalRef) {
    Project project = projects.get(projectId);
    int number = projects.nextWorkItemNumber(projectId);
    Instant now = time.now();
    WorkItem item =
        workItems.save(
            WorkItem.create(
                projectId, project.getKey(), number, title, description, type, externalRef, now));
    events.append(
        EventDraft.of(
            "work_item",
            item.getId(),
            "workitem.created",
            null,
            Map.of(
                "projectId", projectId, "key", item.getKey(), "title", title, "type", type.name()),
            now));
    return item;
  }

  @Transactional(readOnly = true)
  public List<WorkItem> list(UUID projectId) {
    projects.get(projectId);
    return workItems.findByProjectIdOrderByNumberDesc(projectId);
  }

  @Transactional(readOnly = true)
  public WorkItem get(UUID id) {
    return workItems.findById(id).orElseThrow(() -> new NotFoundException("Trabajo", id));
  }

  /** Trabajos por id; los que no existen se omiten. */
  @Transactional(readOnly = true)
  public List<WorkItem> getAll(Collection<UUID> ids) {
    return workItems.findAllById(ids);
  }
}
