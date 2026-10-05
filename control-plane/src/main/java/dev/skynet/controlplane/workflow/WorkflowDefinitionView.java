package dev.skynet.controlplane.workflow;

import java.time.Instant;
import java.util.UUID;

/** Definición de workflow versionada e inmutable, en lectura. */
public record WorkflowDefinitionView(
    UUID id, String key, int version, String sourceYaml, Instant createdAt) {}
