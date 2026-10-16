package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.definition.PublishedDefinition;
import dev.skynet.controlplane.definition.StageDefinition;
import dev.skynet.controlplane.definition.StageDefinition.Dependency;
import dev.skynet.controlplane.definition.StageType;
import dev.skynet.controlplane.definition.WorkflowDefinition;
import dev.skynet.controlplane.project.CodeRepository;
import dev.skynet.controlplane.project.Project;
import dev.skynet.controlplane.project.ProjectService;
import dev.skynet.controlplane.shared.ConflictException;
import dev.skynet.controlplane.shared.InvalidRequestException;
import dev.skynet.controlplane.shared.NotFoundException;
import dev.skynet.controlplane.shared.TimeSource;
import dev.skynet.controlplane.workitem.WorkItem;
import dev.skynet.protocol.StageStatus;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Puertas de aprobación (W5): la petición que deja una fase {@code human-approval} al arrancar, con
 * la foto de su evidencia, y las decisiones sobre ella. Las decisiones no se reescriben ({@code
 * V19__approvals.sql}); una petición solo pasa una vez de pendiente a su estado final.
 */
@Component
public class Approvals {

  static final String PENDING = "PENDING";
  static final String APPROVED = "APPROVED";
  static final String REJECTED = "REJECTED";
  static final String CANCELLED = "CANCELLED";
  static final String CHANGES_REQUESTED = "CHANGES_REQUESTED";

  /** Quién firma la cancelación de una petición cuando se cancela su fase. */
  static final String SYSTEM_ACTOR = "skynet";

  /**
   * La corrección en curso de una puerta que pidió cambios (W5-B).
   *
   * @param approvalId la petición que se cerró pidiendo cambios
   * @param agentRunId la invocación que corrige, en la fase {@code revise}
   */
  record Correction(UUID approvalId, int round, String stageKey, UUID agentRunId) {}

  /** Lo que se puede hacer con una petición. */
  public enum Action {
    APPROVE,
    REJECT,
    REQUEST_CHANGES,
    COMMENT
  }

  private static final int MAX_LIST = 200;
  private static final int MAX_COMMENT = 10_000;

  private final JdbcClient jdbc;
  private final ObjectMapper json;
  private final WorkflowRunRepository workflowRuns;
  private final StageRunRepository stageRuns;
  private final AgentRunRepository agentRuns;
  private final RunTransitions transitions;
  private final Verifications verifications;
  private final ProjectService projects;
  private final ApplicationEventPublisher publisher;
  private final ObjectProvider<RunService> runs;
  private final TimeSource time;

  Approvals(
      JdbcClient jdbc,
      ObjectMapper json,
      WorkflowRunRepository workflowRuns,
      StageRunRepository stageRuns,
      AgentRunRepository agentRuns,
      RunTransitions transitions,
      Verifications verifications,
      ProjectService projects,
      ApplicationEventPublisher publisher,
      ObjectProvider<RunService> runs,
      TimeSource time) {
    this.jdbc = jdbc;
    this.json = json;
    this.workflowRuns = workflowRuns;
    this.stageRuns = stageRuns;
    this.agentRuns = agentRuns;
    this.transitions = transitions;
    this.verifications = verifications;
    this.projects = projects;
    this.publisher = publisher;
    this.runs = runs;
    this.time = time;
  }

