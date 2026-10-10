package dev.skynet.controlplane.definition;

/**
 * Resultado de validar un YAML sin guardarlo.
 *
 * @param key clave leída, o {@code null}
 * @param definition el workflow leído, o {@code null} si no tiene la forma básica
 */
public record ValidationView(String key, Validation validation, WorkflowDefinition definition) {}
