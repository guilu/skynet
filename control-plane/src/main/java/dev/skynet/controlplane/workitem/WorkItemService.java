package dev.skynet.controlplane.workitem;

import dev.skynet.controlplane.event.EventDraft;
import dev.skynet.controlplane.event.EventStore;
import dev.skynet.controlplane.project.Project;
import dev.skynet.controlplane.project.ProjectService;
import dev.skynet.controlplane.shared.ArchiveState;
import dev.skynet.controlplane.shared.Archived;
import dev.skynet.controlplane.shared.Archiving;
import dev.skynet.controlplane.shared.ConflictException;
import dev.skynet.controlplane.shared.NotFoundException;
import dev.skynet.controlplane.shared.TimeSource;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkItemService {

  private final WorkItemRepository workItems;
  private final ProjectService projects;
  private final EventStore events;
  private final TimeSource time;
  private final JdbcClient jdbc;

  WorkItemService(
      WorkItemRepository workItems,
      ProjectService projects,
      EventStore events,
      TimeSource time,
      JdbcClient jdbc) {
    this.workItems = workItems;
    this.projects = projects;
    this.events = events;
    this.time = time;
    this.jdbc = jdbc;
  }

  @Transactional
  public WorkItem create(
      UUID projectId, String title, String description, WorkItemType type, String externalRef) {
    Project project = projects.get(projectId);
    projects.requireActive(project);
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
  public List<WorkItem> list(UUID projectId, Archived archived) {
    projects.get(projectId);
    return workItems.findByProjectIdOrderByNumberDesc(projectId).stream()
        .filter(w -> archived.accepts(w.getArchivedAt()))
        .toList();
  }

  /**
   * Archiva el trabajo: sale de las listas junto con sus ejecuciones y no admite lanzar agentes
   * hasta que se restaure. No se archiva con ejecuciones en curso.
   */
  @Transactional
  public ArchiveState archive(UUID id) {
    WorkItem item = get(id);
    if (item.getArchivedAt() != null) {
      return new ArchiveState(id, item.getArchivedAt());
    }
    long active =
        jdbc.sql(
                "SELECT count(*) FROM workflow_run WHERE work_item_id = ?"
                    + " AND status IN ('PENDING', 'RUNNING')")
            .param(id)
            .query(Long.class)
            .single();
    if (active > 0) {
      throw new ConflictException(
          "El trabajo "
              + item.getKey()
              + " tiene ejecuciones en curso: cancélalas o espera a que terminen antes de"
              + " archivarlo");
    }
    Instant now = time.now();
    Archiving.set(jdbc, "work_item", id, now);
    events.append(
        EventDraft.of(
            "work_item",
            id,
            "workitem.archived",
            null,
            Map.of("projectId", item.getProjectId(), "key", item.getKey()),
            now));
    return new ArchiveState(id, now);
  }

  /** Restaura el trabajo; su proyecto no puede estar archivado. */
  @Transactional
  public ArchiveState restore(UUID id) {
    WorkItem item = get(id);
    if (item.getArchivedAt() == null) {
      return new ArchiveState(id, null);
    }
    projects.requireActive(projects.get(item.getProjectId()));
    Archiving.set(jdbc, "work_item", id, null);
    events.append(
        EventDraft.of(
            "work_item",
            id,
            "workitem.restored",
            null,
            Map.of("projectId", item.getProjectId(), "key", item.getKey()),
            time.now()));
    return new ArchiveState(id, null);
  }

  /** Falla si el trabajo o su proyecto están archivados: no se lanzan agentes en ellos. */
  public void requireActive(WorkItem item) {
    projects.requireActive(projects.get(item.getProjectId()));
    if (item.getArchivedAt() != null) {
      throw new ConflictException(
          "El trabajo " + item.getKey() + " está archivado: restáuralo para lanzar agentes");
    }
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
