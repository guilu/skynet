package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.definition.AgentDefinition;
import dev.skynet.controlplane.definition.StageDefinition;
import dev.skynet.controlplane.project.AgentPolicy;
import dev.skynet.protocol.AgentProviderKind;
import dev.skynet.protocol.runner.AgentLimits;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lo que se le permite a una invocación: lo que pide su agente del YAML, recortado a la política
 * del repositorio. Un agente nunca obtiene herramientas, un modo de permisos ni límites que el
 * repositorio no le dé.
 *
 * @param model modelo que pide el agente, o {@code null} para el global ({@code
 *     skynet.agent.model})
 * @param outputSchema JSON Schema de la salida estructurada que pide el agente ({@code output}), o
 *     {@code null}
 * @param provider proveedor que pide el agente; {@code null} para {@code claude-code}
 * @param memory acceso a la memoria que pide la fase ({@code off}, {@code read} o {@code write});
 *     {@code null} para {@code write}
 */
record EffectivePolicy(
    List<String> allowedTools,
    String permissionMode,
    List<String> environment,
    AgentLimits limits,
    String model,
    String outputSchema,
    String provider,
    String memory) {

  EffectivePolicy(
      List<String> allowedTools,
      String permissionMode,
      List<String> environment,
      AgentLimits limits,
      String model) {
    this(allowedTools, permissionMode, environment, limits, model, null, null, null);
  }

  /** Acceso efectivo a la memoria: {@code write} si la fase no dice otro. */
  String memoryOrDefault() {
    return memory == null ? StageDefinition.MEMORY_WRITE : memory;
  }

  /** La misma política con el acceso a la memoria de la fase. */
  EffectivePolicy withMemory(String stageMemory) {
    return new EffectivePolicy(
        allowedTools,
        permissionMode,
        environment,
        limits,
        model,
        outputSchema,
        provider,
        stageMemory);
  }

  /** Proveedor efectivo: {@code claude-code} si el agente no pide otro. */
  String providerOrDefault() {
    return provider == null ? AgentProviderKind.CLAUDE_CODE.id() : provider;
  }

  /** Del menos al más permisivo; {@code default} y {@code dontAsk} solo usan lo permitido. */
  private static final Map<String, Integer> PERMISSIVENESS =
      Map.of("plan", 0, "default", 1, "dontAsk", 1, "acceptEdits", 2);

  /** La política del repositorio tal cual, con los límites de la invocación. */
  static EffectivePolicy of(AgentPolicy policy, AgentLimits limits) {
    return new EffectivePolicy(
        policy.allowedTools(), policy.permissionMode(), policy.environment(), limits, null);
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
    return new EffectivePolicy(
        tools,
        mode,
        policy.environment(),
        limits,
        agent.model(),
        agent.output() == null ? null : JsonMapper.shared().writeValueAsString(agent.output()),
        agent.provider(),
        null);
  }

  /**
   * La misma política con el {@code outputSchema} de la fase, que manda sobre el {@code output} de
   * su agente; sin él ({@code null}), la misma.
   */
  EffectivePolicy withOutputSchema(String stageSchema) {
    return stageSchema == null
        ? this
        : new EffectivePolicy(
            allowedTools,
            permissionMode,
            environment,
            limits,
            model,
            stageSchema,
            provider,
            memory);
  }

  /** La misma política con otros límites. */
  EffectivePolicy withLimits(AgentLimits other) {
    return new EffectivePolicy(
        allowedTools, permissionMode, environment, other, model, outputSchema, provider, memory);
  }

  /**
   * La misma política con el proveedor y el modelo del lanzamiento, para una fase sin agente con
   * nombre.
   */
  EffectivePolicy withAgent(LaunchAgent launch) {
    return new EffectivePolicy(
        allowedTools,
        permissionMode,
        environment,
        limits,
        launch.model(),
        outputSchema,
        launch.provider(),
        memory);
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
