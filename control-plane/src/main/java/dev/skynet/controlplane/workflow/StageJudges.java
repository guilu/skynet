package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.definition.AgentDefinition;
import dev.skynet.controlplane.definition.JudgeSpec;
import dev.skynet.controlplane.definition.StageDefinition;
import dev.skynet.controlplane.definition.WorkflowDefinition;
import dev.skynet.controlplane.definition.WorkflowDefinitions;
import dev.skynet.controlplane.project.CodeRepository;
import dev.skynet.controlplane.project.ProjectService;
import dev.skynet.controlplane.shared.ConflictException;
import dev.skynet.controlplane.shared.TimeSource;
import dev.skynet.controlplane.workflow.JudgeVerdicts.Aggregate;
import dev.skynet.controlplane.workflow.JudgeVerdicts.CriterionScore;
import dev.skynet.controlplane.workflow.JudgeVerdicts.Finding;
import dev.skynet.controlplane.workflow.JudgeVerdicts.Verdict;
import dev.skynet.controlplane.workitem.WorkItem;
import dev.skynet.controlplane.workitem.WorkItemService;
import dev.skynet.protocol.AgentObservableStatus;
import dev.skynet.protocol.StageStatus;
import dev.skynet.protocol.runner.AgentLimits;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * El juez de las fases {@code verification} con {@code judge} (VA-B). Cada ronda:
 *
 * <ol>
 *   <li>Las comprobaciones de la fase, si tiene ({@code CHECKING}): lo barato primero.
 *   <li>Si pasan, los jueces a la vez ({@code JUDGING}), cada uno en una copia del worktree, en
 *       solo lectura y sin ver lo que dijo el autor ({@link JudgePrompt}).
 *   <li>Cuando terminan todos, el veredicto ({@link JudgeVerdicts}): si pasa, la fase termina bien.
 *   <li>Si no (o si no pasaron las comprobaciones) y quedan rondas, el agente de {@code fix}
 *       corrige en su sesión con lo que se encontró ({@code FIXING}, como en {@code github-check});
 *       al terminar, empieza la ronda siguiente. Sin rondas, la fase falla con el motivo.
 * </ol>
 *
 * <p>El estado va en {@code stage_judge} y el veredicto de cada juez en {@code stage_verdict}. El
 * motor llama aquí con la ejecución bloqueada.
 */
@Component
class StageJudges {

  static final String CHECKING = "CHECKING";
  static final String JUDGING = "JUDGING";
  static final String REFUTING = "REFUTING";
  static final String FIXING = "FIXING";
  static final String PASSED = "PASSED";
  static final String FAILED = "FAILED";
  static final String SKIPPED = "SKIPPED";

  private final JdbcClient jdbc;
  private final AgentRunRepository agentRuns;
  private final StageRunRepository stageRuns;
  private final WorkflowRunRepository workflowRuns;
  private final WorkflowDefinitions definitions;
  private final Workspaces workspaces;
  private final Verifications verifications;
  private final RunTransitions transitions;
  private final ProjectService projects;
  private final WorkItemService workItems;
  private final AgentQueue agentQueue;
  private final QuotaWaits quotaWaits;
  private final ObjectProvider<RunService> runs;
  private final RunRisks risks;
  private final ApplicationEventPublisher publisher;
  private final ObjectMapper json;
  private final TimeSource time;

  StageJudges(
      JdbcClient jdbc,
      AgentRunRepository agentRuns,
      StageRunRepository stageRuns,
      WorkflowRunRepository workflowRuns,
      WorkflowDefinitions definitions,
      Workspaces workspaces,
      Verifications verifications,
      RunTransitions transitions,
      ProjectService projects,
      WorkItemService workItems,
      AgentQueue agentQueue,
      QuotaWaits quotaWaits,
      ObjectProvider<RunService> runs,
      RunRisks risks,
      ApplicationEventPublisher publisher,
      ObjectMapper json,
      TimeSource time) {
    this.jdbc = jdbc;
    this.agentRuns = agentRuns;
    this.stageRuns = stageRuns;
    this.workflowRuns = workflowRuns;
    this.definitions = definitions;
    this.workspaces = workspaces;
    this.verifications = verifications;
    this.transitions = transitions;
    this.projects = projects;
    this.workItems = workItems;
    this.agentQueue = agentQueue;
    this.quotaWaits = quotaWaits;
    this.runs = runs;
    this.risks = risks;
    this.publisher = publisher;
    this.json = json;
    this.time = time;
  }

  /** El estado del juez de una fase. */
  record State(
      UUID stageRunId,
      UUID runId,
      String status,
      int round,
      UUID workspaceId,
      UUID fixAgentRunId,
      BigDecimal score,
      Boolean passed,
      Integer approvals,
      Integer judges,
      String findings,
      String criteria,
      String error,
      String refuteNote) {}

  /** Una ronda que tiene que empezar: la fase de {@code fix} ya ha corregido. */
  record NextRound(StageRun stage, StageDefinition definition, WorkspaceView source) {}

  // ---- Arranque y rondas ------------------------------------------------------------------------

  /** La fase empieza a juzgar el worktree {@code source}: ronda 1. */
  void open(StageRun stage, WorkspaceView source, Instant now) {
    jdbc.sql(
            "INSERT INTO stage_judge (stage_run_id, workflow_run_id, status, round, workspace_id,"
                + " created_at, updated_at) VALUES (:stage, :run, :status, 1, :workspace, :now,"
                + " :now) ON CONFLICT (stage_run_id) DO NOTHING")
        .param("stage", stage.getId())
        .param("run", stage.getWorkflowRunId())
        .param("status", CHECKING)
        .param("workspace", source.id())
        .param("now", Timestamp.from(now))
        .update();
  }

  Optional<State> state(UUID stageRunId) {
    return jdbc.sql("SELECT * FROM stage_judge WHERE stage_run_id = ?")
        .param(stageRunId)
        .query(StageJudges::state)
        .optional();
  }

  /**
   * Han terminado las comprobaciones de la ronda: si pasan, entran los jueces; si no, la ronda
   * suspende sin llamarlos.
   */
  void checked(StageRun stage, StageDefinition definition, VerificationResult result, Instant now) {
    State state = state(stage.getId()).orElse(null);
    if (state == null || !CHECKING.equals(state.status()) || stage.getStatus().isTerminal()) {
      return;
    }
    if (Verifications.PASSED.equals(result.status())) {
      WorkspaceView source = workspaces.find(state.workspaceId()).orElse(null);
      if (source == null) {
        fail(stage, state, "El worktree que se juzga ya no existe", now);
        return;
      }
      launch(stage, definition, state.round(), source, result, now);
      return;
    }
    String reason = StageVerifications.reason(result);
    roundFailed(
        stage,
        definition,
        state.round(),
        "Las comprobaciones no pasan: " + reason,
        "Las comprobaciones de la fase `"
            + stage.getStageKey()
            + "` no pasan, así que el juez no ha llegado a mirarlo:\n\n"
            + result.checks().stream()
                .filter(c -> Boolean.FALSE.equals(c.passed()))
                .map(c -> "- `" + c.name() + "`: " + c.reason())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("- " + reason),
        now);
  }

