package dev.skynet.controlplane.workflow;

import dev.skynet.protocol.runner.ResumeFrom;
import dev.skynet.protocol.runner.WorkspaceRef;
import java.util.UUID;

/**
 * Invocación por crear.
 *
 * @param parent invocación de la que parte, o {@code null} en un lanzamiento
 * @param workspaceId worktree que reutiliza (al reanudar o al continuar el de otra fase)
 * @param resume sesión y worktree de partida (reanudar y bifurcar)
 * @param workspace worktree existente en el que arranca una sesión nueva (una fase que continúa el
 *     de la fase de la que depende)
 * @param runnerId runner que debe ejecutarla, o {@code null} si vale cualquiera
 * @param command comando de shell de una fase {@code command}, o {@code null} para un agente
 */
record Invocation(
    AgentRunKind kind,
    AgentRun parent,
    UUID sessionId,
    String prompt,
    EffectivePolicy policy,
    UUID workspaceId,
    ResumeFrom resume,
    WorkspaceRef workspace,
    UUID runnerId,
    String command) {

  Invocation(
      AgentRunKind kind,
      AgentRun parent,
      UUID sessionId,
      String prompt,
      EffectivePolicy policy,
      UUID workspaceId,
      ResumeFrom resume,
      WorkspaceRef workspace,
      UUID runnerId) {
    this(kind, parent, sessionId, prompt, policy, workspaceId, resume, workspace, runnerId, null);
  }

  /** Invocación con sesión y worktree nuevos, en cualquier runner. */
  static Invocation fresh(
      AgentRunKind kind, AgentRun parent, String prompt, EffectivePolicy policy) {
    return new Invocation(kind, parent, UUID.randomUUID(), prompt, policy, null, null, null, null);
  }

  /** Sesión nueva en el worktree {@code workspace}, en su runner. */
  static Invocation inWorkspace(String prompt, EffectivePolicy policy, WorkspaceView workspace) {
    return new Invocation(
        AgentRunKind.START,
        null,
        UUID.randomUUID(),
        prompt,
        policy,
        workspace.id(),
        null,
        new WorkspaceRef(workspace.path(), workspace.branch()),
        workspace.runnerId());
  }

  /** La misma invocación como fase {@code command}: el runner ejecuta {@code command}. */
  Invocation asCommand(String command) {
    return new Invocation(
        kind,
        parent,
        sessionId,
        command,
        policy,
        workspaceId,
        resume,
        workspace,
        runnerId,
        command);
  }
}
