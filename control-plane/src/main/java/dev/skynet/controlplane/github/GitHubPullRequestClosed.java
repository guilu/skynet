package dev.skynet.controlplane.github;

import java.util.UUID;

/**
 * Un webhook {@code pull_request.closed} de la PR {@code number} de un repositorio de Skynet: se ha
 * fusionado ({@code merged}) o cerrado sin fusionar. Si la abrió una ejecución que sigue en marcha,
 * su fase {@code github-merge} cuenta como hecha, o la ejecución se cancela (EN-C). Con el nombre,
 * el título, la descripción y el enlace, una PR de revert es un escape del juez (EV-A).
 */
public record GitHubPullRequestClosed(
    UUID repositoryId,
    long number,
    boolean merged,
    String mergeSha,
    String fullName,
    String title,
    String body,
    String url) {

  public GitHubPullRequestClosed(UUID repositoryId, long number, boolean merged, String mergeSha) {
    this(repositoryId, number, merged, mergeSha, null, null, null, null);
  }
}