  /**
   * Lanza los jueces de la ronda {@code round}, cada uno en una copia de {@code source}, con su
   * prompt, la salida estructurada del veredicto y en solo lectura. {@code checks} es la
   * verificación de la ronda, o {@code null} si la fase no tiene comprobaciones.
   */
  void launch(
      StageRun stage,
      StageDefinition definition,
      int round,
      WorkspaceView source,
      VerificationResult checks,
      Instant now) {
    JudgeSpec spec = definition.judge();
    WorkflowRun run = workflowRuns.findById(stage.getWorkflowRunId()).orElseThrow();
    WorkflowDefinition workflow = definitions.published(run.getDefinitionId()).definition();
    WorkItem workItem = workItems.get(run.getWorkItemId());
    CodeRepository repository = projects.getRepository(repositoryOf(run));
    try {
      workItems.requireActive(workItem);
      projects.requireActive(repository);
    } catch (ConflictException e) {
      fail(stage, state(stage.getId()).orElseThrow(), e.getMessage(), now);
      return;
    }
    List<JudgePrompt.Check> seen =
        checks == null
            ? List.of()
            : checks.checks().stream()
                .map(
                    c ->
                        new JudgePrompt.Check(
                            c.name(), !Boolean.FALSE.equals(c.passed()), c.reason(), c.warnings()))
                .toList();
    String author = authorProvider(stage, definition, workflow);
    AgentLimits limits = launchLimits(run);
    if (spec.isGuide()) {
      launchGuide(
          stage, spec, round, source, run, workflow, workItem, repository, author, limits, now);
      return;
    }
    // El nivel de lo que se juzga (AU-C): por debajo de minRisk, la fase pasa sin juez.
    Integer tier = risks.workspaceTier(run.getId(), source.id()).orElse(null);
    jdbc.sql("UPDATE stage_judge SET risk = ? WHERE stage_run_id = ?")
        .params(tier, stage.getId())
        .update();
    if (round == 1 && spec.skipsAt(tier)) {
      skip(stage, spec, tier, now);
      return;
    }
    String schema = JudgeVerdicts.outputSchema(spec.rubricFile() != null);
    List<Map<String, Object>> launched = new ArrayList<>();
    for (JudgeSpec.Judge judge : spec.agents()) {
      AgentDefinition agent = workflow.agent(judge.agent()).orElseThrow();
      String prompt =
          JudgePrompt.build(
              new JudgePrompt.Context(
                  workItem.getKey(),
                  workItem.getTitle(),
                  workItem.getDescription(),
                  source.baseCommit(),
                  seen,
                  round,
                  agent.prompt()),
              spec,
              judge);
      EffectivePolicy policy =
          EffectivePolicy.of(projects.agentPolicy(repository), agent, limits)
              .withOutputSchema(schema)
              .withMemory(StageDefinition.MEMORY_OFF)
              .withPermissionMode("plan");
      Invocation invocation =
          routed(stage, Invocation.inCopyOf(prompt, policy, source), agent, author);
      AgentRun queued =
          agentQueue.queue(run, stage, workItem, repository, invocation, judge.agent(), now);
      queued.asJudge();
      if (source.baseCommit() != null) {
        queued.recordBaseCommit(source.baseCommit());
      }
      queued = agentRuns.save(queued);
      jdbc.sql(
              "INSERT INTO stage_verdict (id, stage_run_id, workflow_run_id, round, judge, lens,"
                  + " agent_run_id, status, created_at, updated_at) VALUES (:id, :stage, :run,"
                  + " :round, :judge, :lens, :agent, 'PENDING', :now, :now)")
          .param("id", UUID.randomUUID())
          .param("stage", stage.getId())
          .param("run", run.getId())
          .param("round", round)
          .param("judge", judge.agent())
          .param("lens", judge.lens())
          .param("agent", queued.getId())
          .param("now", Timestamp.from(now))
          .update();
      Map<String, Object> one = new LinkedHashMap<>();
      one.put("agent", judge.agent());
      if (judge.lens() != null) {
        one.put("lens", judge.lens());
      }
      one.put("agentRunId", queued.getId());
      one.put("provider", queued.getProvider());
      launched.add(one);
    }
    status(stage.getId(), JUDGING, round, null, now);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("stageKey", stage.getStageKey());
    payload.put("round", round);
    payload.put("judges", launched);
    transitions.append(
        "stage_run", stage.getId(), "stage.judge.started", run.getId(), payload, now);
  }

  /**
   * El cambio no llega al nivel {@code judge.minRisk} (AU-C): la fase pasa sin llamar a los jueces,
   * con el motivo. Si después sube el nivel (p. ej. lo corrige la CI), la puerta de fusión ya no se
   * aprueba sola.
   */
  private void skip(StageRun stage, JudgeSpec spec, int tier, Instant now) {
    String note = "nivel " + tier + ": sin juez (juzga desde el nivel " + spec.minRisk() + ")";
    jdbc.sql(
            "UPDATE stage_judge SET status = :status, min_risk = :min, error = NULL, updated_at ="
                + " :now WHERE stage_run_id = :stage")
        .param("status", SKIPPED)
        .param("min", spec.minRisk())
        .param("now", Timestamp.from(now))
        .param("stage", stage.getId())
        .update();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("stageKey", stage.getStageKey());
    payload.put("tier", tier);
    payload.put("minRisk", spec.minRisk());
    payload.put("note", note);
    transitions.append(
        "stage_run", stage.getId(), "stage.judge.skipped", stage.getWorkflowRunId(), payload, now);
    transitions.advanceStage(stage, StageStatus.SUCCEEDED, "judge-skipped", now);
    publisher.publishEvent(new WorkflowRunChanged(stage.getWorkflowRunId()));
  }

  /**
   * Lanza al agente que ejecuta la guía de pruebas (VA-D), en una copia de {@code source} y sin
   * memoria. A diferencia de un juez no va en solo lectura: tiene que ejecutar los pasos. Lo que
   * haga en la copia no llega al cambio.
   */
  private void launchGuide(
      StageRun stage,
      JudgeSpec spec,
      int round,
      WorkspaceView source,
      WorkflowRun run,
      WorkflowDefinition workflow,
      WorkItem workItem,
      CodeRepository repository,
      String author,
      AgentLimits limits,
      Instant now) {
    JudgeSpec.Judge judge = spec.agents().getFirst();
    AgentDefinition agent = workflow.agent(judge.agent()).orElseThrow();
    String prompt =
        GuideSteps.prompt(
            workItem.getKey(),
            workItem.getTitle(),
            source.baseCommit(),
            spec.guide(),
            round,
            agent.prompt());
    EffectivePolicy policy =
        EffectivePolicy.of(projects.agentPolicy(repository), agent, limits)
            .withOutputSchema(GuideSteps.outputSchema())
            .withMemory(StageDefinition.MEMORY_OFF);
    Invocation invocation =
        routed(stage, Invocation.inCopyOf(prompt, policy, source), agent, author);
    AgentRun queued =
        agentQueue.queue(run, stage, workItem, repository, invocation, judge.agent(), now);
    queued.asGuide();
    if (source.baseCommit() != null) {
      queued.recordBaseCommit(source.baseCommit());
    }
    queued = agentRuns.save(queued);
    jdbc.sql(
            "INSERT INTO stage_verdict (id, stage_run_id, workflow_run_id, round, judge, lens,"
                + " agent_run_id, status, created_at, updated_at) VALUES (:id, :stage, :run,"
                + " :round, :judge, 'guide', :agent, 'PENDING', :now, :now)")
        .param("id", UUID.randomUUID())
        .param("stage", stage.getId())
        .param("run", run.getId())
        .param("round", round)
        .param("judge", judge.agent())
        .param("agent", queued.getId())
        .param("now", Timestamp.from(now))
        .update();
    status(stage.getId(), JUDGING, round, null, now);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("stageKey", stage.getStageKey());
    payload.put("round", round);
    payload.put("agent", judge.agent());
    payload.put("agentRunId", queued.getId());
    payload.put("provider", queued.getProvider());
    payload.put("paths", spec.guide().paths());
    transitions.append(
        "stage_run", stage.getId(), "stage.guide.started", run.getId(), payload, now);
  }

  /**
   * Lanza los escépticos de la ronda (VA-C): {@code refute} por cada hallazgo de {@code targets},
   * cada uno en una copia del worktree, en solo lectura y sin memoria. Solo ven el hallazgo y el
   * cambio.
   */
  private void launchSkeptics(
      StageRun stage,
      StageDefinition definition,
      State state,
      List<JudgeVerdicts.Target> targets,
      String note,
      Instant now) {
    JudgeSpec spec = definition.judge();
    WorkflowRun run = workflowRuns.findById(stage.getWorkflowRunId()).orElseThrow();
    WorkflowDefinition workflow = definitions.published(run.getDefinitionId()).definition();
    WorkItem workItem = workItems.get(run.getWorkItemId());
    CodeRepository repository = projects.getRepository(repositoryOf(run));
    WorkspaceView source = workspaces.find(state.workspaceId()).orElse(null);
    if (source == null) {
      fail(stage, state, "El worktree que se juzga ya no existe", now);
      return;
    }
    String author = authorProvider(stage, definition, workflow);
    queueSkeptics(
        stage,
        run,
        workflow,
        workItem,
        repository,
        spec,
        author,
        source,
        source.baseCommit(),
        state.round(),
        targets,
        now);
    status(stage.getId(), REFUTING, state.round(), null, now);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("stageKey", stage.getStageKey());
    payload.put("round", state.round());
    payload.put("findings", targets.size());
    payload.put("skeptics", spec.refute());
    payload.put("agent", spec.skeptic());
    if (note != null) {
      payload.put("note", note);
    }
    transitions.append(
        "stage_run", stage.getId(), "stage.judge.refute.started", run.getId(), payload, now);
  }

