package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.event.EventDraft;
import dev.skynet.controlplane.event.EventStore;
import dev.skynet.controlplane.shared.ConflictException;
import dev.skynet.controlplane.shared.NotFoundException;
import dev.skynet.controlplane.shared.TimeSource;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Elimina worktrees: a petición desde la web o, pasada la retención, solo. La orden {@code CLEANUP}
 * va al runner del worktree, que responde con {@code agent.workspace.removed}; la rama se conserva.
 */
@Service
public class WorkspaceCleanup {

  private static final Logger log = LoggerFactory.getLogger(WorkspaceCleanup.class);

  private final AgentRunRepository agentRuns;
  private final StageRunRepository stageRuns;
  private final Workspaces workspaces;
  private final EventStore events;
  private final ApplicationEventPublisher publisher;
  private final WorkspaceProperties properties;
  private final TimeSource time;
  private final TransactionTemplate tx;

  WorkspaceCleanup(
      AgentRunRepository agentRuns,
      StageRunRepository stageRuns,
      Workspaces workspaces,
      EventStore events,
      ApplicationEventPublisher publisher,
      WorkspaceProperties properties,
      TimeSource time,
      TransactionTemplate tx) {
    this.agentRuns = agentRuns;
    this.stageRuns = stageRuns;
    this.workspaces = workspaces;
    this.events = events;
    this.publisher = publisher;
    this.properties = properties;
    this.time = time;
    this.tx = tx;
  }

  /**
   * Pide eliminar el worktree de un agente. Exige que nada lo esté usando; pedirlo cuando ya está
   * pedido o eliminado no tiene efecto.
   */
  @Transactional
  public void request(UUID agentRunId) {
    AgentRun agent =
        agentRuns
            .findById(agentRunId)
            .orElseThrow(() -> new NotFoundException("Agente", agentRunId));
    WorkspaceView workspace =
        workspaces
            .find(agent.getWorkspaceId())
            .orElseThrow(
                () ->
                    new ConflictException(
                        "El agente " + agentRunId + " no llegó a preparar su worktree"));
    workspaces.lock(workspace.id());
    if (workspaces.hasLiveInvocation(workspace.id())) {
      throw new ConflictException(
          "Hay una invocación o una verificación en curso en el worktree "
              + workspace.path()
              + ": espera a que termine o cancélala");
    }
    request(agent, workspace, "manual", time.now());
  }

  /** Pide eliminar los worktrees que han pasado la retención. Devuelve cuántos. */
  @Scheduled(
      fixedDelayString = "${skynet.workspaces.check-interval:1h}",
      initialDelayString = "${skynet.workspaces.check-interval:1h}")
  public int expire() {
    Instant now = time.now();
    int requested = 0;
    for (UUID id : workspaces.expired(now.minus(properties.retention()))) {
      try {
        Boolean done =
            tx.execute(
                status -> {
                  workspaces.lock(id);
                  WorkspaceView workspace = workspaces.find(id).orElseThrow();
                  UUID agentRunId = workspaces.lastAgentOf(id).orElse(null);
                  if (agentRunId == null || workspaces.hasLiveInvocation(id)) {
                    return false;
                  }
                  return request(
                      agentRuns.findById(agentRunId).orElseThrow(), workspace, "retention", now);
                });
        if (Boolean.TRUE.equals(done)) {
          requested++;
        }
      } catch (RuntimeException e) {
        log.warn("No se pudo pedir la eliminación del worktree {}", id, e);
      }
    }
    return requested;
  }

  private boolean request(AgentRun agent, WorkspaceView workspace, String trigger, Instant now) {
    if (!workspaces.requestCleanup(workspace.id(), now)) {
      return false;
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("workspaceId", workspace.id());
    payload.put("path", workspace.path());
    payload.put("trigger", trigger);
    events.append(
        EventDraft.of(
            "agent_run",
            agent.getId(),
            "agent.workspace.cleanup.requested",
            stageRuns.findById(agent.getStageRunId()).orElseThrow().getWorkflowRunId(),
            payload,
            now));
    publisher.publishEvent(
        new WorkspaceCleanupRequested(
            workspace.id(), workspace.runnerId(), agent.getId(), workspace.path()));
    return true;
  }
}
