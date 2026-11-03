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
  static final String FIXING = "FIXING";
  static final String PASSED = "PASSED";
  static final String FAILED = "FAILED";

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
      String error) {}

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

  // ---- Veredicto --------------------------------------------------------------------------------

  /**
   * Si ya han terminado los jueces de la ronda, la cierra: el veredicto de cada uno, el de la fase
   * y, con él, la fase termina bien o pasa a corregir (o falla). Un juez que no termina bien (o no
   * devuelve su veredicto) hace fallar la fase: no es culpa del autor, y corregir no lo arregla.
   */
  void settle(StageRun stage, StageDefinition definition, Instant now) {
    State state = state(stage.getId()).orElse(null);
    if (state == null || stage.getStatus().isTerminal() || !JUDGING.equals(state.status())) {
      return;
    }
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
      Verdict verdict = JudgeVerdicts.verdict(row.judge(), row.lens(), spec, output);
      saveVerdict(row, verdict, now);
      verdicts.add(verdict);
    }
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
    payload.put("round", state.round());
    payload.put("score", aggregate.score());
    payload.put("threshold", spec.threshold());
    payload.put("passed", aggregate.passed());
    payload.put("approvals", aggregate.approvals());
    payload.put("judges", aggregate.judges());
    payload.put("blocking", aggregate.findings().stream().filter(Finding::blocking).count());
    transitions.append(
        "stage_run", stage.getId(), "stage.judge.verdict", stage.getWorkflowRunId(), payload, now);
    if (aggregate.passed()) {
      status(stage.getId(), PASSED, state.round(), null, now);
      transitions.advanceStage(stage, StageStatus.SUCCEEDED, "judge-passed", now);
      return;
    }
    roundFailed(
        stage,
        definition,
        state.round(),
        "Suspende el juez: " + JudgeVerdicts.note(aggregate),
        "El juez de la fase `"
            + stage.getStageKey()
            + "` ha suspendido el cambio.\n\n"
            + JudgeVerdicts.report(verdicts, aggregate),
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
    if (state == null || state.score() == null) {
      return null;
    }
    String field = parts.get(1);
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
            state.error()));
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
      out.append(
          String.join(
              "; ",
              issues.stream()
                  .limit(5)
                  .map(
                      i ->
                          (i.file() == null
                                  ? ""
                                  : "`"
                                      + i.file()
                                      + (i.line() == null ? "" : ":" + i.line())
                                      + "` ")
                              + i.text()
                              + " ("
                              + i.severity()
                              + ")")
                  .toList()));
      if (issues.size() > 5) {
        out.append(" y ").append(issues.size() - 5).append(" más");
      }
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
        rs.getString("error"));
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
