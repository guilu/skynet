package dev.skynet.protocol.runner;

import java.util.UUID;

/**
 * Respuesta a la subida de un artefacto.
 *
 * @param duplicate {@code true} si ya estaba guardado y no se ha vuelto a guardar
 */
public record ArtifactStored(UUID id, boolean duplicate) {}