  /**
   * Pone en cola {@code refute} escépticos por cada hallazgo de {@code targets} (VA-C), como
   * agentes de {@code stage}, cada uno en una copia de {@code source} y mirando el diff desde
   * {@code base}.
   */
  private void queueSkeptics(
      StageRun stage,
      WorkflowRun run,
      WorkflowDefinition workflow,
      WorkItem workItem,
      CodeRepository repository,
      JudgeSpec spec,
      String author,
      WorkspaceView source,
      String base,
      int round,
      List<JudgeVerdicts.Target> targets,
      Instant now) {
    AgentDefinition agent = workflow.agent(spec.skeptic()).orElseThrow();
    AgentLimits limits = launchLimits(run);
    String schema = JudgeVerdicts.refutationSchema();
    JudgePrompt.Context context =
        new JudgePrompt.Context(
            workItem.getKey(),
            workItem.getTitle(),
            workItem.getDescription(),
            base,
            List.of(),
            round,
            null);
    for (int f = 0; f < targets.size(); f++) {
      JudgeVerdicts.Finding finding = targets.get(f).finding();
      String prompt = JudgePrompt.refutation(context, finding);
      for (int k = 1; k <= spec.refute(); k++) {
        EffectivePolicy policy =
            EffectivePolicy.of(projects.agentPolicy(repository), agent, limits)
                .withOutputSchema(schema)
                .withMemory(StageDefinition.MEMORY_OFF)
                .withPermissionMode("plan");
        Invocation invocation =
            routed(stage, Invocation.inCopyOf(prompt, policy, source), agent, author);
        AgentRun queued =
            agentQueue.queue(run, stage, workItem, repository, invocation, spec.skeptic(), now);
        queued.asSkeptic();
        if (base != null) {
          queued.recordBaseCommit(base);
        }
        queued = agentRuns.save(queued);
        jdbc.sql(
                "INSERT INTO stage_refutation (id, stage_run_id, workflow_run_id, round, finding,"
                    + " judge, content, skeptic, agent_run_id, status, created_at, updated_at)"
                    + " VALUES (:id, :stage, :run, :round, :finding, :judge, CAST(:content AS"
                    + " jsonb), :skeptic, :agent, 'PENDING', :now, :now)")
            .param("id", UUID.randomUUID())
            .param("stage", stage.getId())
            .param("run", run.getId())
            .param("round", round)
            .param("finding", f)
            .param("judge", finding.judge())
            .param("content", json.writeValueAsString(findings(List.of(finding)).getFirst()))
            .param("skeptic", k)
            .param("agent", queued.getId())
            .param("now", Timestamp.from(now))
            .update();
      }
    }
  }

  /**
   * Lanza los escépticos de lo que encontraron los jueces del delta (AU-B, AU-C) como agentes de
   * {@code stage}, la fase que espera la CI. Sus respuestas van en {@code stage_refutation} con
   * ella, ronda 1. Devuelve cuántos hallazgos se intentan refutar.
   */
  int launchDeltaSkeptics(
      StageRun stage,
      StageRun judgeStage,
      StageDefinition judgeDefinition,
      WorkspaceView source,
      String judgedSha,
      List<Verdict> verdicts,
      Instant now) {
    JudgeSpec spec = judgeDefinition.judge();
    int budget = JudgeSpec.MAX_AGENT_CALLS - calls(stage.getId());
    List<JudgeVerdicts.Target> targets = JudgeVerdicts.targets(verdicts, spec.refute(), budget);
    if (targets.isEmpty()) {
      return 0;
    }
    WorkflowRun run = workflowRuns.findById(stage.getWorkflowRunId()).orElseThrow();
    WorkflowDefinition workflow = definitions.published(run.getDefinitionId()).definition();
    WorkItem workItem = workItems.get(run.getWorkItemId());
    CodeRepository repository = projects.getRepository(repositoryOf(run));
    String author = authorProvider(judgeStage, judgeDefinition, workflow);
    queueSkeptics(
        stage,
        run,
        workflow,
        workItem,
        repository,
        spec,
        author,
        source,
        judgedSha,
        1,
        targets,
        now);
    return targets.size();
  }

  /**
   * Lo que dijeron los escépticos del delta de {@code stageRunId}: los veredictos de sus jueces con
   * los hallazgos refutados descartados, cuando han terminado todos; vacío mientras falte alguno.
   */
  Optional<DeltaOutcome> collectDeltaRefutations(UUID stageRunId, JudgeSpec spec, Instant now) {
    List<RefutationRow> rows = refutationRows(stageRunId, 1);
    Map<UUID, AgentRun> agents = new LinkedHashMap<>();
    for (RefutationRow row : rows) {
      AgentRun agent = agentRuns.findById(row.agentRunId()).orElseThrow();
      if (!agent.getStatus().isTerminal()) {
        return Optional.empty();
      }
      agents.put(row.agentRunId(), agent);
    }
    Map<Integer, List<JudgeVerdicts.Refutation>> byFinding = new LinkedHashMap<>();
    for (RefutationRow row : rows) {
      AgentRun agent = agents.get(row.agentRunId());
      if (agent.getStatus() == AgentObservableStatus.CANCELLED) {
        return Optional.of(new DeltaOutcome(null, List.of(), true, "Se canceló un escéptico"));
      }
      JudgeVerdicts.Refutation said =
          agent.getStatus() == AgentObservableStatus.COMPLETED
                  && agent.getStructuredOutput() != null
              ? JudgeVerdicts.refutation(json.readTree(agent.getStructuredOutput()))
              : null;
      saveRefutation(
          row,
          said,
          said != null
              ? null
              : agent.getStatus() != AgentObservableStatus.COMPLETED
                  ? "no terminó bien" + (agent.getError() == null ? "" : ": " + agent.getError())
                  : "no devolvió su respuesta",
          now);
      byFinding
          .computeIfAbsent(row.finding(), k -> new ArrayList<>())
          .add(said == null ? new JudgeVerdicts.Refutation(false, null) : said);
    }
    List<Verdict> after = refuted(stageRunId, 1, spec, byFinding, now);
    return Optional.of(new DeltaOutcome(JudgeVerdicts.aggregate(after), after, false, null));
  }

  /** El nivel de lo que se juzgó en la última ronda de la fase, si se midió (AU-C). */
  private Integer riskOf(UUID stageRunId) {
    return jdbc.sql("SELECT risk FROM stage_judge WHERE stage_run_id = ?")
        .param(stageRunId)
        .query((rs, n) -> rs.getObject("risk", Integer.class))
        .optional()
        .orElse(null);
  }

  /** Cuántas llamadas a agentes lleva la fase entre jueces y escépticos, en todas las rondas. */
  private int calls(UUID stageRunId) {
    return jdbc.sql(
            "SELECT count(*) FROM agent_run WHERE stage_run_id = ? AND role IN ('judge',"
                + " 'skeptic')")
        .param(stageRunId)
        .query(Integer.class)
        .single();
  }

  private void refuteNote(UUID stageRunId, String note, Instant now) {
    jdbc.sql("UPDATE stage_judge SET refute_note = ?, updated_at = ? WHERE stage_run_id = ?")
        .params(note, Timestamp.from(now), stageRunId)
        .update();
  }

  /**
   * Un juez con reserva cuyo principal está sin cuota arranca con la reserva (CQ-B), salvo que la
   * reserva sea el proveedor del autor: entonces sigue con el suyo, que esperará la cuota.
   */
  private Invocation routed(
      StageRun stage, Invocation invocation, AgentDefinition agent, String authorProvider) {
    if (agent == null
        || !agent.fallsBack()
        || agent.fallback().provider().equals(authorProvider)
        || !invocation.policy().providerOrDefault().equals(agent.provider())) {
      return invocation;
    }
    return quotaWaits
        .route(agent, stage.getId())
        .map(
            primary ->
                invocation.asFallback(
                    invocation
                        .policy()
                        .withProvider(agent.fallback().provider(), agent.fallback().model()),
                    new Invocation.Replacing(
                        primary.provider(), primary.window(), primary.resetsAt())))
        .orElse(invocation);
  }

  // ---- El delta tras el juicio (AU-B) -----------------------------------------------------------

