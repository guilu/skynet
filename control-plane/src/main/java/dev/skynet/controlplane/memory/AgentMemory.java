package dev.skynet.controlplane.memory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Lo que una invocación de agente hizo con la memoria (ME-D): con qué modo la usó y qué
 * observaciones recibió, buscó, leyó o guardó, cada una en la versión que vio.
 *
 * @param mode {@code READ}, {@code WRITE}, o {@code null} si la invocación no usó la memoria
 */
public record AgentMemory(UUID agentRunId, UUID projectId, String mode, List<Access> accesses) {

  /**
   * Un acceso a una observación, en la versión que vio. Varios iguales (la misma búsqueda dos
   * veces) cuentan como uno, con {@code times}.
   *
   * @param via {@code PROMPT}, {@code CONTEXT}, {@code SEARCH}, {@code GET} o {@code SAVE}
   * @param title el título de esa versión
   * @param archived si la observación está archivada ahora
   */
  public record Access(
      UUID observationId,
      int version,
      String via,
      MemoryKind kind,
      String title,
      UUID repositoryId,
      boolean archived,
      int times,
      Instant firstAt) {}
}
