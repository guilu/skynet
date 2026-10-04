package dev.skynet.protocol.runner;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Datos de una orden {@link RunnerCommandType#START}.
 *
 * @param workflowRunId ejecución a la que pertenece el agente; forma parte de la ruta del worktree
 * @param workItemKey clave del trabajo (p. ej. {@code TKM-1}); forma parte del nombre de la rama
 * @param repositoryPath ruta del repositorio en la máquina del runner
 * @param baseBranch rama de partida del worktree
 * @param sessionId id de sesión que el runner fija con {@code --session-id}
 * @param prompt prompt efectivo
 * @param allowedTools herramientas permitidas; el resto se deniega
 * @param permissionMode modo de permisos del proveedor (p. ej. {@code dontAsk})
 * @param model modelo, o {@code null} para el predeterminado del proveedor
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
    AgentLimits limits) {

  public StartAgent {
    Objects.requireNonNull(workflowRunId, "workflowRunId");
    Objects.requireNonNull(repositoryPath, "repositoryPath");
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(prompt, "prompt");
    allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
    limits = limits == null ? AgentLimits.none() : limits;
  }
}
