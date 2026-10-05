package dev.skynet.controlplane.dashboard;

import dev.skynet.controlplane.runner.RunnerView;
import dev.skynet.controlplane.workflow.RunView;
import dev.skynet.controlplane.workflow.UnresponsiveAgent;
import java.time.Instant;
import java.util.List;

/**
 * Excepciones que requieren atención.
 *
 * @param activeRuns ejecuciones sin terminar, de la más reciente a la más antigua
 * @param activeRunsTotal total de ejecuciones sin terminar (la lista puede estar recortada)
 * @param recentFailures ejecuciones fallidas en las últimas 24 horas
 * @param unresponsiveAgents agentes que han dejado de dar señales de actividad
 * @param staleRunners runners sin latido reciente
 */
public record DashboardSummary(
    List<RunView> activeRuns,
    long activeRunsTotal,
    List<RunView> recentFailures,
    List<UnresponsiveAgent> unresponsiveAgents,
    List<RunnerView> staleRunners,
    Instant generatedAt) {}
