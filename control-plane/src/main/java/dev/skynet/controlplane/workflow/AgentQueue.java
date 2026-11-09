package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.definition.StageDefinition;
import dev.skynet.controlplane.definition.StageType;
import dev.skynet.controlplane.definition.WorkflowDefinition;
import dev.skynet.controlplane.definition.WorkflowDefinitions;
import dev.skynet.controlplane.memory.MemoryAgents;
import dev.skynet.controlplane.project.AgentDefaults;
import dev.skynet.controlplane.project.CodeRepository;
import dev.skynet.controlplane.workitem.WorkItem;
import dev.skynet.protocol.AgentProviderKind;
import dev.skynet.protocol.AgentProviderKind.Capability;
import dev.skynet.protocol.runner.AgentLimits;
import dev.skynet.protocol.runner.RiskRequest;
import dev.skynet.protocol.runner.StartAgent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
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
  private final WorkflowDefinitions definitions;
  private final JdbcClient jdbc;

  AgentQueue(
      AgentRunRepository agentRuns,
      PromptRepository prompts,
      RunTransitions transitions,
      AgentDefaults defaults,
      ApplicationEventPublisher publisher,
      MemoryAgents memoryAgents,
      WorkflowDefinitions definitions,
      JdbcClient jdbc) {
    this.agentRuns = agentRuns;
    this.prompts = prompts;
    this.transitions = transitions;
    this.defaults = defaults;
    this.publisher = publisher;
    this.memoryAgents = memoryAgents;
    this.definitions = definitions;
    this.jdbc = jdbc;
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
            : switchesProvider(invocation)
                ? AgentRun.queued(
                    stage.getId(),
                    parent,
                    policy.providerOrDefault(),
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
    Invocation.Replacing replacing = invocation.replacing();
    if (replacing != null) {
      queued.replaces(replacing.provider(), replacing.window(), replacing.resetsAt());
    }
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
    List<String> protectedPaths = noAgent ? List.of() : protectedPaths(run, stage, repository);
    if (!protectedPaths.isEmpty()) {
      spawned.put("protectedPaths", protectedPaths);
      if (invocation.resume() == null) {
        // Una continuación ya lo tiene en su conversación.
        sent = protectedNote(protectedPaths) + "\n\n" + sent;
      }
    }
    RiskRequest risk = noAgent ? null : riskRequest(run, stage, repository, agent, invocation);
    if (risk != null && (!risk.paths().isEmpty() || !risk.patterns().isEmpty())) {
      spawned.put("risk", Map.of("paths", risk.paths(), "patterns", risk.patterns()));
    }
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
        sent = block.text() + "\n\n" + sent;
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
    if (replacing != null) {
      Map<String, Object> fallback = new LinkedHashMap<>();
      fallback.put("stageKey", stage.getStageKey());
      if (childKey != null) {
        fallback.put("childKey", childKey);
      }
      fallback.put("provider", agent.getProvider());
      fallback.put("model", model);
      fallback.put("replaces", replacing.provider());
      fallback.put("window", replacing.window());
      fallback.put("resetsAt", replacing.resetsAt());
      transitions.append(
          "agent_run", agent.getId(), "agent.fallback.started", run.getId(), fallback, now);
    }
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
                invocation.push(),
                protectedPaths,
                risk),
            invocation.runnerId()));
    return agent;
  }

  /** Las rutas protegidas de la fase y del repositorio (VA-A). */
  private List<String> protectedPaths(WorkflowRun run, StageRun stage, CodeRepository repository) {
    StageDefinition definition =
        definitions
            .published(run.getDefinitionId())
            .definition()
            .stage(stage.getStageKey())
            .orElse(null);
    return StageCheckList.protectedPaths(definition, repository);
  }

  /**
   * Lo que pide medir el nivel de riesgo al terminar (AU-A): solo un agente de una fase {@code
   * agent} o {@code parallel} que trabaja en el worktree de la ejecución, con las reglas del
   * workflow y del repositorio. Un juez, un escéptico o quien ejecuta la guía trabajan en una copia
   * que no llega al cambio: no se mide.
   */
  private RiskRequest riskRequest(
      WorkflowRun run,
      StageRun stage,
      CodeRepository repository,
      AgentRun agent,
      Invocation invocation) {
    if (agent.isJudge()
        || agent.isSkeptic()
        || agent.isGuide()
        || (invocation.workspace() != null && invocation.workspace().copy())) {
      return null;
    }
    WorkflowDefinition workflow = definitions.published(run.getDefinitionId()).definition();
    StageDefinition definition = workflow.stage(stage.getStageKey()).orElse(null);
    if (definition == null
        || (definition.type() != StageType.AGENT && definition.type() != StageType.PARALLEL)) {
      return null;
    }
    List<String> paths = new ArrayList<>(workflow.risk().paths());
    List<String> patterns = new ArrayList<>(workflow.risk().patterns());
    repository.getRiskPaths().stream().filter(p -> !paths.contains(p)).forEach(paths::add);
    repository.getRiskPatterns().stream().filter(p -> !patterns.contains(p)).forEach(patterns::add);
    RiskRequest request = new RiskRequest(paths, patterns);
    // En el worktree que ya aprobó un juez, lo que cambia se mide desde lo que vio (AU-B): así una
    // corrección posterior, p. ej. de la CI, lleva el nivel de todo lo que no se juzgó.
    String judged = invocation.workspaceId() == null ? null : judgedSha(run, invocation);
    return judged == null ? request : request.from(judged);
  }

  /** El último commit juzgado y aprobado en el worktree de {@code invocation} (AU-B). */
  private String judgedSha(WorkflowRun run, Invocation invocation) {
    return jdbc.sql(
            "SELECT judged_sha FROM stage_judge WHERE workflow_run_id = ? AND workspace_id = ?"
                + " AND status = 'PASSED' AND judged_sha IS NOT NULL ORDER BY updated_at DESC"
                + " LIMIT 1")
        .params(run.getId(), invocation.workspaceId())
        .query(String.class)
        .optional()
        .orElse(null);
  }

  /**
   * Lo que se le dice al agente antes de su prompt. Claude Code además no puede editarlas; en los
   * demás proveedores, lo que las cambie lo detecta el check {@code protected}.
   */
  static String protectedNote(List<String> paths) {
    return "Rutas protegidas: no cambies, borres ni muevas nada en "
        + String.join(", ", paths.stream().map(p -> "`" + p + "`").toList())
        + ". Si la tarea parece pedirlo, dilo en tu respuesta en lugar de hacerlo: Skynet rechaza"
        + " cualquier cambio en ellas.";
  }

  /**
   * Si un reintento de agente cambia de proveedor respecto a su anterior (CQ-B): la reserva que
   * sigue el trabajo de un agente sin cuota, o el principal que vuelve tras ella. Reanudar o
   * bifurcar siguen con el proveedor de la sesión.
   */
  private static boolean switchesProvider(Invocation invocation) {
    return invocation.kind() == AgentRunKind.RETRY
        && invocation.command() == null
        && invocation.push() == null
        && invocation.policy().provider() != null
        && !invocation.policy().providerOrDefault().equals(invocation.parent().getProvider());
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
