package dev.skynet.controlplane.workspace;

import java.time.Instant;
import java.util.UUID;

/**
 * Un cambio producido por la fábrica (FS-B): una PR, con su repositorio, su trabajo y su intento.
 */
public record ChangeView(
    UUID id,
    Integer number,
    String url,
    String title,
    String status,
    String branch,
    UUID repositoryId,
    String repositoryName,
    UUID projectId,
    String projectKey,
    String projectName,
    UUID workItemId,
    String workItemKey,
    String workItemTitle,
    UUID workflowRunId,
    Instant createdAt,
    Instant updatedAt) {}
