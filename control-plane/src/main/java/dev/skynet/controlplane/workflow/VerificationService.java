package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.definition.StageDefinition;
import dev.skynet.controlplane.definition.StageType;
import dev.skynet.controlplane.event.EventDraft;
import dev.skynet.controlplane.event.EventStore;
import dev.skynet.controlplane.project.CodeRepository;
import dev.skynet.controlplane.project.ProjectService;
import dev.skynet.controlplane.shared.ConflictException;
import dev.skynet.controlplane.shared.NotFoundException;
import dev.skynet.controlplane.shared.TimeSource;
import dev.skynet.protocol.AgentEventType;
import dev.skynet.protocol.runner.RunVerification;
import dev.skynet.protocol.runner.VerificationCheck;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verificación independiente del worktree de un agente (docs/implementation-plan.md §M5): el runner
 * ejecuta el comando de validación del repositorio y Skynet registra su resultado. Se lanza sola
 * cuando una invocación se completa y se puede reejecutar a mano. No cambia el estado del agente ni
 * el de su ejecución.
 */
@Service
public class VerificationService {

  static final String AUTO = "AUTO";
  static final String MANUAL = "MANUAL";
  static final String STAGE = "STAGE";

  private final AgentRunRepository agentRuns;
  private final StageRunRepository stageRuns;
  private final ProjectService projects;
  private final Workspaces workspaces;
  private final Verifications verifications;
  private final EventStore events;
  private final ApplicationEventPublisher publisher;
  private final VerificationProperties properties;
  private final TimeSource time;

  VerificationService(
      AgentRunRepository agentRuns,
      StageRunRepository stageRuns,
      ProjectService projects,
      Workspaces workspaces,
      Verifications verifications,
      EventStore events,
      ApplicationEventPublisher publisher,
      VerificationProperties properties,
      TimeSource time) {
    this.agentRuns = agentRuns;
    this.stageRuns = stageRuns;
    this.projects = projects;
    this.workspaces = workspaces;
    this.verifications = verifications;
    this.events = events;
    this.publisher = publisher;
    this.properties = properties;
    this.time = time;
  }

  /** Verificaciones de un agente, de la más reciente a la más antigua. */
  @Transactional(readOnly = true)
  public List<VerificationResult> ofAgent(UUID agentRunId) {
    agent(agentRunId);
    return verifications.ofAgent(agentRunId);
  }

  /**
   * Reejecuta la verificación del worktree de un agente terminado. Como una reanudación, exige que
   * no haya ninguna invocación ni verificación viva en ese worktree.
   */
  @Transactional
  public VerificationResult request(UUID agentRunId) {
    AgentRun agent = agent(agentRunId);
    if (agent.isCommand()) {
      throw new ConflictException("Es un comando: su resultado ya es su código de salida");
    }
    if (!agent.getStatus().isTerminal()) {
      throw new ConflictException(
          "El agente " + agentRunId + " sigue en curso (" + agent.getStatus() + ")");
    }
    WorkspaceView workspace =
        workspaces
            .find(agent.getWorkspaceId())
            .orElseThrow(
                () ->
                    new ConflictException(
                        "El agente " + agentRunId + " no llegó a preparar su worktree"));
    String command = commandOf(agent);
    if (command == null) {
      throw new ConflictException(
          "El repositorio no tiene comando de verificación: configúralo para poder verificar");
    }
    workspaces.lock(workspace.id());
    RunService.requireUsable(workspaces.find(workspace.id()).orElseThrow());
    if (workspaces.hasLiveInvocation(workspace.id())) {
      throw new ConflictException(
          "Ya hay una invocación o una verificación en curso en el worktree "
              + workspace.path()
              + ": espera a que termine");
    }
    UUID id = queue(agent, workspace, MANUAL, command, null, time.now());
    return verifications.find(id).orElseThrow();
  }

  /**
   * Una invocación acaba de completarse: si el repositorio tiene comando, verifica su worktree. Una
   * fase {@code command} no se verifica: ya es un comando. Corre en la transacción de la ingestión.
   */
  void afterCompletion(AgentRun agent, Instant now) {
    String command = agent.isCommand() ? null : commandOf(agent);
    if (command == null) {
      return;
    }
    workspaces
        .find(agent.getWorkspaceId())
        .ifPresent(workspace -> queue(agent, workspace, AUTO, command, null, now));
  }

  /**
   * La verificación de una fase con {@code artifacts} o {@code checks} en el worktree de {@code
   * agent}: sus comprobaciones y, si una es {@code tests} o la fase tiene agente, el comando del
   * repositorio (que solo cuenta para la fase con {@code tests}). Los cambios se miden desde {@code
   * baseCommit}.
   */
  UUID queueStage(
      AgentRun agent,
      WorkspaceView workspace,
      StageRun stage,
      StageDefinition definition,
      String baseCommit,
      Instant now) {
    boolean runsCommand =
        definition.runsTests()
            || (definition.type() != StageType.VERIFICATION && !agent.isCommand());
    String command = runsCommand ? commandOf(agent) : null;
    return queue(
        agent,
        workspace,
        STAGE,
        command,
        new StageChecks(stage.getId(), definition.checks(), baseCommit),
        now);
  }