  /**
   * Lanza los jueces de {@code judgeStage} sobre lo que cambió después de que aprobaran (AU-B):
   * cada uno en una copia de {@code source}, en solo lectura, mirando el diff desde {@code
   * judgedSha}. Son agentes de {@code stage} (la fase que espera, p. ej. una {@code github-check})
   * y sus veredictos van en {@code stage_verdict} con ella, ronda 1. En el nivel 1 juzga solo el
   * primer juez; en el 2, todos. Devuelve los jueces lanzados.
   */
  List<Map<String, Object>> launchDelta(
      StageRun stage,
      StageRun judgeStage,
      StageDefinition judgeDefinition,
      WorkspaceView source,
      String judgedSha,
      int tier,
      Instant now) {
    JudgeSpec spec = judgeDefinition.judge();
    WorkflowRun run = workflowRuns.findById(stage.getWorkflowRunId()).orElseThrow();
    WorkflowDefinition workflow = definitions.published(run.getDefinitionId()).definition();
    WorkItem workItem = workItems.get(run.getWorkItemId());
    CodeRepository repository = projects.getRepository(repositoryOf(run));
    workItems.requireActive(workItem);
    projects.requireActive(repository);
    String author = authorProvider(judgeStage, judgeDefinition, workflow);
    AgentLimits limits = launchLimits(run);
    String schema = JudgeVerdicts.outputSchema(spec.rubricFile() != null);
    List<JudgeSpec.Judge> panel = tier >= 2 ? spec.agents() : spec.agents().subList(0, 1);
    List<Map<String, Object>> launched = new ArrayList<>();
    for (JudgeSpec.Judge judge : panel) {
      AgentDefinition agent = workflow.agent(judge.agent()).orElseThrow();
      String prompt =
          JudgePrompt.delta(
              new JudgePrompt.Context(
                  workItem.getKey(),
                  workItem.getTitle(),
                  workItem.getDescription(),
                  judgedSha,
                  List.of(),
                  1,
                  agent.prompt()),
              spec,
              judge,
              tier);
      EffectivePolicy policy =
          EffectivePolicy.of(projects.agentPolicy(repository), agent, limits)
              .withOutputSchema(schema)
              .withMemory(StageDefinition.MEMORY_OFF)
              .withPermissionMode("plan");
      Invocation invocation =
          routed(stage, Invocation.inCopyOf(prompt, policy, source), agent, author);
      AgentRun queued =
          agentQueue.queue(run, stage, workItem, repository, invocation, judge.agent(), now);
      queued.asJudge();
      queued.recordBaseCommit(judgedSha);
      queued = agentRuns.save(queued);
      jdbc.sql(
              "INSERT INTO stage_verdict (id, stage_run_id, workflow_run_id, round, judge, lens,"
                  + " agent_run_id, status, created_at, updated_at) VALUES (:id, :stage, :run, 1,"
                  + " :judge, :lens, :agent, 'PENDING', :now, :now)")
          .param("id", UUID.randomUUID())
          .param("stage", stage.getId())
          .param("run", run.getId())
          .param("judge", judge.agent())
          .param("lens", judge.lens())
          .param("agent", queued.getId())
          .param("now", Timestamp.from(now))
          .update();
      Map<String, Object> one = new LinkedHashMap<>();
      one.put("agent", judge.agent());
      if (judge.lens() != null) {
        one.put("lens", judge.lens());
      }
      one.put("agentRunId", queued.getId());
      one.put("provider", queued.getProvider());
      launched.add(one);
    }
    return launched;
  }

  /**
   * Lo que dijeron los jueces del delta de {@code stageRunId} (AU-B), cuando han terminado todos;
   * vacío mientras falte alguno. Guarda el veredicto de cada uno.
   */
  Optional<DeltaOutcome> collectDelta(UUID stageRunId, JudgeSpec spec, Instant now) {
    List<VerdictRow> rows = verdictRows(stageRunId, 1);
    Map<UUID, AgentRun> agents = new LinkedHashMap<>();
    for (VerdictRow row : rows) {
      AgentRun agent = agentRuns.findById(row.agentRunId()).orElseThrow();
      if (!agent.getStatus().isTerminal()) {
        return Optional.empty();
      }
      agents.put(row.agentRunId(), agent);
    }
    List<Verdict> verdicts = new ArrayList<>();
    for (VerdictRow row : rows) {
      AgentRun agent = agents.get(row.agentRunId());
      if (agent.getStatus() == AgentObservableStatus.CANCELLED) {
        verdictFailed(row, "Se canceló", now);
        return Optional.of(
            new DeltaOutcome(null, List.of(), true, "Se canceló el juez " + row.judge()));
      }
      JsonNode output =
          agent.getStructuredOutput() == null ? null : json.readTree(agent.getStructuredOutput());
      if (agent.getStatus() != AgentObservableStatus.COMPLETED
          || output == null
          || !output.isObject()) {
        String error =
            agent.getStatus() != AgentObservableStatus.COMPLETED
                ? "no terminó bien" + (agent.getError() == null ? "" : ": " + agent.getError())
                : "no devolvió su veredicto";
        verdictFailed(row, error, now);
        return Optional.of(
            new DeltaOutcome(null, List.of(), false, "El juez `" + row.judge() + "` " + error));
      }
      Verdict verdict = JudgeVerdicts.verdict(row.judge(), row.lens(), spec, output);
      saveVerdict(row, verdict, now);
      verdicts.add(verdict);
    }
    return Optional.of(new DeltaOutcome(JudgeVerdicts.aggregate(verdicts), verdicts, false, null));
  }

  /**
   * Lo que dijeron los jueces del delta: el veredicto conjunto, o {@code error} si alguno no llegó
   * a darlo ({@code cancelled} si se canceló).
   */
  record DeltaOutcome(
      Aggregate aggregate, List<Verdict> verdicts, boolean cancelled, String error) {}

  /** Los hallazgos de un veredicto, como se guardan. */
  static List<Map<String, Object>> findingsOf(List<Finding> findings) {
    return findings(findings);
  }

  // ---- Veredicto --------------------------------------------------------------------------------

  /**
   * Si ya han terminado los jueces de la ronda, la cierra: el veredicto de cada uno, el de la fase
   * y, con él, la fase termina bien o pasa a corregir (o falla). Un juez que no termina bien (o no
   * devuelve su veredicto) hace fallar la fase: no es culpa del autor, y corregir no lo arregla.
   */
  void settle(StageRun stage, StageDefinition definition, Instant now) {
    State state = state(stage.getId()).orElse(null);
    if (state == null || stage.getStatus().isTerminal()) {
      return;
    }
    if (JUDGING.equals(state.status())) {
      settleJudges(stage, definition, state, now);
    } else if (REFUTING.equals(state.status())) {
      settleRefutations(stage, definition, state, now);
    }
  }

  private void settleJudges(StageRun stage, StageDefinition definition, State state, Instant now) {
    List<VerdictRow> rows = verdictRows(stage.getId(), state.round());
    Map<UUID, AgentRun> agents = new LinkedHashMap<>();
    for (VerdictRow row : rows) {
      AgentRun agent = agentRuns.findById(row.agentRunId()).orElseThrow();
      if (!agent.getStatus().isTerminal()) {
        return;
      }
      agents.put(row.agentRunId(), agent);
    }
    JudgeSpec spec = definition.judge();
    List<Verdict> verdicts = new ArrayList<>();
    for (VerdictRow row : rows) {
      AgentRun agent = agents.get(row.agentRunId());
      if (agent.getStatus() == AgentObservableStatus.CANCELLED) {
        verdictFailed(row, "Se canceló", now);
        status(stage.getId(), FAILED, state.round(), "Se canceló el juez " + row.judge(), now);
        transitions.advanceStage(stage, StageStatus.CANCELLED, "judge-cancelled", now);
        return;
      }
      JsonNode output =
          agent.getStructuredOutput() == null ? null : json.readTree(agent.getStructuredOutput());
      if (agent.getStatus() != AgentObservableStatus.COMPLETED
          || output == null
          || !output.isObject()) {
        String error =
            agent.getStatus() != AgentObservableStatus.COMPLETED
                ? "no terminó bien" + (agent.getError() == null ? "" : ": " + agent.getError())
                : "no devolvió su veredicto";
        verdictFailed(row, error, now);
        fail(stage, state, "El juez `" + row.judge() + "` " + error, now);
        return;
      }
      if (spec.isGuide()) {
        GuideSteps.Run guide = GuideSteps.run(output);
        Verdict verdict = GuideSteps.verdict(row.judge(), spec.guide(), guide);
        saveVerdict(row, verdict, now);
        saveGuide(row, guide);
        decideGuide(stage, definition, state.round(), verdict, guide, now);
        return;
      }
      Verdict verdict = JudgeVerdicts.verdict(row.judge(), row.lens(), spec, output);
      saveVerdict(row, verdict, now);
      verdicts.add(verdict);
    }
    Integer tier = riskOf(stage.getId());
    if (spec.refute() > 0 && !spec.refutesAt(tier)) {
      refuteNote(
          stage.getId(),
          "Nivel "
              + tier
              + ": sin escépticos (refutan desde el nivel "
              + spec.refuteMinRisk()
              + ")",
          now);
    }
    if (spec.refutesAt(tier)) {
      List<JudgeVerdicts.Target> all = JudgeVerdicts.blockingTargets(verdicts);
      int budget = JudgeSpec.MAX_AGENT_CALLS - calls(stage.getId());
      List<JudgeVerdicts.Target> targets = JudgeVerdicts.targets(verdicts, spec.refute(), budget);
      String note =
          JudgeVerdicts.trimmed(
              targets.size(),
              all.size(),
              targets.size() < Math.min(all.size(), JudgeSpec.MAX_REFUTED_FINDINGS));
      refuteNote(stage.getId(), note, now);
      if (!targets.isEmpty()) {
        launchSkeptics(stage, definition, state, targets, note, now);
        return;
      }
    }
    decide(stage, definition, state.round(), verdicts, now);
  }

