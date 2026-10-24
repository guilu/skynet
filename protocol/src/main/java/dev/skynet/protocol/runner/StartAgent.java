package dev.skynet.protocol.runner;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Datos de una invocación del agente: una orden {@link RunnerCommandType#START} o, con {@code
 * resume}, una {@link RunnerCommandType#RESUME}.
 *
 * @param workflowRunId ejecución a la que pertenece el agente; forma parte de la ruta del worktree
 * @param workItemKey clave del trabajo (p. ej. {@code TKM-1}); forma parte del nombre de la rama
 * @param repositoryPath ruta del repositorio en la máquina del runner
 * @param baseBranch rama de partida del worktree
 * @param sessionId id de sesión que el runner fija con {@code --session-id}; al reanudar sin
 *     bifurcar es la sesión que se reanuda
 * @param prompt prompt efectivo
 * @param allowedTools herramientas permitidas; el resto se deniega
 * @param permissionMode modo de permisos del proveedor (p. ej. {@code dontAsk})
 * @param model modelo, o {@code null} para el predeterminado del proveedor
 * @param resume sesión y worktree de partida; solo en {@link RunnerCommandType#RESUME}
 * @param environment variables de entorno adicionales que recibe el agente, siempre dentro de las
 *     que el runner permite ({@code SKYNET_AGENT_ENV}); {@code null} para todas las permitidas
 * @param workspace worktree existente en el que arranca la sesión nueva, o {@code null} para crear
 *     uno; no se combina con {@code resume}
 * @param command comando de shell de una fase {@code command}: el runner lo ejecuta con {@code sh
 *     -c} en el worktree en lugar de lanzar el agente; {@code null} para un agente
 * @param outputSchema JSON Schema de la salida estructurada que debe devolver el agente ({@code
 *     --json-schema}), o {@code null} si no tiene que devolver ninguna. {@code {"$ref": "<ruta>"}}
 *     apunta a un fichero del repositorio, que el runner lee del worktree al arrancar
 * @param provider proveedor que ejecuta el agente ({@link dev.skynet.protocol.AgentProviderKind});
 *     {@code null} para {@code claude-code}. No aplica a un comando.
 * @param memory acceso del agente a la memoria del proyecto ({@link #MEMORY_READ} o {@link
 *     #MEMORY_WRITE}): el runner pide un token de memoria para la invocación y le da las
 *     herramientas MCP de memoria. {@code null} sin memoria; nunca en un comando.
 */
public record StartAgent(
    UUID workflowRunId,
    String workItemKey,
    String repositoryPath,
    String baseBranch,
    UUID sessionId,
    String prompt,
    List<String> allowedTools,
    String permissionMode,
    String model,
    AgentLimits limits,
    ResumeFrom resume,
    List<String> environment,
    WorkspaceRef workspace,
    String command,
    String outputSchema,
    String provider,
    String memory) {

  public static final String MEMORY_READ = "read";
  public static final String MEMORY_WRITE = "write";

  public StartAgent {
    Objects.requireNonNull(workflowRunId, "workflowRunId");
    Objects.requireNonNull(repositoryPath, "repositoryPath");
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(prompt, "prompt");
    allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
    limits = limits == null ? AgentLimits.none() : limits;
    environment = environment == null ? null : List.copyOf(environment);
    if (resume != null && workspace != null) {
      throw new IllegalArgumentException("Una reanudación ya trae su worktree");
    }
    if (command != null && resume != null) {
      throw new IllegalArgumentException("Un comando no se reanuda");
    }
    if (command != null && outputSchema != null) {
      throw new IllegalArgumentException("Un comando no devuelve salida estructurada");
    }
    if (memory != null && !MEMORY_READ.equals(memory) && !MEMORY_WRITE.equals(memory)) {
      throw new IllegalArgumentException("memory debe ser read o write: " + memory);
    }
    if (command != null && memory != null) {
      throw new IllegalArgumentException("Un comando no usa la memoria");
    }
  }

  /** Invocación sin memoria. */
  public StartAgent(
      UUID workflowRunId,
      String workItemKey,
      String repositoryPath,
      String baseBranch,
      UUID sessionId,
      String prompt,
      List<String> allowedTools,
      String permissionMode,
      String model,
      AgentLimits limits,
      ResumeFrom resume,
      List<String> environment,
      WorkspaceRef workspace,
      String command,
      String outputSchema,
      String provider) {
    this(
        workflowRunId,
        workItemKey,
        repositoryPath,
        baseBranch,
        sessionId,
        prompt,
        allowedTools,
        permissionMode,
        model,
        limits,
        resume,
        environment,
        workspace,
        command,
        outputSchema,
        provider,
        null);
  }

  /** Invocación de Claude Code (o un comando). */
  public StartAgent(
      UUID workflowRunId,
      String workItemKey,
      String repositoryPath,
      String baseBranch,
      UUID sessionId,
      String prompt,
      List<String> allowedTools,
      String permissionMode,
      String model,
      AgentLimits limits,
      ResumeFrom resume,
      List<String> environment,
      WorkspaceRef workspace,
      String command,
      String outputSchema) {
    this(
        workflowRunId,
        workItemKey,
        repositoryPath,
        baseBranch,
        sessionId,
        prompt,
        allowedTools,
        permissionMode,
        model,
        limits,
        resume,
        environment,
        workspace,
        command,
        outputSchema,
        null);
  }

  /** La misma invocación con otro {@code outputSchema}. */
  public StartAgent withOutputSchema(String schema) {
    return new StartAgent(
        workflowRunId,
        workItemKey,
        repositoryPath,
        baseBranch,
        sessionId,
        prompt,
        allowedTools,
        permissionMode,
        model,
        limits,
        resume,
        environment,
        workspace,
        command,
        schema,
        provider,
        memory);
  }

  /** Proveedor efectivo: {@code claude-code} si no se indica. */
  public String providerOrDefault() {
    return provider == null ? dev.skynet.protocol.AgentProviderKind.CLAUDE_CODE.id() : provider;
  }

  /** Invocación sin salida estructurada. */
  public StartAgent(
      UUID workflowRunId,
      String workItemKey,
      String repositoryPath,
      String baseBranch,
      UUID sessionId,
      String prompt,
      List<String> allowedTools,
      String permissionMode,
      String model,
      AgentLimits limits,
      ResumeFrom resume,
      List<String> environment,
      WorkspaceRef workspace,
      String command) {
    this(
        workflowRunId,
        workItemKey,
        repositoryPath,
        baseBranch,
        sessionId,
        prompt,
        allowedTools,
        permissionMode,
        model,
        limits,
        resume,
        environment,
        workspace,
        command,
        null);
  }

  /** Invocación de un agente (no un comando). */
  public StartAgent(
      UUID workflowRunId,
      String workItemKey,
      String repositoryPath,
      String baseBranch,
      UUID sessionId,
      String prompt,
      List<String> allowedTools,
      String permissionMode,
      String model,
      AgentLimits limits,
      ResumeFrom resume,
      List<String> environment,
      WorkspaceRef workspace) {
    this(
        workflowRunId,
        workItemKey,
        repositoryPath,
        baseBranch,
        sessionId,
        prompt,
        allowedTools,
        permissionMode,
        model,
        limits,
        resume,
        environment,
        workspace,
        null);
  }

  /** Invocación que no parte de un worktree existente (o que lo trae en {@code resume}). */
  public StartAgent(
      UUID workflowRunId,
      String workItemKey,
      String repositoryPath,
      String baseBranch,
      UUID sessionId,
      String prompt,
      List<String> allowedTools,
      String permissionMode,
      String model,
      AgentLimits limits,
      ResumeFrom resume,
      List<String> environment) {
    this(
        workflowRunId,
        workItemKey,
        repositoryPath,
        baseBranch,
        sessionId,
        prompt,
        allowedTools,
        permissionMode,
        model,
        limits,
        resume,
        environment,
        null,
        null);
  }
}
