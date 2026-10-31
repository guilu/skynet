package dev.skynet.controlplane.github;

import java.util.UUID;

/**
 * Un webhook {@code check_run}, {@code check_suite} o {@code status} sobre el commit {@code sha} de
 * un repositorio de Skynet: la CI de ese commit ha cambiado. Quien espera a esa CI (una fase {@code
 * github-check}, EN-B) vuelve a preguntar a la API, que es quien decide.
 */
public record GitHubCommitChecked(UUID repositoryId, String sha) {}
