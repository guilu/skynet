package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.definition.StageDefinition;
import dev.skynet.controlplane.memory.MemoryAgents;
import dev.skynet.controlplane.project.AgentDefaults;
import dev.skynet.controlplane.project.CodeRepository;
import dev.skynet.controlplane.workitem.WorkItem;
import dev.skynet.protocol.AgentProviderKind;
import dev.skynet.protocol.AgentProviderKind.Capability;
import dev.skynet.protocol.runner.AgentLimits;
import dev.skynet.protocol.runner.StartAgent;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Pone en cola el agente de una fase: crea la invocación con su prompt, registra {@code
 * agent.spawned} y publica la orden para los runners, todo en la transacción en curso.
 *
 * <p>Una sesión nueva con memoria recibe antes de su prompt el bloque de memoria del proyecto
 * (ME-C); el prompt que se guarda es el de la fase, y {@code agent.spawned} dice qué observaciones
 * (y qué versión) llevaba el bloque.
 */
@Component
class AgentQueue {

  private final AgentRunRepository agentRuns;
  private final PromptRepository prompts;
  private final RunTransitions transitions;
  private final AgentDefaults defaults;
  private final ApplicationEventPublisher publisher;
  private final MemoryAgents memoryAgents;

  AgentQueue(
      AgentRunRepository agentRuns,
      PromptRepository prompts,
      RunTransitions transitions,
      AgentDefaults defaults,
      ApplicationEventPublisher publisher,
      MemoryAgents memoryAgents) {
    this.agentRuns = agentRuns;
    this.prompts = prompts;
    this.transitions = transitions;
    this.defaults = defaults;
    this.publisher = publisher;
    this.memoryAgents = memoryAgents;
  }

  AgentRun queue(
      WorkflowRun run,
      StageRun stage,
      WorkItem workItem,
      CodeRepository repository,
      Invocation invocation,
      Instant now) {
    return queue(run, stage, workItem, repository, invocation, null, now);
  }