  /**
   * La puerta {@code stage} empieza a esperar: pasa a {@code WAITING_FOR_APPROVAL} y deja una
   * petición con la acción ya resuelta ({@code action}) y la foto de lo que hay que enseñar al
   * decidir. También vuelve a preguntar, con una ronda más, cuando termina bien la corrección que
   * se pidió ({@code RUNNING}). La llama el motor, dentro de su transacción.
   */
  void request(
      WorkflowRun run,
      StageRun stage,
      PublishedDefinition published,
      String action,
      WorkItem workItem,
      CodeRepository repository,
      Instant now) {
    StageDefinition gate = published.definition().stage(stage.getStageKey()).orElseThrow();
    stage = transitions.advanceStage(stage, StageStatus.STARTING, "approval-requested", now);
    stage = transitions.advanceStage(stage, StageStatus.RUNNING, "approval-requested", now);
    stage =
        transitions.advanceStage(
            stage, StageStatus.WAITING_FOR_APPROVAL, "approval-requested", now);
    int round =
        jdbc.sql("SELECT coalesce(max(round), 0) + 1 FROM approval_request WHERE stage_run_id = ?")
            .param(stage.getId())
            .query(Integer.class)
            .single();
    String risk = gate.approval().risk().name();
    Map<String, Object> evidence =
        evidence(
            run, stage, published, workItem, repository, projects.get(workItem.getProjectId()));
    evidence.put(
        "changesRequested", round == 1 ? null : changesRequested(stage.getId(), round - 1));
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO approval_request (id, workflow_run_id, stage_run_id, round, action, risk,"
                + " evidence, status, created_at) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)")
        .params(
            id,
            run.getId(),
            stage.getId(),
            round,
            action,
            risk,
            json.writeValueAsString(evidence),
            PENDING,
            Timestamp.from(now))
        .update();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("approvalId", id);
    payload.put("stageKey", stage.getStageKey());
    payload.put("round", round);
    payload.put("action", action);
    payload.put("risk", risk);
    transitions.append("approval_request", id, "approval.requested", run.getId(), payload, now);
  }

  /**
   * Decide sobre la petición {@code id} o la comenta. Aprobar cierra la puerta bien y deja arrancar
   * a sus dependientes; rechazar la hace fallar (con el comentario, obligatorio, como motivo) y se
   * aplica el fail-fast. Pedir cambios manda el comentario a la fase {@code revise}, que lo corrige
   * en su sesión mientras la puerta queda «Corrigiendo» ({@code RUNNING}); al terminar, el motor
   * vuelve a preguntar ({@link RunSteps#settleCorrections}). Comentar no cambia nada más. Una
   * petición ya decidida responde 409.
   */
  @Transactional
  public ApprovalView decide(UUID id, Action action, String comment, String actor) {
    String text = comment == null || comment.isBlank() ? null : comment.strip();
    if (text != null && text.length() > MAX_COMMENT) {
      throw new InvalidRequestException(
          "El comentario ocupa más de " + MAX_COMMENT + " caracteres");
    }
    if (text == null && action != Action.APPROVE) {
      throw new InvalidRequestException(
          switch (action) {
            case REJECT -> "Para rechazar hace falta un comentario con el motivo";
            case REQUEST_CHANGES -> "Para pedir cambios hace falta un comentario con qué cambiar";
            default -> "El comentario está vacío";
          });
    }
    UUID runId =
        jdbc.sql("SELECT workflow_run_id FROM approval_request WHERE id = ?")
            .param(id)
            .query(UUID.class)
            .optional()
            .orElseThrow(() -> new NotFoundException("Aprobación", id));
    // Con la ejecución bloqueada, la decisión no se cruza con el motor ni con otra decisión.
    jdbc.sql("SELECT id FROM workflow_run WHERE id = ? FOR UPDATE")
        .param(runId)
        .query(UUID.class)
        .single();
    ApprovalSummary request = summary(id);
    Instant now = time.now();
    if (action == Action.COMMENT) {
      record(id, action.name(), text, actor, now);
      append(request, "approval.commented", actor, text, now);
      return view(id);
    }
    if (!PENDING.equals(request.status())) {
      throw new ConflictException(
          "La aprobación ya está decidida (" + request.status().toLowerCase(Locale.ROOT) + ")");
    }
    StageRun stage = stageRuns.findById(request.stageRunId()).orElseThrow();
    if (action == Action.REQUEST_CHANGES) {
      requestChanges(request, stage, text, actor, now);
      publisher.publishEvent(new WorkflowRunChanged(runId));
      return view(id);
    }
    String status = action == Action.APPROVE ? APPROVED : REJECTED;
    close(id, status, now);
    record(id, action.name(), text, actor, now);
    if (action == Action.APPROVE) {
      transitions.advanceStage(stage, StageStatus.SUCCEEDED, "approval-granted", now);
      append(request, "approval.granted", actor, text, now);
    } else {
      transitions.advanceStage(stage, StageStatus.FAILED, "approval-rejected", now);
      append(request, "approval.rejected", actor, text, now);
    }
    publisher.publishEvent(new WorkflowRunChanged(runId));
    return view(id);
  }

