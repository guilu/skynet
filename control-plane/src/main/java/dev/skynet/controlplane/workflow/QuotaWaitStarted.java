package dev.skynet.controlplane.workflow;

import java.time.Instant;
import java.util.UUID;

/**
 * Una fase empieza a esperar a que se renueve la cuota de su proveedor (CQ-A). Se publica dentro de
 * la transacción del motor; lo escuchan, p. ej., los avisos en GitHub de los disparadores.
 *
 * @param window la ventana sin cuota ({@code five_hour} o {@code seven_day}), o {@code null}
 * @param resetsAt cuándo se renueva, o {@code null} si no se sabe
 * @param notBefore cuándo sigue la fase
 */
public record QuotaWaitStarted(
    UUID workflowRunId,
    String stageKey,
    String provider,
    String window,
    Instant resetsAt,
    Instant notBefore) {}
