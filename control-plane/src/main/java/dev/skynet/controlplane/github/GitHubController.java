package dev.skynet.controlplane.github;

import dev.skynet.controlplane.shared.ConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** La conexión con GitHub, para Ajustes → GitHub. */
@RestController
class GitHubController {

  private final GitHubProperties properties;
  private final GitHubInstallations installations;

  GitHubController(GitHubProperties properties, GitHubInstallations installations) {
    this.properties = properties;
    this.installations = installations;
  }

  /** La App, sus instalaciones y el enlace de cada repositorio con GitHub. */
  @GetMapping("/api/github")
  GitHubStatus status() {
    return installations.status();
  }

  /**
   * Vuelve a pedir a GitHub las instalaciones y sus repositorios. 409 si la App no está
   * configurada; 502 si GitHub falla.
   */
  @PostMapping("/api/github/sync")
  GitHubStatus sync() {
    if (!properties.configured()) {
      throw new ConflictException(
          "La GitHub App no está configurada: falta " + String.join(", ", properties.missing()));
    }
    return installations.sync();
  }

  @ExceptionHandler(GitHubException.class)
  ProblemDetail gitHubFailed(GitHubException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.getMessage());
  }
}
