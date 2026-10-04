package dev.skynet.controlplane.event;

import java.util.UUID;

/** Filtro opcional por ejecución de workflow y/o agregado. {@code null} significa "cualquiera". */
public record EventFilter(UUID workflowRunId, UUID aggregateId) {

  public static final EventFilter ALL = new EventFilter(null, null);

  public boolean matches(StoredEvent event) {
    return (workflowRunId == null || workflowRunId.equals(event.workflowRunId()))
        && (aggregateId == null || aggregateId.equals(event.aggregateId()));
  }
}