  /** Con {@code childKey}, la invocación es ese hijo de una fase {@code parallel}. */
  AgentRun queue(
      WorkflowRun run,
      StageRun stage,
      WorkItem workItem,
      CodeRepository repository,
      Invocation invocation,
      String childKey,
      Instant now) {
    AgentRun parent = invocation.parent();
    EffectivePolicy policy = invocation.policy();
    AgentLimits limits = policy.limits();
    AgentRun queued =
        parent == null
            ? AgentRun.queued(
                stage.getId(),
                repository.getId(),
                invocation.push() != null
                    ? AgentRun.PROVIDER_PUSH
                    : invocation.command() == null
                        ? policy.providerOrDefault()
                        : AgentRun.PROVIDER_COMMAND,
                invocation.sessionId(),
                invocation.workspaceId(),
                limits,
                now)
            : AgentRun.queued(
                stage.getId(),
                parent,
                invocation.kind(),
                invocation.sessionId(),
                invocation.workspaceId(),
                limits,
                now);
    queued.forChild(childKey);
    String memory = memoryMode(invocation, policy);
    queued.useMemory(memory);
    boolean noAgent = invocation.command() != null || invocation.push() != null;
    String outputSchema = noAgent ? null : policy.outputSchema();
    queued.expectOutput(outputSchema);
    AgentRun agent = agentRuns.save(queued);
    Prompt prompt =
        prompts.save(Prompt.of(agent.getId(), Prompt.ROLE_USER, invocation.prompt(), now));
    Map<String, Object> spawned = new LinkedHashMap<>();
    spawned.put("stageRunId", stage.getId());
    if (childKey != null) {
      spawned.put("childKey", childKey);
    }
    spawned.put("repositoryId", repository.getId());
    spawned.put("provider", agent.getProvider());
    spawned.put("kind", agent.getKind());
    spawned.put("status", agent.getStatus());
    spawned.put("promptId", prompt.getId());
    spawned.put("promptSha256", prompt.getSha256());
    spawned.put("allowedTools", policy.allowedTools());
    spawned.put("permissionMode", policy.permissionMode());
    String model = model(invocation, agent.getProvider());
    if (model != null) {
      spawned.put("model", model);
    }
    if (invocation.command() != null) {
      spawned.put("command", invocation.command());
    }
    if (invocation.push() != null) {
      Map<String, Object> push = new LinkedHashMap<>();
      push.put("remoteUrl", invocation.push().remoteUrl());
      push.put("branch", invocation.push().branch());
      push.put("base", invocation.push().base());
      push.put("force", invocation.push().force());
      spawned.put("push", push);
    }
    if (outputSchema != null) {
      spawned.put("outputSchema", outputSchema);
    }
    Map<String, Object> limitsPayload = new LinkedHashMap<>();
    limitsPayload.put("maxTurns", limits.maxTurns());
    limitsPayload.put("maxBudgetUsd", limits.maxBudgetUsd());
    limitsPayload.put(
        "timeoutMinutes", limits.timeout() == null ? null : limits.timeout().toMinutes());
    spawned.put("limits", limitsPayload);
    if (parent != null) {
      spawned.put("parentAgentRunId", parent.getId());
    }
    if (invocation.workspace() != null && invocation.workspace().copy()) {
      spawned.put("copyOfWorkspace", invocation.workspace().path());
    } else if (invocation.workspace() != null) {
      spawned.put("workspaceId", invocation.workspaceId());
      if (invocation.workspace().restore() != null) {
        spawned.put("restoreSnapshot", invocation.workspace().restore());
      }
    }
    String sent = invocation.prompt();
    if (memory != null) {
      Map<String, Object> memoryPayload = new LinkedHashMap<>();
      memoryPayload.put("mode", memory);
      // Solo una sesión nueva recibe el bloque: una continuación ya lo tiene en su conversación.
      if (invocation.resume() == null) {
        MemoryAgents.PromptBlock block =
            memoryAgents.promptBlock(
                agent.getId(),
                workItem.getProjectId(),
                repository.getId(),
                MemoryAgents.Mode.valueOf(memory),
                AgentProviderKind.byId(agent.getProvider())
                    .map(k -> k.supports(Capability.MEMORY_TOOLS))
                    .orElse(false));
        sent = block.text() + "\n\n" + invocation.prompt();
        memoryPayload.put(
            "context",
            block.observations().stream()
                .map(r -> Map.of("id", r.id(), "version", r.version()))
                .toList());
        memoryPayload.put("omitted", block.omitted());
      }
      spawned.put("memory", memoryPayload);
    }
    transitions.append("agent_run", agent.getId(), "agent.spawned", run.getId(), spawned, now);
    publisher.publishEvent(
        new AgentRunQueued(
            agent.getId(),
            new StartAgent(
                run.getId(),
                workItem.getKey(),
                repository.getLocalPath(),
                repository.getDefaultBranch(),
                invocation.sessionId(),
                sent,
                policy.allowedTools(),
                policy.permissionMode(),
                model,
                limits,
                invocation.resume(),
                policy.environment(),
                invocation.workspace(),
                invocation.command(),
                outputSchema,
                agent.isCommand() ? null : agent.getProvider(),
                memory == null ? null : memory.toLowerCase(Locale.ROOT),
                invocation.push()),
            invocation.runnerId()));
    return agent;
  }

  /**
   * Acceso a la memoria de la invocación ({@code READ} o {@code WRITE}) según la clave {@code
   * memory} de su fase, o {@code null} sin memoria: un comando o una fase con {@code memory: off}.
   */
  private static String memoryMode(Invocation invocation, EffectivePolicy policy) {
    String mode = policy.memoryOrDefault();
    if (invocation.command() != null
        || invocation.push() != null
        || StageDefinition.MEMORY_OFF.equals(mode)) {
      return null;
    }
    return mode.toUpperCase(Locale.ROOT);
  }

  /**
   * Modelo de la invocación: el de su agente del YAML o, si no fija ninguno, el global de Claude
   * Code ({@code skynet.agent.model}); los demás proveedores usan entonces el predeterminado de su
   * CLI. Un comando no tiene modelo.
   */
  private String model(Invocation invocation, String provider) {
    if (invocation.command() != null || invocation.push() != null) {
      return null;
    }
    String model = invocation.policy().model();
    if (model != null) {
      return model;
    }
    return AgentRun.PROVIDER_CLAUDE_CODE.equals(provider) ? defaults.model() : null;
  }
}