  /**
   * Han terminado los escépticos de la ronda (VA-C): cada hallazgo que refuta la mayoría se
   * descarta, y con lo que queda se decide la ronda.
   */
  private void settleRefutations(
      StageRun stage, StageDefinition definition, State state, Instant now) {
    List<RefutationRow> rows = refutationRows(stage.getId(), state.round());
    Map<UUID, AgentRun> agents = new LinkedHashMap<>();
    for (RefutationRow row : rows) {
      AgentRun agent = agentRuns.findById(row.agentRunId()).orElseThrow();
      if (!agent.getStatus().isTerminal()) {
        return;
      }
      agents.put(row.agentRunId(), agent);
    }
    JudgeSpec spec = definition.judge();
    Map<Integer, List<JudgeVerdicts.Refutation>> byFinding = new LinkedHashMap<>();
    Map<Integer, RefutationRow> firstOf = new LinkedHashMap<>();
    for (RefutationRow row : rows) {
      AgentRun agent = agents.get(row.agentRunId());
      if (agent.getStatus() == AgentObservableStatus.CANCELLED) {
        status(stage.getId(), FAILED, state.round(), "Se canceló un escéptico", now);
        transitions.advanceStage(stage, StageStatus.CANCELLED, "judge-cancelled", now);
        return;
      }
      JudgeVerdicts.Refutation said =
          agent.getStatus() == AgentObservableStatus.COMPLETED
                  && agent.getStructuredOutput() != null
              ? JudgeVerdicts.refutation(json.readTree(agent.getStructuredOutput()))
              : null;
      // Un escéptico que no termina bien no refuta: el hallazgo sigue en pie.
      saveRefutation(
          row,
          said,
          said != null
              ? null
              : agent.getStatus() != AgentObservableStatus.COMPLETED
                  ? "no terminó bien" + (agent.getError() == null ? "" : ": " + agent.getError())
                  : "no devolvió su respuesta",
          now);
      byFinding
          .computeIfAbsent(row.finding(), k -> new ArrayList<>())
          .add(said == null ? new JudgeVerdicts.Refutation(false, null) : said);
      firstOf.putIfAbsent(row.finding(), row);
    }
    List<Verdict> after = refuted(stage.getId(), state.round(), spec, byFinding, now);
    long refuted =
        after.stream().flatMap(v -> v.findings().stream()).filter(Finding::discarded).count();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("stageKey", stage.getStageKey());
    payload.put("round", state.round());
    payload.put("refuted", refuted);
    payload.put("findings", byFinding.size());
    transitions.append(
        "stage_run", stage.getId(), "stage.judge.refuted", stage.getWorkflowRunId(), payload, now);
    decide(stage, definition, state.round(), after, now);
  }

  /**
   * Los veredictos de la ronda {@code round} con lo que dijeron los escépticos de cada hallazgo
   * ({@code byFinding}, por su orden entre los que bloquean): los refutados se descartan y se
   * guardan.
   */
  private List<Verdict> refuted(
      UUID stageRunId,
      int round,
      JudgeSpec spec,
      Map<Integer, List<JudgeVerdicts.Refutation>> byFinding,
      Instant now) {
    List<VerdictRow> verdictRows = verdictRows(stageRunId, round);
    List<Verdict> verdicts = new ArrayList<>();
    for (VerdictRow row : verdictRows) {
      AgentRun judge = agentRuns.findById(row.agentRunId()).orElseThrow();
      verdicts.add(
          JudgeVerdicts.verdict(
              row.judge(), row.lens(), spec, json.readTree(judge.getStructuredOutput())));
    }
    List<JudgeVerdicts.Target> targets = JudgeVerdicts.blockingTargets(verdicts);
    Map<Integer, Map<Integer, JudgeVerdicts.Finding>> decided = new LinkedHashMap<>();
    for (Map.Entry<Integer, List<JudgeVerdicts.Refutation>> entry : byFinding.entrySet()) {
      if (entry.getKey() >= targets.size()) {
        continue;
      }
      JudgeVerdicts.Target target = targets.get(entry.getKey());
      JudgeVerdicts.Finding finding =
          JudgeVerdicts.decide(target.finding(), entry.getValue(), spec.refute());
      decided
          .computeIfAbsent(target.verdict(), k -> new LinkedHashMap<>())
          .put(target.index(), finding);
    }
    List<Verdict> after = new ArrayList<>();
    for (int i = 0; i < verdicts.size(); i++) {
      Verdict verdict =
          JudgeVerdicts.refuted(verdicts.get(i), decided.getOrDefault(i, Map.of()), spec);
      saveVerdict(verdictRows.get(i), verdict, now);
      after.add(verdict);
    }
    return after;
  }

  /** El veredicto de la ronda con los de sus jueces: la fase pasa o la ronda suspende. */
  private void decide(
      StageRun stage, StageDefinition definition, int round, List<Verdict> verdicts, Instant now) {
    JudgeSpec spec = definition.judge();
    Aggregate aggregate = JudgeVerdicts.aggregate(verdicts);
    jdbc.sql(
            "UPDATE stage_judge SET score = :score, passed = :passed, approvals = :approvals,"
                + " judges = :judges, findings = CAST(:findings AS jsonb), criteria ="
                + " CAST(:criteria AS jsonb), updated_at = :now WHERE stage_run_id = :stage")
        .param("score", aggregate.score())
        .param("passed", aggregate.passed())
        .param("approvals", aggregate.approvals())
        .param("judges", aggregate.judges())
        .param("findings", json.writeValueAsString(findings(aggregate.findings())))
        .param("criteria", json.writeValueAsString(aggregate.criteria()))
        .param("now", Timestamp.from(now))
        .param("stage", stage.getId())
        .update();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("stageKey", stage.getStageKey());
    payload.put("round", round);
    payload.put("score", aggregate.score());
    payload.put("threshold", spec.threshold());
    payload.put("passed", aggregate.passed());
    payload.put("approvals", aggregate.approvals());
    payload.put("judges", aggregate.judges());
    payload.put("blocking", aggregate.findings().stream().filter(Finding::blocking).count());
    payload.put("refuted", aggregate.findings().stream().filter(Finding::discarded).count());
    transitions.append(
        "stage_run", stage.getId(), "stage.judge.verdict", stage.getWorkflowRunId(), payload, now);
    if (aggregate.passed()) {
      status(stage.getId(), PASSED, round, null, now);
      recordJudged(stage, round, now);
      transitions.advanceStage(stage, StageStatus.SUCCEEDED, "judge-passed", now);
      return;
    }
    roundFailed(
        stage,
        definition,
        round,
        "Suspende el juez: " + JudgeVerdicts.note(aggregate),
        "El juez de la fase `"
            + stage.getStageKey()
            + "` ha suspendido el cambio.\n\n"
            + JudgeVerdicts.report(verdicts, aggregate),
        now);
  }

  /**
   * El recorrido de la guía de la ronda (VA-D): la fase pasa si no falla ningún paso; si no, la
   * ronda suspende y el que corrige recibe los pasos que fallan con su evidencia.
   */
  private void decideGuide(
      StageRun stage,
      StageDefinition definition,
      int round,
      Verdict verdict,
      GuideSteps.Run guide,
      Instant now) {
    JudgeSpec spec = definition.judge();
    Aggregate aggregate = JudgeVerdicts.aggregate(List.of(verdict));
    String note = GuideSteps.note(guide, spec.guide());
    jdbc.sql(
            "UPDATE stage_judge SET score = :score, passed = :passed, approvals = :approvals,"
                + " judges = 1, findings = CAST(:findings AS jsonb), criteria = '{}'::jsonb,"
                + " updated_at = :now WHERE stage_run_id = :stage")
        .param("score", verdict.score())
        .param("passed", verdict.passed())
        .param("approvals", verdict.passed() ? 1 : 0)
        .param("findings", json.writeValueAsString(findings(aggregate.findings())))
        .param("now", Timestamp.from(now))
        .param("stage", stage.getId())
        .update();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("stageKey", stage.getStageKey());
    payload.put("round", round);
    payload.put("passed", verdict.passed());
    payload.put("guides", guide.guides());
    payload.put("steps", guide.steps().size());
    payload.put("stepsPassed", guide.count(GuideSteps.PASSED));
    payload.put("stepsFailed", guide.count(GuideSteps.FAILED));
    payload.put("stepsBlocked", guide.count(GuideSteps.BLOCKED));
    payload.put("note", note);
    transitions.append(
        "stage_run", stage.getId(), "stage.guide.verdict", stage.getWorkflowRunId(), payload, now);
    if (verdict.passed()) {
      status(stage.getId(), PASSED, round, null, now);
      transitions.advanceStage(stage, StageStatus.SUCCEEDED, "guide-passed", now);
      return;
    }
    roundFailed(
        stage,
        definition,
        round,
        "No pasa la guía de pruebas: " + String.join("; ", verdict.reasons()),
        "La guía de pruebas de la fase `"
            + stage.getStageKey()
            + "` no pasa.\n\n"
            + GuideSteps.markdown(guide, spec.guide())
            + "\nNo cambies la guía para que pase: corrige el código. Si la guía está mal, dilo"
            + " en tu respuesta y deja la guía como está.",
        now);
  }