  /** Las comprobaciones de la verificación de una fase. */
  private record StageChecks(UUID stageRunId, List<VerificationCheck> checks, String baseCommit) {}

  /**
   * Aplica un evento de verificación que envía el runner. Devuelve {@code false} si la verificación
   * no existe o no es de ese agente.
   */
  boolean belongsTo(UUID verificationRunId, UUID agentRunId) {
    return verificationRunId != null
        && verifications
            .find(verificationRunId)
            .map(v -> v.agentRunId().equals(agentRunId))
            .orElse(false);
  }

  void apply(UUID verificationRunId, AgentEventType type, Map<String, Object> payload, Instant at) {
    if (type == AgentEventType.VERIFICATION_STARTED) {
      verifications.started(verificationRunId, at);
    } else if (type == AgentEventType.VERIFICATION_COMPLETED) {
      verifications.completed(
          verificationRunId,
          new Verifications.Outcome(
              integer(payload.get("exitCode")),
              string(payload.get("signal")),
              string(payload.get("error")),
              tests(payload.get("tests")),
              checks(payload.get("checks"))),
          at);
    }
  }

  private UUID queue(
      AgentRun agent,
      WorkspaceView workspace,
      String trigger,
      String command,
      StageChecks stage,
      Instant now) {
    CodeRepository repository = projects.getRepository(agent.getRepositoryId());
    UUID id =
        verifications.create(
            agent.getId(),
            workspace.id(),
            workspace.runnerId(),
            trigger,
            command,
            stage == null ? null : stage.stageRunId(),
            stage == null
                ? List.of()
                : stage.checks().stream()
                    .map(c -> new VerificationResult.CheckResult(c.name(), c.target(), null, null))
                    .toList(),
            now);
    UUID workflowRunId = stageRuns.findById(agent.getStageRunId()).orElseThrow().getWorkflowRunId();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("agentRunId", agent.getId());
    payload.put("trigger", trigger);
    payload.put("command", command);
    payload.put("runnerId", workspace.runnerId());
    if (stage != null) {
      payload.put("stageRunId", stage.stageRunId());
      payload.put("checks", stage.checks().stream().map(VerificationCheck::name).toList());
    }
    events.append(
        EventDraft.of("verification_run", id, "verification.queued", workflowRunId, payload, now));
    publisher.publishEvent(
        new VerificationQueued(
            agent.getId(),
            workspace.runnerId(),
            new RunVerification(
                id,
                workspace.path(),
                command,
                repository.getTestReportPaths(),
                properties.timeout(),
                stage == null ? List.of() : stage.checks(),
                stage == null ? null : stage.baseCommit())));
    return id;
  }

  private String commandOf(AgentRun agent) {
    return projects.getRepository(agent.getRepositoryId()).getValidationCommand();
  }

  private AgentRun agent(UUID agentRunId) {
    return agentRuns
        .findById(agentRunId)
        .orElseThrow(() -> new NotFoundException("Ejecución de agente", agentRunId));
  }

  private static VerificationResult.TestTotals tests(Object value) {
    if (!(value instanceof Map<?, ?> map) || integer(map.get("total")) == null) {
      return null;
    }
    return new VerificationResult.TestTotals(
        integer(map.get("total")),
        orZero(integer(map.get("failed"))),
        orZero(integer(map.get("errors"))),
        orZero(integer(map.get("skipped"))));
  }

  /** Las comprobaciones que devuelve el runner, o {@code null} si no las devuelve. */
  private static List<VerificationResult.CheckResult> checks(Object value) {
    if (!(value instanceof List<?> list)) {
      return null;
    }
    return list.stream()
        .filter(Map.class::isInstance)
        .map(Map.class::cast)
        .map(
            m ->
                new VerificationResult.CheckResult(
                    string(m.get("name")),
                    string(m.get("target")),
                    Boolean.TRUE.equals(m.get("passed")),
                    string(m.get("reason")),
                    omitted(m.get("omitted"))))
        .toList();
  }

  /** Los ficheros declarados que el runner no guardó, si los hay. */
  private static List<VerificationResult.OmittedFile> omitted(Object value) {
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    return list.stream()
        .filter(Map.class::isInstance)
        .map(Map.class::cast)
        .map(
            m -> new VerificationResult.OmittedFile(string(m.get("path")), string(m.get("reason"))))
        .toList();
  }

  private static int orZero(Integer value) {
    return value == null ? 0 : value;
  }

  private static Integer integer(Object value) {
    return value instanceof Number n ? n.intValue() : null;
  }

  private static String string(Object value) {
    return value == null ? null : value.toString();
  }
}
