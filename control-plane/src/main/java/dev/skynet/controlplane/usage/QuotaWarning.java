package dev.skynet.controlplane.usage;

import java.time.Instant;
import java.util.UUID;

/**
 * Una ventana de cuota que pide atención (decisión 6 de CU): pasa del 90 % ({@code limit}) o, la
 * semanal, va 15 puntos por encima del ritmo uniforme ({@code pace}).
 *
 * @param pace dónde estaría gastando igual toda la ventana, de 0 a 1
 */
public record QuotaWarning(
    UUID runnerId,
    String runnerName,
    String provider,
    String providerName,
    String bucket,
    String label,
    String window,
    double usedFraction,
    Double pace,
    Instant resetsAt,
    String reason) {}