  /**
   * Cierra la petición pidiendo cambios: pone en cola la corrección en la fase {@code revise} de la
   * evidencia y la puerta pasa a {@code RUNNING}. Si no se puede continuar esa sesión (el proveedor
   * no lo permite, su worktree se eliminó), responde 409 y la petición sigue pendiente.
   */
  private void requestChanges(
      ApprovalSummary request, StageRun stage, String comment, String actor, Instant now) {
    String reviseKey = reviseKey(request.id());
    if (reviseKey == null) {
      throw new ConflictException(
          "Esta puerta no tiene a quién pedir cambios: indica en `revise` la fase de agente que"
              + " corrige");
    }
    StageRun revise = latestStages(request.workflowRunId()).get(reviseKey);
    if (revise == null || revise.getStatus() != StageStatus.SUCCEEDED) {
      throw new ConflictException(
          "La fase " + reviseKey + " no ha terminado bien: no se le pueden pedir cambios");
    }
    WorkflowRun run = workflowRuns.findById(request.workflowRunId()).orElseThrow();
    AgentRun correction =
        runs.getObject().queueCorrection(run, revise, correctionPrompt(request, comment), now);
    jdbc.sql(
            "UPDATE approval_request SET status = ?, decided_at = ?, correction_agent_run_id = ?"
                + " WHERE id = ? AND status = ?")
        .params(CHANGES_REQUESTED, Timestamp.from(now), correction.getId(), request.id(), PENDING)
        .update();
    record(request.id(), Action.REQUEST_CHANGES.name(), comment, actor, now);
    transitions.advanceStage(stage, StageStatus.RUNNING, "changes-requested", now);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("approvalId", request.id());
    payload.put("stageKey", request.stageKey());
    payload.put("round", request.round());
    payload.put("actor", actor);
    payload.put("comment", comment);
    payload.put("reviseStageKey", reviseKey);
    payload.put("correctionAgentRunId", correction.getId());
    transitions.append(
        "approval_request",
        request.id(),
        "approval.changes_requested",
        request.workflowRunId(),
        payload,
        now);
  }

  /** Lo que recibe el agente que corrige: qué se revisaba y el comentario. */
  private static String correctionPrompt(ApprovalSummary request, String comment) {
    return "Al revisar «"
        + request.action()
        + "» han pedido estos cambios. Aplícalos y, si tu tarea pide una salida estructurada,"
        + " devuélvela completa de nuevo.\n\n"
        + comment;
  }

  /** La fase {@code revise} de la petición, tal como quedó en su evidencia. */
  private String reviseKey(UUID requestId) {
    return jdbc.sql("SELECT evidence -> 'revise' ->> 'stageKey' FROM approval_request WHERE id = ?")
        .param(requestId)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  /**
   * La corrección de la puerta {@code stageRunId}, si su última petición se cerró pidiendo cambios.
   */
  Optional<Correction> correction(UUID stageRunId) {
    return jdbc.sql(
            "SELECT id, round, correction_agent_run_id,"
                + " evidence -> 'revise' ->> 'stageKey' AS revise, status"
                + " FROM approval_request WHERE stage_run_id = ? ORDER BY round DESC LIMIT 1")
        .param(stageRunId)
        .query(
            (rs, row) ->
                Map.entry(
                    rs.getString("status"),
                    new Correction(
                        rs.getObject("id", UUID.class),
                        rs.getInt("round"),
                        rs.getString("revise"),
                        rs.getObject("correction_agent_run_id", UUID.class))))
        .optional()
        .filter(e -> CHANGES_REQUESTED.equals(e.getKey()))
        .map(Map.Entry::getValue);
  }

  /**
   * La corrección no ha terminado bien: la puerta falla ({@code correction-failed}) o se cancela
   * ({@code correction-cancelled}) con el motivo en {@code approval.correction.failed}.
   */
  void correctionFailed(
      StageRun stage, Correction correction, boolean cancelled, String error, Instant now) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("approvalId", correction.approvalId());
    payload.put("stageKey", stage.getStageKey());
    payload.put("round", correction.round());
    payload.put("reviseStageKey", correction.stageKey());
    payload.put("correctionAgentRunId", correction.agentRunId());
    payload.put("cancelled", cancelled);
    if (error != null) {
      payload.put("error", error);
    }
    transitions.append(
        "approval_request",
        correction.approvalId(),
        "approval.correction.failed",
        stage.getWorkflowRunId(),
        payload,
        now);
    transitions.advanceStage(
        stage,
        cancelled ? StageStatus.CANCELLED : StageStatus.FAILED,
        cancelled ? "correction-cancelled" : "correction-failed",
        now);
  }

