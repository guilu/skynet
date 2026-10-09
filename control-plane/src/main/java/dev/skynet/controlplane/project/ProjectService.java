package dev.skynet.controlplane.project;

import dev.skynet.controlplane.event.EventDraft;
import dev.skynet.controlplane.event.EventStore;
import dev.skynet.controlplane.shared.ArchiveState;
import dev.skynet.controlplane.shared.Archived;
import dev.skynet.controlplane.shared.Archiving;
import dev.skynet.controlplane.shared.ConflictException;
import dev.skynet.controlplane.shared.NotFoundException;
import dev.skynet.controlplane.shared.TimeSource;
import java.time.Instant;
import java.util.LinkedHashMap;
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
  private final AgentDefaults agentDefaults;

  ProjectService(
      ProjectRepository projects,
      CodeRepositoryRepository repositories,
      EventStore events,
      TimeSource time,
      JdbcClient jdbc,
      AgentDefaults agentDefaults) {
    this.projects = projects;
    this.repositories = repositories;
    this.events = events;
    this.time = time;
    this.jdbc = jdbc;
    this.agentDefaults = agentDefaults;
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
  public List<Project> list(Archived archived) {
    return projects.findAllByOrderByKeyAsc().stream()
        .filter(p -> archived.accepts(p.getArchivedAt()))
        .toList();
  }

  /**
   * Archiva el proyecto: sale de las listas junto con sus repositorios, trabajos y ejecuciones, y
   * no admite cambios hasta que se restaure. No se archiva con ejecuciones en curso.
   */
  @Transactional
  public ArchiveState archive(UUID id) {
    Project project = get(id);
    if (project.getArchivedAt() != null) {
      return new ArchiveState(id, project.getArchivedAt());
    }
    long active =
        jdbc.sql(
                "SELECT count(*) FROM workflow_run r JOIN work_item w ON w.id = r.work_item_id"
                    + " WHERE w.project_id = ? AND r.status IN ('PENDING', 'RUNNING')")
            .param(id)
            .query(Long.class)
            .single();
    if (active > 0) {
      throw new ConflictException(
          "El proyecto "
              + project.getKey()
              + " tiene ejecuciones en curso: cancélalas o espera a que terminen antes de"
              + " archivarlo");
    }
    Instant now = time.now();
    Archiving.set(jdbc, "project", id, now);
    events.append(
        EventDraft.of(
            "project", id, "project.archived", null, Map.of("key", project.getKey()), now));
    return new ArchiveState(id, now);
  }

  @Transactional
  public ArchiveState restore(UUID id) {
    Project project = get(id);
    if (project.getArchivedAt() == null) {
      return new ArchiveState(id, null);
    }
    Instant now = time.now();
    Archiving.set(jdbc, "project", id, null);
    events.append(
        EventDraft.of(
            "project", id, "project.restored", null, Map.of("key", project.getKey()), now));
    return new ArchiveState(id, null);
  }

  /** Falla si el proyecto está archivado: lo archivado es de solo lectura. */
  public void requireActive(Project project) {
    if (project.getArchivedAt() != null) {
      throw new ConflictException(
          "El proyecto " + project.getKey() + " está archivado: restáuralo para cambiarlo");
    }
  }

  /**
   * Archiva el repositorio: deja de ofrecerse para lanzar agentes. No se archiva mientras algún
   * agente lo esté usando.
   */
  @Transactional
  public ArchiveState archiveRepository(UUID projectId, UUID repositoryId) {
    CodeRepository repository = repositoryOf(projectId, repositoryId);
    if (repository.getArchivedAt() != null) {
      return new ArchiveState(repositoryId, repository.getArchivedAt());
    }
    long active =
        jdbc.sql(
                "SELECT count(*) FROM agent_run a JOIN stage_run s ON s.id = a.stage_run_id"
                    + " JOIN workflow_run r ON r.id = s.workflow_run_id"
                    + " WHERE a.repository_id = ? AND r.status IN ('PENDING', 'RUNNING')")
            .param(repositoryId)
            .query(Long.class)
            .single();
    if (active > 0) {
      throw new ConflictException(
          "El repositorio "
              + repository.getName()
              + " tiene ejecuciones en curso: espera a que terminen antes de archivarlo");
    }
    Instant now = time.now();
    Archiving.set(jdbc, "repository", repositoryId, now);
    events.append(
        EventDraft.of(
            "repository",
            repositoryId,
            "repository.archived",
            null,
            Map.of("projectId", projectId, "name", repository.getName()),
            now));
    return new ArchiveState(repositoryId, now);
  }

  @Transactional
  public ArchiveState restoreRepository(UUID projectId, UUID repositoryId) {
    CodeRepository repository = repositoryOf(projectId, repositoryId);
    if (repository.getArchivedAt() == null) {
      return new ArchiveState(repositoryId, null);
    }
    requireActive(get(projectId));
    Archiving.set(jdbc, "repository", repositoryId, null);
    events.append(
        EventDraft.of(
            "repository",
            repositoryId,
            "repository.restored",
            null,
            Map.of("projectId", projectId, "name", repository.getName()),
            time.now()));
    return new ArchiveState(repositoryId, null);
  }

  /** Falla si el repositorio o su proyecto están archivados. */
  public void requireActive(CodeRepository repository) {
    requireActive(get(repository.getProjectId()));
    if (repository.getArchivedAt() != null) {
      throw new ConflictException(
          "El repositorio " + repository.getName() + " está archivado: restáuralo para usarlo");
    }
  }

  @Transactional(readOnly = true)
  public Project get(UUID id) {
    return projects.findById(id).orElseThrow(() -> new NotFoundException("Proyecto", id));
  }

  @Transactional
  public CodeRepository registerRepository(
      UUID projectId,
      String name,
      String localPath,
      String remoteUrl,
      String defaultBranch,
      String validationCommand,
      List<String> testReportPaths) {
    requireActive(get(projectId));
    Instant now = time.now();
    CodeRepository created =
        CodeRepository.create(projectId, name, localPath, remoteUrl, defaultBranch, now);
    created.configureVerification(validationCommand, testReportPaths);
    CodeRepository repository = repositories.save(created);
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

  /** Cambia el comando de validación del repositorio y dónde deja sus informes JUnit. */
  @Transactional
  public CodeRepository configureVerification(
      UUID projectId, UUID repositoryId, String command, List<String> reportPaths) {
    CodeRepository repository = editableRepository(projectId, repositoryId);
    repository.configureVerification(command, reportPaths);
    repository = repositories.save(repository);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("validationCommand", repository.getValidationCommand());
    payload.put("testReportPaths", repository.getTestReportPaths());
    events.append(
        EventDraft.of(
            "repository",
            repository.getId(),
            "repository.verification.configured",
            null,
            payload,
            time.now()));
    return repository;
  }

  /** Política efectiva de los agentes del repositorio: la suya o, si no tiene, la global. */
  public AgentPolicy agentPolicy(CodeRepository repository) {
    return repository.customAgentPolicy().orElseGet(() -> AgentPolicy.of(agentDefaults));
  }

  /** Da al repositorio una política propia para sus agentes. */
  @Transactional
  public CodeRepository configureAgentPolicy(
      UUID projectId, UUID repositoryId, AgentPolicy policy) {
    CodeRepository repository = editableRepository(projectId, repositoryId);
    repository.configureAgentPolicy(policy);
    repository = repositories.save(repository);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("allowedTools", policy.allowedTools());
    payload.put("permissionMode", policy.permissionMode());
    payload.put("environment", policy.environment());
    payload.put("maxTurns", policy.maxTurns());
    payload.put("maxBudgetUsd", policy.maxBudgetUsd());
    payload.put("timeoutMinutes", policy.timeoutMinutes());
    events.append(
        EventDraft.of(
            "repository",
            repository.getId(),
            "repository.agent-policy.configured",
            null,
            payload,
            time.now()));
    return repository;
  }

  /** El repositorio vuelve a la política global de los agentes. */
  @Transactional
  public CodeRepository inheritAgentPolicy(UUID projectId, UUID repositoryId) {
    CodeRepository repository = editableRepository(projectId, repositoryId);
    repository.inheritAgentPolicy();
    repository = repositories.save(repository);
    events.append(
        EventDraft.of(
            "repository",
            repository.getId(),
            "repository.agent-policy.reset",
            null,
            Map.of(),
            time.now()));
    return repository;
  }

  private CodeRepository repositoryOf(UUID projectId, UUID repositoryId) {
    CodeRepository repository = getRepository(repositoryId);
    if (!repository.getProjectId().equals(projectId)) {
      throw new NotFoundException("Repositorio", repositoryId);
    }
    return repository;
  }

  /** El repositorio, si se puede cambiar: ni él ni su proyecto están archivados. */
  private CodeRepository editableRepository(UUID projectId, UUID repositoryId) {
    CodeRepository repository = repositoryOf(projectId, repositoryId);
    requireActive(repository);
    return repository;
  }

  @Transactional(readOnly = true)
  public List<CodeRepository> repositories(UUID projectId, Archived archived) {
    get(projectId);
    return repositories.findByProjectIdOrderByNameAsc(projectId).stream()
        .filter(r -> archived.accepts(r.getArchivedAt()))
        .toList();
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
