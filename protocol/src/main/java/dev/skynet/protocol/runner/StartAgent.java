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
    String command) {

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
