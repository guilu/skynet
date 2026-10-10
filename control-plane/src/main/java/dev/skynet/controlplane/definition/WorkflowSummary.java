package dev.skynet.controlplane.definition;

import java.time.Instant;
import java.util.UUID;

/**
 * Un workflow en la lista: su última versión publicada y su borrador, si lo tiene. El nombre y la
 * descripción son los de la versión publicada o, sin ninguna, los del borrador.
 *
 * @param published última versión publicada, o {@code null}
 * @param draft borrador en curso, o {@code null}
 */
public record WorkflowSummary(
    UUID id,
    String key,
    String name,
    String description,
    VersionView published,
    VersionView draft,
    Instant createdAt,
    Instant archivedAt) {}