  /**
   * La ronda {@code round} ha suspendido: corrige la fase de {@code fix} si quedan rondas o la fase
   * falla con {@code summary}. {@code report} es lo que se le cuenta al que corrige.
   */
  private void roundFailed(
      StageRun stage,
      StageDefinition definition,
      int round,
      String summary,
      String report,
      Instant now) {
    JudgeSpec spec = definition.judge();
    State state = state(stage.getId()).orElseThrow();
    int corrections = round - 1;
    if (spec.fix() == null || corrections >= spec.maxRounds()) {
      fail(
          stage,
          state,
          spec.fix() == null
              ? summary
              : summary
                  + " tras "
                  + (spec.maxRounds() == 1 ? "1 ronda" : spec.maxRounds() + " rondas")
                  + " de corrección",
          now);
      return;
    }
    int correction = corrections + 1;
    WorkflowRun run = workflowRuns.findById(stage.getWorkflowRunId()).orElseThrow();
    AgentRun agent;
    try {
      StageRun fixStage =
          latestStage(run.getId(), spec.fix())
              .orElseThrow(
                  () -> new ConflictException("La fase " + spec.fix() + " no se ha ejecutado"));
      agent =
          runs.getObject()
              .queueCorrection(
                  run,
                  fixStage,
                  fixPrompt(stage.getStageKey(), correction, spec.maxRounds(), report),
                  now);
    } catch (ConflictException e) {
      fail(stage, state, summary + ". No se pudo corregir: " + e.getMessage(), now);
      return;
    }
    jdbc.sql(
            "UPDATE stage_judge SET status = :status, fix_agent_run_id = :agent, error = :error,"
                + " updated_at = :now WHERE stage_run_id = :stage")
        .param("status", FIXING)
        .param("agent", agent.getId())
        .param("error", summary)
        .param("now", Timestamp.from(now))
        .param("stage", stage.getId())
        .update();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("stageKey", stage.getStageKey());
    payload.put("round", correction);
    payload.put("maxRounds", spec.maxRounds());
    payload.put("fixStageKey", spec.fix());
    payload.put("agentRunId", agent.getId());
    payload.put("reason", summary);
    payload.put("guide", spec.isGuide());
    transitions.append(
        "stage_run",
        stage.getId(),
        "stage.judge.fix.started",
        stage.getWorkflowRunId(),
        payload,
        now);
    publisher.publishEvent(new WorkflowRunChanged(run.getId()));
  }

  static String fixPrompt(String stageKey, int round, int maxRounds, String report) {
    return report
        + "\n\nCorrige lo que señala (ronda de corrección "
        + round
        + " de "
        + maxRounds
        + ") y deja los cambios hechos en el worktree. Después, la fase `"
        + stageKey
        + "` lo vuelve a comprobar y a juzgar desde cero.";
  }

  /**
   * Cierra las correcciones de los jueces que han terminado (el motor, con la ejecución bloqueada).
   * Si la corrección terminó bien y pasan las comprobaciones de su fase, devuelve la ronda que
   * tiene que empezar; si no, la fase falla, se cancela o suspende otra ronda.
   */
  List<NextRound> settleFixes(UUID runId) {
    List<NextRound> next = new ArrayList<>();
    List<State> fixing =
        jdbc.sql("SELECT * FROM stage_judge WHERE workflow_run_id = ? AND status = 'FIXING'")
            .param(runId)
            .query(StageJudges::state)
            .list();
    for (State state : fixing) {
      StageRun stage = stageRuns.findById(state.stageRunId()).orElseThrow();
      AgentRun fix = agentRuns.findById(state.fixAgentRunId()).orElseThrow();
      if (!fix.getStatus().isTerminal()) {
        continue;
      }
      Instant now = time.now();
      if (stage.getStatus().isTerminal()) {
        status(stage.getId(), FAILED, state.round(), state.error(), now);
        continue;
      }
      if (fix.getStatus() == AgentObservableStatus.CANCELLED) {
        status(stage.getId(), FAILED, state.round(), "Se canceló la corrección", now);
        transitions.advanceStage(stage, StageStatus.CANCELLED, "judge-fix-cancelled", now);
        continue;
      }
      if (fix.getStatus() != AgentObservableStatus.COMPLETED) {
        fail(
            stage,
            state,
            "La corrección no terminó bien" + (fix.getError() == null ? "" : ": " + fix.getError()),
            now);
        continue;
      }
      StageDefinition definition = definitionOf(stage);
      WorkflowRun run = workflowRuns.findById(runId).orElseThrow();
      StageDefinition fixDefinition =
          definitions
              .published(run.getDefinitionId())
              .definition()
              .stage(definition.judge().fix())
              .orElse(null);
      int round = state.round() + 1;
      if (fixDefinition != null && fixDefinition.verified() && fix.getWorkspaceId() != null) {
        VerificationResult result =
            verifications.latestOfStage(fix.getStageRunId()).get(fix.getId());
        if (result == null || result.isLive()) {
          continue;
        }
        if (!Verifications.PASSED.equals(result.status())) {
          status(stage.getId(), CHECKING, round, null, now);
          roundFailed(
              stage,
              definition,
              round,
              "Las comprobaciones de `"
                  + definition.judge().fix()
                  + "` no pasan tras la corrección",
              "Las comprobaciones de la fase `"
                  + definition.judge().fix()
                  + "` no pasan tras tu corrección: "
                  + StageVerifications.reason(result),
              now);
          continue;
        }
      }
      WorkspaceView source = workspaces.find(state.workspaceId()).orElse(null);
      if (source == null) {
        fail(stage, state, "El worktree que se juzga ya no existe", now);
        continue;
      }
      workspaces.lock(source.id());
      if (workspaces.hasLiveInvocation(source.id())) {
        continue;
      }
      status(stage.getId(), CHECKING, round, null, now);
      next.add(new NextRound(stage, definition, workspaces.find(source.id()).orElseThrow()));
    }
    return next;
  }

  // ---- Datos para las fases siguientes, la web y la PR ------------------------------------------

  /**
   * Un dato del juez para una condición o un prompt: {@code score}, {@code passed}, {@code
   * findings} o {@code criteria.<id>.score}; {@code null} si todavía no hay veredicto.
   */
  Object value(UUID stageRunId, List<String> parts) {
    State state = state(stageRunId).orElse(null);
    if (state == null) {
      return null;
    }
    String field = parts.get(1);
    if (parts.size() == 2 && field.equals("judgedSha")) {
      return judgedSha(stageRunId).orElse(null);
    }
    if (state.score() == null) {
      return null;
    }
    if (parts.size() == 2) {
      return switch (field) {
        case "score" -> state.score();
        case "passed" -> state.passed();
        case "findings" -> json.readValue(state.findings(), new TypeReference<List<Object>>() {});
        default -> null;
      };
    }
    if (parts.size() == 4 && field.equals("criteria") && parts.get(3).equals("score")) {
      JsonNode score = json.readTree(state.criteria()).get(parts.get(2));
      return score == null || !score.isNumber() ? null : score.decimalValue();
    }
    return null;
  }

  /**
   * Lo que se juzgó (AU-B): el commit que vieron los jueces de la ronda que aprobó la fase. Si no
   * todos vieron el mismo (no debería: el worktree no cambia mientras se juzga), no hay uno solo y
   * no se fija.
   */
  private void recordJudged(StageRun stage, int round, Instant now) {
    List<String> seen =
        jdbc.sql("SELECT DISTINCT head_sha FROM stage_verdict WHERE stage_run_id = ? AND round = ?")
            .params(stage.getId(), round)
            .query(String.class)
            .list();
    if (seen.size() != 1 || seen.getFirst() == null) {
      return;
    }
    jdbc.sql("UPDATE stage_judge SET judged_sha = ?, updated_at = ? WHERE stage_run_id = ?")
        .params(seen.getFirst(), Timestamp.from(now), stage.getId())
        .update();
  }

