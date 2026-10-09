package dev.skynet.controlplane.project;

import dev.skynet.controlplane.shared.ArchiveState;
import dev.skynet.controlplane.shared.Archived;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects")
class ProjectController {

  private final ProjectService service;

  ProjectController(ProjectService service) {
    this.service = service;
  }

  record CreateProject(
      @NotBlank
          @Pattern(
              regexp = "^[A-Z][A-Z0-9]{1,9}$",
              message = "2-10 caracteres: mayúsculas y dígitos, empezando por letra")
          String key,
      @NotBlank @Size(max = 200) String name,
      @Size(max = 2000) String description) {}

  record ProjectView(
      UUID id, String key, String name, String description, Instant createdAt, Instant archivedAt) {
    static ProjectView of(Project p) {
      return new ProjectView(
          p.getId(),
          p.getKey(),
          p.getName(),
          p.getDescription(),
          p.getCreatedAt(),
          p.getArchivedAt());
    }
  }

  record RegisterRepository(
      @NotBlank @Size(max = 200) String name,
      @NotBlank @Pattern(regexp = "^/.*", message = "debe ser una ruta absoluta") String localPath,
      @Size(max = 500) String remoteUrl,
      @Size(max = 200) String defaultBranch,
      @Size(max = 2000) String validationCommand,
      List<@Size(max = 500) String> testReportPaths) {}

  /**
   * Verificación del repositorio. Sin {@code validationCommand} no se verifica; sin {@code
   * testReportPaths} se buscan los informes JUnit de Gradle y Maven.
   */
  record VerificationSettings(
      @Size(max = 2000) String validationCommand, List<@Size(max = 500) String> testReportPaths) {}

  /**
   * Política propia de los agentes del repositorio. Sin {@code environment}, el agente recibe todas
   * las variables que permite el runner; sin {@code maxTurns}, no hay límite de turnos.
   */
  record AgentPolicySettings(
      @NotNull @Size(max = 50)
          List<
                  @NotBlank @Size(max = 200)
                  @Pattern(
                      regexp = TOOL,
                      message =
                          "herramienta no válida: un nombre y, opcionalmente, un patrón entre"
                              + " paréntesis sin comas, como Bash(git:*)")
                  String>
              allowedTools,
      @NotBlank
          @Pattern(
              regexp = "dontAsk|acceptEdits|default|plan",
              message = "debe ser dontAsk, acceptEdits, default o plan")
          String permissionMode,
      @Size(max = 50)
          List<
                  @NotBlank @Pattern(regexp = ENV_NAME, message = "nombre de variable no válido")
                  String>
              environment,
      @Positive @Max(1_000) Integer maxTurns,
      @NotNull @Positive @DecimalMax("1000") @Digits(integer = 4, fraction = 2)
          BigDecimal maxBudgetUsd,
      @NotNull @Positive @Max(24 * 60) Integer timeoutMinutes) {

    AgentPolicy policy() {
      return new AgentPolicy(
          allowedTools.stream().map(String::strip).distinct().toList(),
          permissionMode,
          environment == null ? null : environment.stream().map(String::strip).distinct().toList(),
          maxTurns,
          maxBudgetUsd,
          timeoutMinutes);
    }
  }

  /** Nombre de herramienta con un patrón opcional; las comas separan herramientas en el CLI. */
  static final String TOOL = "^[A-Za-z][A-Za-z0-9_]*(\\([^(),\\n]*\\))?$";

  static final String ENV_NAME = "^[A-Za-z_][A-Za-z0-9_]*$";

  /**
   * @param agentPolicy política efectiva de los agentes: la propia o, sin ella, la global
   * @param agentPolicyCustom si el repositorio tiene política propia
   */
  record RepositoryView(
      UUID id,
      UUID projectId,
      String name,
      String localPath,
      String remoteUrl,
      String defaultBranch,
      String validationCommand,
      List<String> testReportPaths,
      AgentPolicy agentPolicy,
      boolean agentPolicyCustom,
      Instant createdAt,
      Instant archivedAt) {
    static RepositoryView of(CodeRepository r, AgentPolicy policy) {
      return new RepositoryView(
          r.getId(),
          r.getProjectId(),
          r.getName(),
          r.getLocalPath(),
          r.getRemoteUrl(),
          r.getDefaultBranch(),
          r.getValidationCommand(),
          r.getTestReportPaths(),
          policy,
          r.hasCustomAgentPolicy(),
          r.getCreatedAt(),
          r.getArchivedAt());
    }
  }

  private RepositoryView view(CodeRepository repository) {
    return RepositoryView.of(repository, service.agentPolicy(repository));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  ProjectView create(@Valid @RequestBody CreateProject request) {
    return ProjectView.of(service.create(request.key(), request.name(), request.description()));
  }

  @GetMapping
  List<ProjectView> list(@RequestParam(defaultValue = "false") String archived) {
    return service.list(Archived.of(archived)).stream().map(ProjectView::of).toList();
  }

  @GetMapping("/{id}")
  ProjectView get(@PathVariable UUID id) {
    return ProjectView.of(service.get(id));
  }

  @PostMapping("/{id}/archive")
  ArchiveState archive(@PathVariable UUID id) {
    return service.archive(id);
  }

  @PostMapping("/{id}/restore")
  ArchiveState restore(@PathVariable UUID id) {
    return service.restore(id);
  }

  @PostMapping("/{id}/repositories")
  @ResponseStatus(HttpStatus.CREATED)
  RepositoryView registerRepository(
      @PathVariable UUID id, @Valid @RequestBody RegisterRepository request) {
    String branch =
        request.defaultBranch() == null || request.defaultBranch().isBlank()
            ? "main"
            : request.defaultBranch();
    return view(
        service.registerRepository(
            id,
            request.name(),
            request.localPath(),
            request.remoteUrl(),
            branch,
            request.validationCommand(),
            request.testReportPaths()));
  }

  @PutMapping("/{id}/repositories/{repositoryId}/verification")
  RepositoryView configureVerification(
      @PathVariable UUID id,
      @PathVariable UUID repositoryId,
      @Valid @RequestBody VerificationSettings request) {
    return view(
        service.configureVerification(
            id, repositoryId, request.validationCommand(), request.testReportPaths()));
  }

  @PutMapping("/{id}/repositories/{repositoryId}/agent-policy")
  RepositoryView configureAgentPolicy(
      @PathVariable UUID id,
      @PathVariable UUID repositoryId,
      @Valid @RequestBody AgentPolicySettings request) {
    return view(service.configureAgentPolicy(id, repositoryId, request.policy()));
  }

  /** El repositorio vuelve a la política global de los agentes. */
  @DeleteMapping("/{id}/repositories/{repositoryId}/agent-policy")
  RepositoryView inheritAgentPolicy(@PathVariable UUID id, @PathVariable UUID repositoryId) {
    return view(service.inheritAgentPolicy(id, repositoryId));
  }

  @GetMapping("/{id}/repositories")
  List<RepositoryView> repositories(
      @PathVariable UUID id, @RequestParam(defaultValue = "false") String archived) {
    return service.repositories(id, Archived.of(archived)).stream().map(this::view).toList();
  }

  @PostMapping("/{id}/repositories/{repositoryId}/archive")
  ArchiveState archiveRepository(@PathVariable UUID id, @PathVariable UUID repositoryId) {
    return service.archiveRepository(id, repositoryId);
  }

  @PostMapping("/{id}/repositories/{repositoryId}/restore")
  ArchiveState restoreRepository(@PathVariable UUID id, @PathVariable UUID repositoryId) {
    return service.restoreRepository(id, repositoryId);
  }
}
