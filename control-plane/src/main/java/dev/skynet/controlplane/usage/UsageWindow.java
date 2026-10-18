package dev.skynet.controlplane.usage;

import java.time.Instant;

/**
 * La última muestra de una ventana de la cuota de un CLI.
 *
 * @param providerName nombre para mostrar del CLI
 * @param bucket grupo de modelos con su propia cuota ({@code default} si el CLI solo tiene uno)
 * @param label nombre del grupo (p. ej. «Gemini Models»), o {@code null}
 * @param window {@code five_hour} o {@code seven_day}
 * @param usedFraction parte gastada, de 0 a 1
 * @param resetsAt cuándo se renueva, o {@code null} si el CLI no lo dice
 * @param source {@code invocation} (la vio un agente) o {@code poll} (la consultó el runner)
 * @param stale si el dato tiene más de 5 h: puede no parecerse a la cuota de ahora
 * @param pace ritmo uniforme: dónde estaría gastando igual toda la ventana, de 0 a 1, o {@code
 *     null} si no se sabe cuándo se renueva o ya se ha renovado
 * @param skynet de lo gastado en la ventana, lo que se atribuye a agentes de Skynet; el resto es de
 *     fuera
 */
public record UsageWindow(
    String provider,
    String providerName,
    String bucket,
    String label,
    String window,
    double usedFraction,
    Instant resetsAt,
    String plan,
    String source,
    Instant observedAt,
    boolean stale,
    Double pace,
    double skynet) {}
