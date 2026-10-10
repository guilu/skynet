package dev.skynet.controlplane.definition;

import java.util.UUID;

/**
 * Una versión publicada, ya leída: la que se lanza y la que sigue el motor.
 *
 * @param id id de la versión, el que guarda cada ejecución
 */
public record PublishedDefinition(
    UUID id, String key, int version, WorkflowDefinition definition) {}
