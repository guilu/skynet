package dev.skynet.controlplane.memory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Una observación en su versión actual.
 *
 * @param repositoryId repositorio, o {@code null} si es de todo el proyecto
 * @param origin quién escribió la versión actual
 * @param redacted si se enmascaró algún secreto al guardarla
 */
public record MemoryObservation(
    UUID id,
    UUID projectId,
    UUID repositoryId,
    MemoryKind kind,
    String title,
    String topicKey,
    String what,
    String why,
    String where,
    String learned,
    List<String> tags,
    MemoryOrigin origin,
    UUID sourceWorkflowRunId,
    UUID sourceAgentRunId,
    boolean pinned,
    boolean redacted,
    int version,
    Instant createdAt,
    Instant updatedAt,
    String updatedBy,
    Instant archivedAt) {}
