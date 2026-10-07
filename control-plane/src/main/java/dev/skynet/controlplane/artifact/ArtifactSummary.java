package dev.skynet.controlplane.artifact;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Artefacto listado sin su contenido (ADR-0001 §5): lo pesado se carga bajo demanda con {@code GET
 * /api/artifacts/{id}/content}.
 *
 * @param verificationRunId verificación que lo produjo, o {@code null} si es de la invocación
 * @param truncated se recortó por superar el tamaño máximo
 * @param metadata datos que añadió el runner (p. ej. archivos y líneas cambiadas)
 */
public record ArtifactSummary(
    UUID id,
    UUID agentRunId,
    UUID verificationRunId,
    String type,
    String name,
    String mediaType,
    long size,
    String sha256,
    boolean truncated,
    Map<String, Object> metadata,
    Instant createdAt) {}
