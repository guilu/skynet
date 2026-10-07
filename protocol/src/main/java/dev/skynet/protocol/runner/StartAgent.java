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
    List<String> environment) {

  public StartAgent {
    Objects.requireNonNull(workflowRunId, "workflowRunId");
    Objects.requireNonNull(repositoryPath, "repositoryPath");
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(prompt, "prompt");
    allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
    limits = limits == null ? AgentLimits.none() : limits;
    environment = environment == null ? null : List.copyOf(environment);
  }
}
