package dev.skynet.controlplane.workflow;

import dev.skynet.protocol.runner.PushBranch;
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
 *     de la fase de la que depende) o del que se copia el suyo (un hijo de una fase {@code
 *     parallel})
 * @param runnerId runner que debe ejecutarla, o {@code null} si vale cualquiera
 * @param command comando de shell de una fase {@code command}, o {@code null} para un agente
 * @param push rama que sube una fase {@code github-pr}, o {@code null} si no sube nada
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
    String command,
    PushBranch push) {

  Invocation(
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
    this(
        kind,
        parent,
        sessionId,
        prompt,
        policy,
        workspaceId,
        resume,
        workspace,
        runnerId,
        command,
        null);
  }

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

  /**
   * Sesión nueva en un worktree nuevo creado, en su runner, desde una instantánea de {@code
   * workspace} (con sus cambios sin confirmar): un hijo de una fase {@code parallel}.
   */
  static Invocation inCopyOf(String prompt, EffectivePolicy policy, WorkspaceView workspace) {
    return new Invocation(
        AgentRunKind.START,
        null,
        UUID.randomUUID(),
        prompt,
        policy,
        null,
        null,
        new WorkspaceRef(workspace.path(), workspace.branch(), true),
        workspace.runnerId());
  }

  /**
   * La misma invocación como reintento de {@code previous} (W6): con {@code restore}, el worktree
   * en el que continúa vuelve antes a esa instantánea.
   */
  Invocation retrying(AgentRun previous, String restore) {
    WorkspaceRef ref =
        restore == null || workspace == null
            ? workspace
            : new WorkspaceRef(workspace.path(), workspace.branch(), false, restore);
    return new Invocation(
        previous == null ? kind : AgentRunKind.RETRY,
        previous,
        sessionId,
        prompt,
        policy,
        workspaceId,
        resume,
        ref,
        runnerId,
        command,
        push);
  }

  /**
   * La misma invocación como fase {@code github-pr}: el runner sube el worktree a {@code push}
   * (EN-A).
   */
  Invocation asPush(PushBranch push) {
    return new Invocation(
        kind,
        parent,
        sessionId,
        "git push " + push.remoteUrl() + " HEAD:" + push.branch(),
        policy,
        workspaceId,
        resume,
        workspace,
        runnerId,
        null,
        push);
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
