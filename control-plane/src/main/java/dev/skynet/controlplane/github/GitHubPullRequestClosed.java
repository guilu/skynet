package dev.skynet.controlplane.github;

import java.util.UUID;

/**
 * Un webhook {@code pull_request.closed} de la PR {@code number} de un repositorio de Skynet: se ha
 * fusionado ({@code merged}) o cerrado sin fusionar. Si la abrió una ejecución que sigue en marcha,
 * su fase {@code github-merge} cuenta como hecha, o la ejecución se cancela (EN-C).
 */
public record GitHubPullRequestClosed(
    UUID repositoryId, long number, boolean merged, String mergeSha) {}
