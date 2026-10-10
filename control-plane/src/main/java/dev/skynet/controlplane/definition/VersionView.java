package dev.skynet.controlplane.definition;

import java.time.Instant;
import java.util.UUID;

/** Una versión de un workflow, sin su YAML. */
public record VersionView(
    UUID id,
    int version,
    DefinitionStatus status,
    Instant createdAt,
    Instant updatedAt,
    Instant publishedAt) {}
