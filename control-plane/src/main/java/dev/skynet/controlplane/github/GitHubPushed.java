package dev.skynet.controlplane.github;

import java.util.List;
import java.util.UUID;

/**
 * Un webhook {@code push} a la rama por defecto de un repositorio de Skynet (EV-A), con sus
 * commits: un {@code git revert} de una PR que fusionó Skynet es un escape del juez.
 */
public record GitHubPushed(UUID repositoryId, List<Commit> commits) {

  public GitHubPushed {
    commits = List.copyOf(commits);
  }

  /** Un commit del push: su SHA, su mensaje y su enlace. */
  public record Commit(String sha, String message, String url) {}
}
