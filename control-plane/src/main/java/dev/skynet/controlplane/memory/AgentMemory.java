package dev.skynet.controlplane.memory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Lo que una invocación de agente hizo con la memoria (ME-D): con qué modo la usó y qué
 * observaciones recibió, buscó, leyó o guardó, cada una en la versión que vio, y qué páginas de la
 * wiki recibió o leyó (MV-D).
 *
 * @param mode {@code READ}, {@code WRITE}, o {@code null} si la invocación no usó la memoria
 */
public record AgentMemory(
    UUID agentRunId, UUID projectId, String mode, List<Access> accesses, List<WikiAccess> wiki) {

  /**
   * Una página de la wiki que recibió ({@code PROMPT} o {@code CONTEXT}) o leyó ({@code GET}), en
   * la versión que vio (MV-D).
   */
  public record WikiAccess(
      UUID pageId,
      String slug,
      String title,
      UUID repositoryId,
      int version,
      String via,
      int times,
      Instant firstAt) {}

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
