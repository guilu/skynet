package dev.skynet.protocol;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
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
          Capability.RESUME, Capability.FORK, Capability.STRUCTURED_OUTPUT, Capability.MAX_TURNS)),
  /** Codex de OpenAI ({@code codex exec --json}); no limita los turnos. */
  CODEX(
      "codex",
      "Codex",
      EnumSet.of(Capability.RESUME, Capability.FORK, Capability.STRUCTURED_OUTPUT)),
  /**
   * Antigravity CLI de Google ({@code agy -p --output-format stream-json}): reanuda pero no
   * bifurca; la salida estructurada la valida el propio CLI ({@code --json-schema}).
   */
  ANTIGRAVITY(
      "antigravity",
      "Antigravity CLI",
      EnumSet.of(Capability.RESUME, Capability.STRUCTURED_OUTPUT)),
  /**
   * Gemini CLI de Google, retirado en AR-B: Antigravity CLI lo sustituye. Solo sigue aquí para leer
   * el histórico: no se lanza, no se continúa y no lo declara ningún runner.
   */
  GEMINI("gemini", "Gemini CLI", EnumSet.noneOf(Capability.class), true);

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
  private final boolean retired;

  AgentProviderKind(String id, String displayName, Set<Capability> capabilities) {
    this(id, displayName, capabilities, false);
  }

  AgentProviderKind(String id, String displayName, Set<Capability> capabilities, boolean retired) {
    this.id = id;
    this.displayName = displayName;
    this.capabilities = Set.copyOf(capabilities);
    this.retired = retired;
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

  /** Retirado: solo se conserva para el histórico, no se puede lanzar ni continuar. */
  public boolean retired() {
    return retired;
  }

  /** Los proveedores que se pueden lanzar, sin los retirados. */
  public static List<AgentProviderKind> active() {
    return Arrays.stream(values()).filter(k -> !k.retired).toList();
  }

  /** Proveedor con ese identificador, si Skynet lo conoce. */
  public static Optional<AgentProviderKind> byId(String id) {
    return Arrays.stream(values()).filter(k -> k.id.equals(id)).findFirst();
  }
}
