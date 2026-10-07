package dev.skynet.protocol.runner;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Metadatos de un artefacto que sube el runner ({@code POST /api/runner/artifacts}): van en la
 * cabecera {@link #HEADER} como JSON en base64url, y el contenido en el cuerpo ({@code
 * application/octet-stream}). Un artefacto se identifica por (invocación, verificación, tipo,
 * nombre): reenviarlo no lo duplica.
 *
 * @param verificationRunId verificación que lo produjo, o {@code null} si es de la invocación
 * @param mediaType tipo MIME del contenido (p. ej. {@code text/x-diff}, {@code application/json})
 * @param sha256 hash del contenido enviado, en hexadecimal
 * @param metadata datos para listarlo sin descargarlo (p. ej. archivos y líneas cambiadas)
 */
public record ArtifactUpload(
    UUID agentRunId,
    UUID verificationRunId,
    ArtifactType type,
    String name,
    String mediaType,
    String sha256,
    Map<String, Object> metadata) {

  /** Cabecera con los metadatos de la subida. */
  public static final String HEADER = "X-Artifact-Metadata";

  public ArtifactUpload {
    Objects.requireNonNull(agentRunId, "agentRunId");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(mediaType, "mediaType");
    Objects.requireNonNull(sha256, "sha256");
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
  }
}
