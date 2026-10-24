package dev.skynet.controlplane.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Memoria de proyecto.
 *
 * @param contextMaxChars lo más que ocupa el contexto que recibe un agente ({@code mem_context})
 * @param maxCalls llamadas a las herramientas de memoria por invocación
 * @param maxSaves guardados por invocación
 */
@ConfigurationProperties("skynet.memory")
record MemoryProperties(Integer contextMaxChars, Integer maxCalls, Integer maxSaves) {

  MemoryProperties {
    contextMaxChars = contextMaxChars == null ? 4_000 : contextMaxChars;
    maxCalls = maxCalls == null ? 500 : maxCalls;
    maxSaves = maxSaves == null ? 100 : maxSaves;
  }
}
