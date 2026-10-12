package dev.skynet.controlplane.runner;

/**
 * Agentes en cola de un proveedor que no tiene ningún runner en línea: no arrancarán hasta que se
 * conecte uno que lo tenga.
 *
 * @param provider id del proveedor (p. ej. {@code codex})
 * @param displayName nombre para mostrar (p. ej. {@code Codex})
 * @param queuedAgents agentes que esperan
 */
public record ProviderShortage(String provider, String displayName, int queuedAgents) {}