  /** Los cambios que se pidieron en la ronda {@code round}: quién, cuándo y qué. */
  private Map<String, Object> changesRequested(UUID stageRunId, int round) {
    return jdbc.sql(
            "SELECT a.id, d.comment, d.actor, d.created_at, a.correction_agent_run_id"
                + " FROM approval_request a JOIN approval_decision d"
                + " ON d.approval_request_id = a.id AND d.action = 'REQUEST_CHANGES'"
                + " WHERE a.stage_run_id = ? AND a.round = ?")
        .params(stageRunId, round)
        .query(
            (rs, row) ->
                map(
                    "approvalId",
                    rs.getObject("id", UUID.class),
                    "round",
                    round,
                    "comment",
                    rs.getString("comment"),
                    "actor",
                    rs.getString("actor"),
                    "createdAt",
                    rs.getTimestamp("created_at").toInstant().toString(),
                    "correctionAgentRunId",
                    rs.getObject("correction_agent_run_id", UUID.class)))
        .optional()
        .orElse(null);
  }

  /**
   * Cancela la petición pendiente de {@code stage}, si la tiene, porque se cancela la fase: queda
   * firmado por {@code skynet} con el motivo.
   */
  void cancelPending(StageRun stage, String reason, Instant now) {
    jdbc.sql(
            "SELECT id FROM approval_request WHERE stage_run_id = ? AND status = ?"
                + " ORDER BY round")
        .params(stage.getId(), PENDING)
        .query(UUID.class)
        .list()
        .forEach(
            id -> {
              close(id, CANCELLED, now);
              record(id, "CANCEL", reason, SYSTEM_ACTOR, now);
              append(summary(id), "approval.cancelled", SYSTEM_ACTOR, reason, now);
            });
  }

  /**
   * Las peticiones, de las pendientes (las de más riesgo y las más antiguas primero) a las
   * decididas (las más recientes primero). Filtran el estado, el proyecto y el riesgo; {@code null}
   * no filtra.
   */
  @Transactional(readOnly = true)
  public List<ApprovalSummary> list(Set<String> statuses, UUID projectId, String risk) {
    return jdbc.sql(
            "SELECT a.* FROM approval_request a"
                + " JOIN workflow_run r ON r.id = a.workflow_run_id"
                + " JOIN work_item w ON w.id = r.work_item_id"
                + " WHERE (CAST(:noStatus AS boolean) OR a.status IN (:statuses))"
                + " AND (CAST(:project AS uuid) IS NULL OR w.project_id = :project)"
                + " AND (CAST(:risk AS text) IS NULL OR a.risk = :risk)"
                + " ORDER BY (a.status = 'PENDING') DESC,"
                + " CASE WHEN a.status = 'PENDING' THEN"
                + " CASE a.risk WHEN 'HIGH' THEN 0 WHEN 'MEDIUM' THEN 1 ELSE 2 END END,"
                + " CASE WHEN a.status = 'PENDING' THEN a.created_at END ASC,"
                + " a.decided_at DESC, a.id"
                + " LIMIT "
                + MAX_LIST)
        .param("noStatus", statuses == null || statuses.isEmpty())
        .param("statuses", statuses == null || statuses.isEmpty() ? List.of(PENDING) : statuses)
        .param("project", projectId)
        .param("risk", risk)
        .query(this::row)
        .list();
  }

