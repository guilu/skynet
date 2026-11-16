package dev.skynet.controlplane.memory;

import java.time.Instant;
import java.util.UUID;

/**
 * Una invocación de agente que recibió, buscó, leyó o guardó una entrada (MV-F, «¿Quién lo vio?»),
 * en la versión que vio. Varios accesos iguales cuentan como uno, con {@code times}.
 *
 * @param via {@code PROMPT}, {@code CONTEXT}, {@code SEARCH}, {@code GET} o {@code SAVE}
 * @param stageKey la fase de la ejecución
 * @param workItemKey el trabajo de la ejecución, p. ej. {@code DEMO-3}
 */
public record MemoryReader(
    UUID agentRunId,
    UUID workflowRunId,
    String stageKey,
    String workItemKey,
    String provider,
    int version,
    String via,
    int times,
    Instant firstAt) {}
