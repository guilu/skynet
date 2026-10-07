package dev.skynet.controlplane.project;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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

  record ProjectView(UUID id, String key, String name, String description, Instant createdAt) {
    static ProjectView of(Project p) {
      return new ProjectView(
          p.getId(), p.getKey(), p.getName(), p.getDescription(), p.getCreatedAt());
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

  record RepositoryView(
      UUID id,
      UUID projectId,
      String name,
      String localPath,
      String remoteUrl,
      String defaultBranch,
      String validationCommand,
      List<String> testReportPaths,
      Instant createdAt) {
    static RepositoryView of(CodeRepository r) {
      return new RepositoryView(
          r.getId(),
          r.getProjectId(),
          r.getName(),
          r.getLocalPath(),
          r.getRemoteUrl(),
          r.getDefaultBranch(),
          r.getValidationCommand(),
          r.getTestReportPaths(),
          r.getCreatedAt());
    }
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  ProjectView create(@Valid @RequestBody CreateProject request) {
    return ProjectView.of(service.create(request.key(), request.name(), request.description()));
  }

  @GetMapping
  List<ProjectView> list() {
    return service.list().stream().map(ProjectView::of).toList();
  }

  @GetMapping("/{id}")
  ProjectView get(@PathVariable UUID id) {
    return ProjectView.of(service.get(id));
  }

  @PostMapping("/{id}/repositories")
  @ResponseStatus(HttpStatus.CREATED)
  RepositoryView registerRepository(
      @PathVariable UUID id, @Valid @RequestBody RegisterRepository request) {
    String branch =
        request.defaultBranch() == null || request.defaultBranch().isBlank()
            ? "main"
            : request.defaultBranch();
    return RepositoryView.of(
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
    return RepositoryView.of(
        service.configureVerification(
            id, repositoryId, request.validationCommand(), request.testReportPaths()));
  }

  @GetMapping("/{id}/repositories")
  List<RepositoryView> repositories(@PathVariable UUID id) {
    return service.repositories(id).stream().map(RepositoryView::of).toList();
  }
}
