package dev.skynet.controlplane.project;

import dev.skynet.controlplane.event.EventDraft;
import dev.skynet.controlplane.event.EventStore;
import dev.skynet.controlplane.shared.NotFoundException;
import dev.skynet.controlplane.shared.TimeSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService {

  private final ProjectRepository projects;
  private final CodeRepositoryRepository repositories;
  private final EventStore events;
  private final TimeSource time;
  private final JdbcClient jdbc;

  ProjectService(
      ProjectRepository projects,
      CodeRepositoryRepository repositories,
      EventStore events,
      TimeSource time,
      JdbcClient jdbc) {
    this.projects = projects;
    this.repositories = repositories;
    this.events = events;
    this.time = time;
    this.jdbc = jdbc;
  }

  @Transactional
  public Project create(String key, String name, String description) {
    Instant now = time.now();
    Project project = projects.save(Project.create(key, name, description, now));
    events.append(
        EventDraft.of(
            "project",
            project.getId(),
            "project.created",
            null,
            Map.of("key", key, "name", name),
            now));
    return project;
  }

  @Transactional(readOnly = true)
  public List<Project> list() {
    return projects.findAllByOrderByKeyAsc();
  }

  @Transactional(readOnly = true)
  public Project get(UUID id) {
    return projects.findById(id).orElseThrow(() -> new NotFoundException("Proyecto", id));
  }

  @Transactional
  public CodeRepository registerRepository(
      UUID projectId, String name, String localPath, String remoteUrl, String defaultBranch) {
    get(projectId);
    Instant now = time.now();
    CodeRepository repository =
        repositories.save(
            CodeRepository.create(projectId, name, localPath, remoteUrl, defaultBranch, now));
    events.append(
        EventDraft.of(
            "repository",
            repository.getId(),
            "repository.registered",
            null,
            Map.of("projectId", projectId, "name", name, "localPath", localPath),
            now));
    return repository;
  }

  @Transactional(readOnly = true)
  public List<CodeRepository> repositories(UUID projectId) {
    get(projectId);
    return repositories.findByProjectIdOrderByNameAsc(projectId);
  }

  @Transactional(readOnly = true)
  public CodeRepository getRepository(UUID repositoryId) {
    return repositories
        .findById(repositoryId)
        .orElseThrow(() -> new NotFoundException("Repositorio", repositoryId));
  }

  /** Reserva el siguiente número de trabajo del proyecto (bloquea la fila hasta el commit). */
  @Transactional
  public int nextWorkItemNumber(UUID projectId) {
    return jdbc.sql(
            "UPDATE project SET work_item_seq = work_item_seq + 1 WHERE id = ?"
                + " RETURNING work_item_seq")
        .param(projectId)
        .query(Integer.class)
        .optional()
        .orElseThrow(() -> new NotFoundException("Proyecto", projectId));
  }
}
