package dev.skynet.controlplane.definition;

import java.time.Instant;
import java.util.UUID;

/**
 * Una versión con su YAML, su validación y el workflow leído.
 *
 * @param revision lo que hay que enviar al guardar o publicar un borrador: si otro lo cambió
 *     entretanto, la petición se rechaza
 * @param definition el workflow leído, o {@code null} si el YAML no tiene la forma básica
 * @param archivedAt si el workflow está archivado, desde cuándo
 */
public record DefinitionDetail(
    UUID id,
    UUID workflowId,
    String key,
    int version,
    DefinitionStatus status,
    String sourceYaml,
    long revision,
    Instant createdAt,
    Instant updatedAt,
    Instant publishedAt,
    Instant archivedAt,
    Validation validation,
    WorkflowDefinition definition) {}
