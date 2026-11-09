package dev.skynet.controlplane.artifact;

import java.util.Map;
import java.util.UUID;

/**
 * Se ha guardado un artefacto nuevo de una invocación. Se publica en la transacción que lo guarda,
 * para que otros módulos lean sus metadatos (p. ej. el nivel de riesgo de {@code GIT_CHANGES},
 * AU-A) sin descargarlo.
 *
 * @param verificationRunId la verificación de la que sale, o {@code null} si es del agente
 * @param type el tipo ({@code GIT_CHANGES}, {@code DIFF}…)
 * @param metadata los metadatos que mandó el runner
 */
public record ArtifactCreated(
    UUID artifactId,
    UUID agentRunId,
    UUID verificationRunId,
    String type,
    Map<String, Object> metadata) {}