  /** El commit que se juzgó en una fase con juez que aprobó (AU-B). */
  Optional<String> judgedSha(UUID stageRunId) {
    return jdbc.sql("SELECT judged_sha FROM stage_judge WHERE stage_run_id = ?")
        .param(stageRunId)
        .query(String.class)
        .optional()
        .filter(sha -> sha != null);
  }

  /** El juez de una fase, para la web: la última ronda con el veredicto de cada juez. */
  Optional<JudgeView> view(UUID stageRunId, StageDefinition definition) {
    State state = state(stageRunId).orElse(null);
    if (state == null || definition == null || definition.judge() == null) {
      return Optional.empty();
    }
    JudgeSpec spec = definition.judge();
    List<JudgeView.Judgement> judgements = new ArrayList<>();
    int shown = state.round();
    List<VerdictRow> rows = verdictRows(stageRunId, shown);
    if (rows.isEmpty() && shown > 1) {
      // Corrigiendo o comprobando la ronda nueva: se ve el veredicto de la anterior.
      shown = shown - 1;
      rows = verdictRows(stageRunId, shown);
    }
    for (VerdictRow row : rows) {
      judgements.add(
          new JudgeView.Judgement(
              row.judge(),
              row.lens(),
              row.agentRunId(),
              row.status(),
              row.score(),
              row.passed(),
              json.readValue(row.criteria(), new TypeReference<List<JudgeView.Score>>() {}),
              json.readValue(row.findings(), new TypeReference<List<JudgeView.Issue>>() {}),
              row.summary(),
              row.error()));
    }
    return Optional.of(
        new JudgeView(
            state.status(),
            state.round(),
            shown,
            spec.fix() == null ? 0 : spec.maxRounds(),
            spec.fix(),
            spec.threshold(),
            state.score(),
            state.passed(),
            state.approvals(),
            state.judges(),
            json.readValue(state.findings(), new TypeReference<List<JudgeView.Issue>>() {}),
            judgements,
            state.error(),
            spec.refute(),
            REFUTING.equals(state.status())
                ? (int)
                    refutationRows(stageRunId, state.round()).stream()
                        .map(RefutationRow::finding)
                        .distinct()
                        .count()
                : 0,
            state.refuteNote(),
            spec.isGuide() ? guideView(stageRunId, spec.guide()) : null,
            riskOf(stageRunId),
            spec.minRisk()));
  }

  private JudgeView.Guide guideView(UUID stageRunId, JudgeSpec.GuideSpec spec) {
    GuideSteps.Run run = latestGuide(stageRunId);
    if (run == null) {
      return new JudgeView.Guide(spec.paths(), List.of(), List.of(), null, null);
    }
    return new JudgeView.Guide(
        spec.paths(),
        run.guides(),
        run.steps().stream()
            .map(
                s -> new JudgeView.Step(s.guide(), s.section(), s.step(), s.status(), s.evidence()))
            .toList(),
        run.summary(),
        GuideSteps.note(run, spec));
  }

  /**
   * Los pasos de la guía de la última ronda en Markdown, para el detalle plegado de la PR (VA-D);
   * vacío si la fase no la ha recorrido.
   */
  Optional<String> guideDetails(UUID stageRunId, StageDefinition definition) {
    if (definition.judge() == null || !definition.judge().isGuide()) {
      return Optional.empty();
    }
    GuideSteps.Run run = latestGuide(stageRunId);
    if (run == null) {
      return Optional.empty();
    }
    return Optional.of(
        GuideSteps.markdown(run, definition.judge().guide()).replaceFirst("^# [^\\n]*\\n+", ""));
  }

  /**
   * La nota del juez y sus hallazgos, para la descripción de la PR (EN-A): «Juez: 3,8 / 5 ·
   * aprobado por 2 de 2 (umbral 3,5). Hallazgos: …»; vacío sin veredicto.
   */
  Optional<String> evidence(UUID stageRunId) {
    State state = state(stageRunId).orElse(null);
    if (state == null || state.score() == null) {
      return Optional.empty();
    }
    StageDefinition definition = definitionOf(stageRuns.findById(stageRunId).orElseThrow());
    if (definition != null && definition.judge() != null && definition.judge().isGuide()) {
      return guideEvidence(state, definition.judge().guide(), latestGuide(stageRunId));
    }
    StringBuilder out = new StringBuilder("Juez: ");
    out.append(
        JudgeVerdicts.note(
            new Aggregate(
                state.score(),
                Boolean.TRUE.equals(state.passed()),
                state.approvals() == null ? 0 : state.approvals(),
                state.judges() == null ? 0 : state.judges(),
                List.of(),
                Map.of())));
    if (state.round() > 1) {
      out.append(", en la ronda ").append(state.round());
    }
    List<JudgeView.Issue> issues =
        json.readValue(state.findings(), new TypeReference<List<JudgeView.Issue>>() {});
    if (!issues.isEmpty()) {
      out.append(". Hallazgos: ");
      out.append(String.join("; ", issues.stream().limit(5).map(StageJudges::evidenceOf).toList()));
      if (issues.size() > 5) {
        out.append(" y ").append(issues.size() - 5).append(" más");
      }
    }
    if (state.refuteNote() != null) {
      out.append(". ").append(state.refuteNote());
    }
    return Optional.of(out.toString());
  }

  /** «Guía de pruebas: 2 de 3 pasos pasan, en la ronda 2. Falla: …» (VA-D). */
  private static Optional<String> guideEvidence(
      State state, JudgeSpec.GuideSpec spec, GuideSteps.Run run) {
    if (run == null) {
      return Optional.empty();
    }
    StringBuilder out = new StringBuilder("Guía de pruebas: ").append(GuideSteps.note(run, spec));
    if (state.round() > 1) {
      out.append(", en la ronda ").append(state.round());
    }
    List<String> failed =
        run.steps().stream()
            .filter(s -> GuideSteps.FAILED.equals(s.status()))
            .map(s -> "«" + s.step() + "»")
            .toList();
    if (!failed.isEmpty()) {
      out.append(failed.size() == 1 ? ". Falla: " : ". Fallan: ").append(String.join("; ", failed));
    }
    return Optional.of(out.toString());
  }

  // ---- Persistencia -----------------------------------------------------------------------------

  private record VerdictRow(
      UUID id,
      String judge,
      String lens,
      UUID agentRunId,
      String status,
      BigDecimal score,
      Boolean passed,
      String criteria,
      String findings,
      String summary,
      String error) {}

  private record RefutationRow(UUID id, int finding, int skeptic, UUID agentRunId, String status) {}

  private List<RefutationRow> refutationRows(UUID stageRunId, int round) {
    return jdbc.sql(
            "SELECT id, finding, skeptic, agent_run_id, status FROM stage_refutation WHERE"
                + " stage_run_id = ? AND round = ? ORDER BY finding, skeptic")
        .params(stageRunId, round)
        .query(
            (rs, n) ->
                new RefutationRow(
                    rs.getObject("id", UUID.class),
                    rs.getInt("finding"),
                    rs.getInt("skeptic"),
                    rs.getObject("agent_run_id", UUID.class),
                    rs.getString("status")))
        .list();
  }

  private void saveRefutation(
      RefutationRow row, JudgeVerdicts.Refutation said, String error, Instant now) {
    jdbc.sql(
            "UPDATE stage_refutation SET status = :status, refuted = :refuted, reason = :reason,"
                + " error = :error, updated_at = :now WHERE id = :id")
        .param("status", said == null ? "FAILED" : "DONE")
        .param("refuted", said == null ? null : said.refuted())
        .param("reason", said == null ? null : said.reason())
        .param("error", error)
        .param("now", Timestamp.from(now))
        .param("id", row.id())
        .update();
  }

