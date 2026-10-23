package dev.skynet.controlplane.memory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Una versión de una observación, tal como quedó al guardarla. */
public record MemoryRevision(
    UUID observationId,
    int version,
    MemoryKind kind,
    String title,
    String topicKey,
    String what,
    String why,
    String where,
    String learned,
    List<String> tags,
    MemoryOrigin origin,
    UUID sourceAgentRunId,
    String changedBy,
    Instant changedAt) {}
