package dev.skynet.protocol.runner;

import java.util.List;
import java.util.UUID;

/**
 * Latido periódico de un runner ({@code POST /api/runner/heartbeat}).
 *
 * @param capacity capacidad total
 * @param runningAgentRunIds invocaciones que el runner sigue ejecutando o de las que aún tiene
 *     eventos sin enviar; el control plane da por perdidas las demás que crea en curso en este
 *     runner
 * @param providers proveedores que el runner tiene disponibles, o {@code null} si no los declara
 *     (se conservan los del registro). Un runner que reutiliza su token tras reiniciarse no se
 *     vuelve a registrar: así el control plane se entera de un CLI instalado o quitado.
 * @param quotas las cuotas que el runner ha consultado por su cuenta (Codex, Antigravity) desde el
 *     último latido que llegó; vacío si no hay nuevas. Las de Claude Code viajan con los eventos de
 *     cada invocación ({@code agent.quota}).
 */
public record RunnerHeartbeat(
    int capacity,
    List<UUID> runningAgentRunIds,
    List<ProviderInfo> providers,
    List<QuotaSample> quotas) {

  public RunnerHeartbeat {
    runningAgentRunIds = runningAgentRunIds == null ? List.of() : List.copyOf(runningAgentRunIds);
    providers = providers == null ? null : List.copyOf(providers);
    quotas = quotas == null ? List.of() : List.copyOf(quotas);
  }

  /** Latido sin cuotas nuevas. */
  public RunnerHeartbeat(
      int capacity, List<UUID> runningAgentRunIds, List<ProviderInfo> providers) {
    this(capacity, runningAgentRunIds, providers, null);
  }

  /** Latido que no declara proveedores. */
  public RunnerHeartbeat(int capacity, List<UUID> runningAgentRunIds) {
    this(capacity, runningAgentRunIds, null, null);
  }
}