  private List<VerdictRow> verdictRows(UUID stageRunId, int round) {
    return jdbc.sql(
            "SELECT id, judge, lens, agent_run_id, status, score, passed, CAST(criteria AS text)"
                + " AS criteria, CAST(findings AS text) AS findings, summary, error FROM"
                + " stage_verdict WHERE stage_run_id = ? AND round = ? ORDER BY created_at, judge")
        .params(stageRunId, round)
        .query(
            (rs, n) ->
                new VerdictRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("judge"),
                    rs.getString("lens"),
                    rs.getObject("agent_run_id", UUID.class),
                    rs.getString("status"),
                    rs.getBigDecimal("score"),
                    (Boolean) rs.getObject("passed"),
                    rs.getString("criteria"),
                    rs.getString("findings"),
                    rs.getString("summary"),
                    rs.getString("error")))
        .list();
  }

  private void saveVerdict(VerdictRow row, Verdict verdict, Instant now) {
    List<Map<String, Object>> criteria = new ArrayList<>();
    for (CriterionScore c : verdict.criteria()) {
      Map<String, Object> one = new LinkedHashMap<>();
      one.put("id", c.id());
      one.put("score", c.score());
      one.put("reason", c.reason());
      one.put("weight", c.weight());
      one.put("required", c.required());
      criteria.add(one);
    }
    jdbc.sql(
            "UPDATE stage_verdict SET status = 'DONE', score = :score, passed = :passed, criteria ="
                + " CAST(:criteria AS jsonb), findings = CAST(:findings AS jsonb), summary ="
                + " :summary, error = :error, updated_at = :now WHERE id = :id")
        .param("score", verdict.score())
        .param("passed", verdict.passed())
        .param("criteria", json.writeValueAsString(criteria))
        .param("findings", json.writeValueAsString(findings(verdict.findings())))
        .param("summary", verdict.summary())
        .param("error", verdict.reasons().isEmpty() ? null : String.join("; ", verdict.reasons()))
        .param("now", Timestamp.from(now))
        .param("id", row.id())
        .update();
    recordHead(jdbc, row.id());
  }

  /**
   * El commit que vio el juez de {@code verdictId} (AU-B): la instantánea de su copia si tenía
   * cambios sin confirmar o, si no, el {@code HEAD} del que partió la copia.
   */
  static void recordHead(JdbcClient jdbc, UUID verdictId) {
    jdbc.sql(
            "UPDATE stage_verdict v SET head_sha = coalesce(a.base_snapshot, w.base_commit) FROM"
                + " agent_run a LEFT JOIN workspace w ON w.id = a.workspace_id WHERE v.id = ? AND"
                + " a.id = v.agent_run_id AND v.head_sha IS NULL")
        .param(verdictId)
        .update();
  }

  private void saveGuide(VerdictRow row, GuideSteps.Run guide) {
    jdbc.sql("UPDATE stage_verdict SET guide = CAST(? AS jsonb) WHERE id = ?")
        .params(json.writeValueAsString(guide), row.id())
        .update();
  }

  /** El recorrido de la guía de la última ronda que lo tiene, o {@code null}. */
  private GuideSteps.Run latestGuide(UUID stageRunId) {
    return jdbc.sql(
            "SELECT CAST(guide AS text) FROM stage_verdict WHERE stage_run_id = ? AND guide IS NOT"
                + " NULL ORDER BY round DESC, created_at DESC LIMIT 1")
        .param(stageRunId)
        .query(String.class)
        .optional()
        .map(text -> json.readValue(text, GuideSteps.Run.class))
        .orElse(null);
  }

  private void verdictFailed(VerdictRow row, String error, Instant now) {
    jdbc.sql("UPDATE stage_verdict SET status = 'FAILED', error = ?, updated_at = ? WHERE id = ?")
        .params(error, Timestamp.from(now), row.id())
        .update();
  }

  private static List<Map<String, Object>> findings(List<Finding> findings) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Finding f : findings) {
      Map<String, Object> one = new LinkedHashMap<>();
      one.put("file", f.file());
      one.put("line", f.line());
      one.put("severity", f.severity());
      one.put("text", f.text());
      one.put("judge", f.judge());
      if (f.refuted() != null) {
        one.put("refuted", f.refuted());
        one.put("refutation", f.refutation());
      }
      out.add(one);
    }
    return out;
  }

  private void status(UUID stageRunId, String status, int round, String error, Instant now) {
    jdbc.sql(
            "UPDATE stage_judge SET status = :status, round = :round, error = :error, updated_at ="
                + " :now WHERE stage_run_id = :stage")
        .param("status", status)
        .param("round", round)
        .param("error", error)
        .param("now", Timestamp.from(now))
        .param("stage", stageRunId)
        .update();
  }

  /** La fase falla con {@code error}, que queda en el estado y en {@code stage.judge.failed}. */
  private void fail(StageRun stage, State state, String error, Instant now) {
    status(stage.getId(), FAILED, state.round(), error, now);
    transitions.append(
        "stage_run",
        stage.getId(),
        "stage.judge.failed",
        stage.getWorkflowRunId(),
        Map.of("stageKey", stage.getStageKey(), "round", state.round(), "error", error),
        now);
    if (!stage.getStatus().isTerminal()) {
      transitions.advanceStage(stage, StageStatus.FAILED, "judge-failed", now);
    }
    publisher.publishEvent(new WorkflowRunChanged(stage.getWorkflowRunId()));
  }

  private static State state(ResultSet rs, int n) throws SQLException {
    return new State(
        rs.getObject("stage_run_id", UUID.class),
        rs.getObject("workflow_run_id", UUID.class),
        rs.getString("status"),
        rs.getInt("round"),
        rs.getObject("workspace_id", UUID.class),
        rs.getObject("fix_agent_run_id", UUID.class),
        rs.getBigDecimal("score"),
        (Boolean) rs.getObject("passed"),
        (Integer) rs.getObject("approvals"),
        (Integer) rs.getObject("judges"),
        rs.getString("findings"),
        rs.getString("criteria"),
        rs.getString("error"),
        rs.getString("refute_note"));
  }

  /** Un hallazgo en la PR; uno refutado va tachado, con el motivo (VA-C). */
  private static String evidenceOf(JudgeView.Issue i) {
    String finding =
        (i.file() == null ? "" : "`" + i.file() + (i.line() == null ? "" : ":" + i.line()) + "` ")
            + i.text();
    if (Boolean.TRUE.equals(i.refuted())) {
      return "~~"
          + finding
          + "~~ (refutado"
          + (i.refutation() == null ? "" : ": " + i.refutation())
          + ")";
    }
    return finding + " (" + i.severity() + ")";
  }

  // ---- Apoyo ------------------------------------------------------------------------------------

  /** El proveedor del agente que hizo lo que se juzga: el de {@code fix} o el de su worktree. */
  private String authorProvider(
      StageRun stage, StageDefinition definition, WorkflowDefinition workflow) {
    String judged =
        definition.judge().fix() != null
            ? definition.judge().fix()
            : definition.workspaceFrom() != null
                ? definition.workspaceFrom().split("\\.")[0]
                : definition.dependsOn().isEmpty()
                    ? null
                    : definition.dependsOn().getFirst().stage();
    if (judged == null) {
      return null;
    }
    Optional<StageRun> run = latestStage(stage.getWorkflowRunId(), judged);
    Optional<AgentRun> last =
        run.flatMap(
            r ->
                agentRuns.findByStageRunIdInOrderByCreatedAtAsc(List.of(r.getId())).stream()
                    .reduce((a, b) -> b));
    if (last.isPresent()) {
      return last.get().getProvider();
    }
    return workflow
        .stage(judged)
        .map(StageDefinition::agent)
        .flatMap(workflow::agent)
        .map(a -> a.provider() == null ? "claude-code" : a.provider())
        .orElse(null);
  }

  private Optional<StageRun> latestStage(UUID runId, String stageKey) {
    return jdbc.sql(
            "SELECT id FROM stage_run WHERE workflow_run_id = ? AND stage_key = ?"
                + " ORDER BY attempt DESC LIMIT 1")
        .params(runId, stageKey)
        .query(UUID.class)
        .optional()
        .flatMap(stageRuns::findById);
  }

  private StageDefinition definitionOf(StageRun stage) {
    WorkflowRun run = workflowRuns.findById(stage.getWorkflowRunId()).orElseThrow();
    return definitions
        .published(run.getDefinitionId())
        .definition()
        .stage(stage.getStageKey())
        .orElseThrow();
  }

  private UUID repositoryOf(WorkflowRun run) {
    return jdbc.sql("SELECT repository_id FROM workflow_run WHERE id = ?")
        .param(run.getId())
        .query(UUID.class)
        .single();
  }

  private AgentLimits launchLimits(WorkflowRun run) {
    JsonNode limits =
        json.readTree(
            jdbc.sql("SELECT CAST(launch_limits AS text) FROM workflow_run WHERE id = ?")
                .param(run.getId())
                .query(String.class)
                .single());
    JsonNode turns = limits.path("maxTurns");
    JsonNode budget = limits.path("maxBudgetUsd");
    JsonNode timeout = limits.path("timeoutMinutes");
    return new AgentLimits(
        turns.isNumber() ? turns.asInt() : null,
        budget.isNumber() ? budget.decimalValue() : null,
        timeout.isNumber() ? Duration.ofMinutes(timeout.asLong()) : null);
  }
}
