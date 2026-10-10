package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.definition.AgentDefinition;
import dev.skynet.controlplane.project.AgentPolicy;
import dev.skynet.protocol.runner.AgentLimits;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Lo que se le permite a una invocación: lo que pide su agente del YAML, recortado a la política
 * del repositorio. Un agente nunca obtiene herramientas, un modo de permisos ni límites que el
 * repositorio no le dé.
 */
record EffectivePolicy(
    List<String> allowedTools,
    String permissionMode,
    List<String> environment,
    AgentLimits limits) {

  /** Del menos al más permisivo; {@code default} y {@code dontAsk} solo usan lo permitido. */
  private static final Map<String, Integer> PERMISSIVENESS =
      Map.of("plan", 0, "default", 1, "dontAsk", 1, "acceptEdits", 2);

  /** La política del repositorio tal cual, con los límites de la invocación. */
  static EffectivePolicy of(AgentPolicy policy, AgentLimits limits) {
    return new EffectivePolicy(
        policy.allowedTools(), policy.permissionMode(), policy.environment(), limits);
  }

  /**
   * Para un agente del YAML ({@code null}: la fase usa la política del repositorio): sus
   * herramientas que el repositorio permite, su modo de permisos si no es más permisivo que el del
   * repositorio, y sus límites rebajados a los del repositorio. Lo que no fija sale de la política
   * y, los límites, de los del lanzamiento ({@code launch}, ya dentro de la política).
   */
  static EffectivePolicy of(AgentPolicy policy, AgentDefinition agent, AgentLimits launch) {
    if (agent == null) {
      return of(policy, launch);
    }
    List<String> tools =
        agent.tools() == null
            ? policy.allowedTools()
            : agent.tools().stream().filter(t -> allows(policy.allowedTools(), t)).toList();
    String mode = policy.permissionMode();
    if (agent.permissionMode() != null
        && (mode == null || rank(agent.permissionMode()) <= rank(mode))) {
      mode = agent.permissionMode();
    }
    Duration agentTimeout =
        agent.timeoutMinutes() == null ? null : Duration.ofMinutes(agent.timeoutMinutes());
    AgentLimits limits =
        new AgentLimits(
            agent.maxTurns() == null ? launch.maxTurns() : min(agent.maxTurns(), policy.maxTurns()),
            agent.maxBudgetUsd() == null
                ? launch.maxBudgetUsd()
                : min(agent.maxBudgetUsd(), policy.maxBudgetUsd()),
            agentTimeout == null ? launch.timeout() : min(agentTimeout, policy.timeout()));
    return new EffectivePolicy(tools, mode, policy.environment(), limits);
  }

  /** {@code Bash(git:*)} cabe en {@code Bash}; {@code Bash} no cabe en {@code Bash(git:*)}. */
  private static boolean allows(List<String> allowed, String tool) {
    int paren = tool.indexOf('(');
    return allowed.contains(tool) || (paren > 0 && allowed.contains(tool.substring(0, paren)));
  }

  private static int rank(String mode) {
    return PERMISSIVENESS.getOrDefault(mode, Integer.MAX_VALUE);
  }

  private static <T extends Comparable<T>> T min(T value, T maximum) {
    return maximum == null || value.compareTo(maximum) <= 0 ? value : maximum;
  }
}
