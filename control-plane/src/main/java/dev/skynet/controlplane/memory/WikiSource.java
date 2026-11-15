package dev.skynet.controlplane.memory;

import java.util.UUID;

/**
 * De dónde sale lo que dice una página de la wiki.
 *
 * @param kind {@code pull_request}, {@code finding}, {@code lesson}, {@code memory} o {@code
 *     escape}
 * @param id la fila de la que sale
 * @param label cómo se nombra: «PR #40», «AKA-1», el título de la observación…
 * @param url el enlace, si lo hay
 */
public record WikiSource(String kind, UUID id, String label, String url) {}