  @Transactional(readOnly = true)
  public ApprovalView view(UUID id) {
    ApprovalSummary summary = summary(id);
    String evidence =
        jdbc.sql("SELECT CAST(evidence AS text) FROM approval_request WHERE id = ?")
            .param(id)
            .query(String.class)
            .single();
    List<ApprovalView.Decision> decisions =
        jdbc.sql(
                "SELECT * FROM approval_decision WHERE approval_request_id = ?"
                    + " ORDER BY created_at, id")
            .param(id)
            .query(
                (rs, row) ->
                    new ApprovalView.Decision(
                        rs.getObject("id", UUID.class),
                        rs.getString("action"),
                        rs.getString("comment"),
                        rs.getString("actor"),
                        rs.getTimestamp("created_at").toInstant()))
            .list();
    return new ApprovalView(
        summary.id(),
        summary.workflowRunId(),
        summary.stageRunId(),
        summary.stageKey(),
        summary.round(),
        summary.action(),
        summary.risk(),
        summary.status(),
        summary.createdAt(),
        summary.decidedAt(),
        summary.correctionAgentRunId(),
        json.readValue(evidence, new TypeReference<LinkedHashMap<String, Object>>() {}),
        decisions);
  }

  /** La última petición de cada una de {@code stageRunIds} que la tenga, por fase. */
  Map<UUID, ApprovalSummary> latestOfStages(List<UUID> stageRunIds) {
    Map<UUID, ApprovalSummary> latest = new LinkedHashMap<>();
    if (stageRunIds.isEmpty()) {
      return latest;
    }
    jdbc.sql("SELECT * FROM approval_request WHERE stage_run_id IN (:ids) ORDER BY round")
        .param("ids", stageRunIds)
        .query(this::row)
        .list()
        .forEach(a -> latest.put(a.stageRunId(), a));
    return latest;
  }

  private ApprovalSummary summary(UUID id) {
    return jdbc.sql("SELECT * FROM approval_request WHERE id = ?")
        .param(id)
        .query(this::row)
        .optional()
        .orElseThrow(() -> new NotFoundException("Aprobación", id));
  }

  private ApprovalSummary row(ResultSet rs, int row) throws SQLException {
    UUID stageRunId = rs.getObject("stage_run_id", UUID.class);
    Map<String, Object> evidence =
        json.readValue(
            rs.getString("evidence"), new TypeReference<LinkedHashMap<String, Object>>() {});
    @SuppressWarnings("unchecked")
    Map<String, Object> origin = (Map<String, Object>) evidence.getOrDefault("origin", Map.of());
    @SuppressWarnings("unchecked")
    Map<String, Object> stage = (Map<String, Object>) origin.getOrDefault("stage", Map.of());
    Timestamp decided = rs.getTimestamp("decided_at");
    return new ApprovalSummary(
        rs.getObject("id", UUID.class),
        rs.getObject("workflow_run_id", UUID.class),
        stageRunId,
        (String) stage.get("key"),
        rs.getInt("round"),
        rs.getString("action"),
        rs.getString("risk"),
        rs.getString("status"),
        rs.getTimestamp("created_at").toInstant(),
        decided == null ? null : decided.toInstant(),
        rs.getObject("correction_agent_run_id", UUID.class),
        origin);
  }

  private void close(UUID id, String status, Instant now) {
    jdbc.sql("UPDATE approval_request SET status = ?, decided_at = ? WHERE id = ? AND status = ?")
        .params(status, Timestamp.from(now), id, PENDING)
        .update();
  }

  private void record(UUID requestId, String action, String comment, String actor, Instant now) {
    jdbc.sql(
            "INSERT INTO approval_decision (id, approval_request_id, action, comment, actor,"
                + " created_at) VALUES (?, ?, ?, ?, ?, ?)")
        .params(UUID.randomUUID(), requestId, action, comment, actor, Timestamp.from(now))
        .update();
  }

