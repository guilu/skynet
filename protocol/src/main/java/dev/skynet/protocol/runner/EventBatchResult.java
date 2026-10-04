package dev.skynet.protocol.runner;

import java.util.List;
import java.util.UUID;

/**
 * Respuesta a un {@link EventBatch}. El runner puede borrar de su journal todos los eventos del
 * lote: los aceptados, los duplicados y los rechazados (que nunca se aceptarán al reenviarlos).
 *
 * @param accepted eventos registrados por primera vez
 * @param duplicates eventos que ya estaban registrados
 * @param rejected eventos descartados, con el motivo
 */
public record EventBatchResult(int accepted, int duplicates, List<Rejected> rejected) {

  public EventBatchResult {
    rejected = rejected == null ? List.of() : List.copyOf(rejected);
  }

  /** Evento que el control plane no acepta, p. ej. de una invocación de otro runner. */
  public record Rejected(UUID eventId, String reason) {}
}
