package dev.skynet.controlplane.memory;

/**
 * Resultado de guardar.
 *
 * @param created {@code false} si el guardado fue una versión nueva de un tema que ya existía
 */
public record MemorySaved(MemoryObservation observation, boolean created) {}
