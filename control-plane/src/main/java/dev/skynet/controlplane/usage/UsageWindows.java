package dev.skynet.controlplane.usage;

import java.util.List;
import java.util.UUID;

/** Las cuotas de cada runner ({@code GET /api/usage/windows}). */
public record UsageWindows(List<Runner> runners) {

  /** Un runner y la última muestra de cada ventana de sus CLIs, por CLI, grupo y ventana. */
  public record Runner(UUID id, String name, List<UsageWindow> windows) {}
}
