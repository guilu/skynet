package dev.skynet.protocol;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Proveedores de agentes que Skynet sabe ejecutar y lo que admite cada uno. Lo comparten el runner
 * (que elige el adaptador) y el control plane (que reparte el trabajo y rechaza lo que el proveedor
 * no admite).
 */
public enum AgentProviderKind {
  /** Claude Code ({@code claude -p --output-format stream-json}). */
  CLAUDE_CODE(
      "claude-code",
      "Claude Code",
      EnumSet.of(
          Capability.RESUME, Capability.FORK, Capability.STRUCTURED_OUTPUT, Capability.MAX_TURNS));

  /** Lo que un proveedor puede hacer además de arrancar una sesión. */
  public enum Capability {
    /** Reanudar la sesión con un mensaje nuevo. */
    RESUME,
    /** Bifurcar la sesión en un worktree nuevo. */
    FORK,
    /** Devolver una salida estructurada que cumple un JSON Schema ({@code output}). */
    STRUCTURED_OUTPUT,
    /** Limitar el número de turnos ({@code maxTurns}). */
    MAX_TURNS
  }

  private final String id;
  private final String displayName;
  private final Set<Capability> capabilities;

  AgentProviderKind(String id, String displayName, Set<Capability> capabilities) {
    this.id = id;
    this.displayName = displayName;
    this.capabilities = Set.copyOf(capabilities);
  }

  /** Identificador en el YAML, en {@code agent_run.provider} y en el protocolo. */
  public String id() {
    return id;
  }

  /** Nombre para mostrar (p. ej. {@code Claude Code}). */
  public String displayName() {
    return displayName;
  }

  public boolean supports(Capability capability) {
    return capabilities.contains(capability);
  }

  public Set<Capability> capabilities() {
    return capabilities;
  }

  /** Proveedor con ese identificador, si Skynet lo conoce. */
  public static Optional<AgentProviderKind> byId(String id) {
    return Arrays.stream(values()).filter(k -> k.id.equals(id)).findFirst();
  }
}
