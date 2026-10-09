package dev.skynet.controlplane.shared;

import java.time.Instant;
import java.util.UUID;

/** Respuesta de archivar o restaurar: {@code archivedAt} es {@code null} si no está archivado. */
public record ArchiveState(UUID id, Instant archivedAt) {}
