package dev.skynet.controlplane.project;

import dev.skynet.controlplane.event.EventDraft;
import dev.skynet.controlplane.event.EventStore;
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
  public List<Project> list() {
    return projects.findAllByOrderByKeyAsc();
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
    get(projectId);
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
    CodeRepository repository = repositoryOf(projectId, repositoryId);
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
    CodeRepository repository = repositoryOf(projectId, repositoryId);
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
    CodeRepository repository = repositoryOf(projectId, repositoryId);
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