  private void append(
      ApprovalSummary request, String type, String actor, String comment, Instant now) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("approvalId", request.id());
    payload.put("stageKey", request.stageKey());
    payload.put("round", request.round());
    payload.put("actor", actor);
    if (comment != null) {
      payload.put("comment", comment);
    }
    transitions.append(
        "approval_request", request.id(), type, request.workflowRunId(), payload, now);
  }

  // ---- Evidencia ------------------------------------------------------------------------------

  /**
   * La foto que se enseña al decidir: de dónde viene la puerta, qué dejaron las fases de las que
   * depende, lo que cuesta la ejecución hasta ahora, lo que se permitirá a las fases que desbloquea
   * y qué pasa con cada decisión.
   */
  private Map<String, Object> evidence(
      WorkflowRun run,
      StageRun stage,
      PublishedDefinition published,
      WorkItem workItem,
      CodeRepository repository,
      Project project) {
    WorkflowDefinition definition = published.definition();
    StageDefinition gate = definition.stage(stage.getStageKey()).orElseThrow();
    Map<String, StageRun> stages = latestStages(run.getId());

    Map<String, Object> origin = new LinkedHashMap<>();
    origin.put(
        "project", map("id", project.getId(), "key", project.getKey(), "name", project.getName()));
    origin.put(
        "workItem",
        map("id", workItem.getId(), "key", workItem.getKey(), "title", workItem.getTitle()));
    origin.put("repository", map("id", repository.getId(), "name", repository.getName()));
    origin.put("run", map("id", run.getId()));
    origin.put(
        "workflow",
        map("key", published.key(), "version", published.version(), "name", definition.name()));
    origin.put("stage", map("key", gate.id(), "name", gate.name()));

    String reviseKey = definition.revise(gate.id());
    Map<String, Object> revise = null;
    if (reviseKey != null && stages.containsKey(reviseKey)) {
      List<AgentRun> agents = agentsOf(stages.get(reviseKey));
      revise = new LinkedHashMap<>();
      revise.put("stageKey", reviseKey);
      revise.put("agentRunId", agents.isEmpty() ? null : agents.getLast().getId());
    }

    List<Map<String, Object>> dependencies = new ArrayList<>();
    for (Dependency dependency : gate.dependsOn()) {
      StageRun dependencyRun = stages.get(dependency.stage());
      StageDefinition dependencyDefinition = definition.stage(dependency.stage()).orElse(null);
      if (dependencyRun == null || dependencyDefinition == null) {
        continue;
      }
      dependencies.add(dependency(dependencyRun, dependencyDefinition));
    }

    List<StageDefinition> unlocked =
        definition.dependents(gate.id()).stream()
            .map(id -> definition.stage(id).orElseThrow())
            .toList();
    List<String> cancelled =
        definition.stages().stream()
            .map(StageDefinition::id)
            .filter(id -> !id.equals(gate.id()))
            .filter(id -> stages.get(id) == null || !stages.get(id).getStatus().isTerminal())
            .toList();
    Map<String, Object> consequences = new LinkedHashMap<>();
    consequences.put(
        "approve",
        unlocked.isEmpty()
            ? "La puerta se cierra y la ejecución sigue."
            : "Pueden arrancar: " + String.join(", ", ids(unlocked)) + ".");
    consequences.put(
        "reject",
        cancelled.isEmpty()
            ? "La ejecución falla."
            : "La ejecución falla y se cancelan " + String.join(", ", cancelled) + ".");
    consequences.put(
        "requestChanges",
        revise == null
            ? null
            : reviseKey + " recibe tu comentario y la puerta vuelve a preguntar con lo corregido.");

    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("origin", origin);
    evidence.put("revise", revise);
    evidence.put("dependencies", dependencies);
    evidence.put("costUsd", runCost(run.getId()));
    evidence.put(
        "grants",
        RunService.stagePolicies(projects.agentPolicy(repository), definition, unlocked).stream()
            .filter(p -> p.type() != StageType.HUMAN_APPROVAL && p.type() != StageType.CONDITIONAL)
            .toList());
    evidence.put("consequences", consequences);
    return evidence;
  }

  /** Lo que dejó una fase de la que depende la puerta: el último intento de cada agente. */
  private Map<String, Object> dependency(StageRun stage, StageDefinition definition) {
    Map<UUID, VerificationResult> checks = verifications.latestOfStage(stage.getId());
    Map<String, AgentRun> latest = new LinkedHashMap<>();
    for (AgentRun agent : agentsOf(stage)) {
      latest.put(agent.getChildKey() == null ? "" : agent.getChildKey(), agent);
    }
    List<Map<String, Object>> agents = new ArrayList<>();
    for (AgentRun agent : latest.values()) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("agentRunId", agent.getId());
      entry.put("childKey", agent.getChildKey());
      entry.put("status", agent.getStatus());
      entry.put("provider", agent.getProvider());
      entry.put("costUsd", agent.getCostUsd());
      entry.put(
          "structuredOutput",
          agent.getStructuredOutput() == null
              ? null
              : json.readValue(agent.getStructuredOutput(), Object.class));
      VerificationResult verification = checks.get(agent.getId());
      entry.put("checks", verification == null ? List.of() : verification.checks());
      entry.put("changes", changes(agent.getId()));
      entry.put("artifacts", artifacts(agent.getId()));
      agents.add(entry);
    }
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("stageKey", stage.getStageKey());
    entry.put("name", definition.name());
    entry.put("type", definition.type().yaml());
    entry.put("status", stage.getStatus());
    entry.put("agents", agents);
    return entry;
  }

  /** El resumen de los cambios del worktree ({@code GIT_CHANGES}), o {@code null} si no lo hay. */
  private Object changes(UUID agentRunId) {
    return jdbc.sql(
            "SELECT CAST(metadata AS text) FROM artifact WHERE agent_run_id = ?"
                + " AND verification_run_id IS NULL AND type = 'GIT_CHANGES'"
                + " ORDER BY created_at DESC LIMIT 1")
        .param(agentRunId)
        .query(String.class)
        .optional()
        .map(m -> (Object) json.readValue(m, new TypeReference<LinkedHashMap<String, Object>>() {}))
        .orElse(null);
  }

  /** Los artefactos de la invocación, sin los de sus verificaciones. */
  private List<Map<String, Object>> artifacts(UUID agentRunId) {
    return jdbc.sql(
            "SELECT id, type, name FROM artifact WHERE agent_run_id = ?"
                + " AND verification_run_id IS NULL ORDER BY created_at, name")
        .param(agentRunId)
        .query(
            (rs, row) ->
                map(
                    "id",
                    rs.getObject("id", UUID.class),
                    "type",
                    rs.getString("type"),
                    "name",
                    rs.getString("name")))
        .list();
  }

  private BigDecimal runCost(UUID runId) {
    return jdbc.sql(
            "SELECT coalesce(sum(a.cost_usd), 0) FROM agent_run a"
                + " JOIN stage_run s ON s.id = a.stage_run_id WHERE s.workflow_run_id = ?")
        .param(runId)
        .query(BigDecimal.class)
        .single();
  }

  private List<AgentRun> agentsOf(StageRun stage) {
    return agentRuns.findByStageRunIdInOrderByCreatedAtAsc(List.of(stage.getId()));
  }

  private Map<String, StageRun> latestStages(UUID runId) {
    Map<String, StageRun> latest = new LinkedHashMap<>();
    stageRuns.findByWorkflowRunIdInOrderByCreatedAtAsc(List.of(runId)).stream()
        .sorted(Comparator.comparing(StageRun::getAttempt))
        .forEach(s -> latest.put(s.getStageKey(), s));
    return latest;
  }

  private static List<String> ids(List<StageDefinition> stages) {
    return stages.stream().map(StageDefinition::id).toList();
  }

  /** Un mapa con orden que admite valores {@code null}. */
  private static Map<String, Object> map(Object... pairs) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      map.put((String) pairs[i], pairs[i + 1]);
    }
    return map;
  }
}
