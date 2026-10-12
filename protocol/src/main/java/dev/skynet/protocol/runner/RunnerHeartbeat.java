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
 */
public record RunnerHeartbeat(
    int capacity, List<UUID> runningAgentRunIds, List<ProviderInfo> providers) {

  public RunnerHeartbeat {
    runningAgentRunIds = runningAgentRunIds == null ? List.of() : List.copyOf(runningAgentRunIds);
    providers = providers == null ? null : List.copyOf(providers);
  }

  /** Latido que no declara proveedores. */
  public RunnerHeartbeat(int capacity, List<UUID> runningAgentRunIds) {
    this(capacity, runningAgentRunIds, null);
  }
}
