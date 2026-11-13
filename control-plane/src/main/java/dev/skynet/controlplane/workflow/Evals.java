package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.definition.AgentDefinition;
import dev.skynet.controlplane.definition.JudgeSpec;
import dev.skynet.controlplane.definition.StageDefinition;
import dev.skynet.controlplane.definition.WorkflowDefinition;
import dev.skynet.controlplane.definition.WorkflowDefinitions;
import dev.skynet.controlplane.project.CodeRepository;
import dev.skynet.controlplane.project.ProjectService;
import dev.skynet.controlplane.shared.ConflictException;
import dev.skynet.controlplane.shared.InvalidRequestException;
import dev.skynet.controlplane.shared.NotFoundException;
import dev.skynet.controlplane.shared.TimeSource;
import dev.skynet.controlplane.workflow.JudgeVerdicts.Verdict;
import dev.skynet.controlplane.workitem.WorkItem;
import dev.skynet.controlplane.workitem.WorkItemService;
import dev.skynet.protocol.AgentObservableStatus;
import dev.skynet.protocol.StageStatus;
import dev.skynet.protocol.WorkflowRunStatus;
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
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * El banco de evaluaciones del juez (EV-C). Un caso es un cambio ya juzgado (repositorio, {@code
 * base_sha}, {@code head_sha}, la tarea y la fase de juez que lo juzgó) con lo que debería decir un
 * buen juez: suspender un escape o aprobar un cambio que se quedó. Cada escape crea su caso; los de
 * control se añaden a mano.
 *
 * <p>Repetir un caso lanza uno o dos jueces (A/B) en una <b>ejecución de evaluación</b> propia, con
 * una sola fase: el motor no la planifica ({@link WorkflowRun#isEval()}) y no sale en las listas.
 * Cada juez es una invocación como las de siempre (solo lectura, sin memoria, el prompt de {@link
 * JudgePrompt}) en un worktree nuevo creado desde {@code head_sha} y con el diff contra {@code
 * base_sha}. Al terminar se guarda su nota, si aprueba, sus hallazgos y si acertó. Sin escépticos:
 * se mide al juez solo.
 */
@Component
class Evals {

  static final String MUST_FAIL = "debe-suspender";
  static final String MUST_PASS = "debe-aprobar";

  /** Jueces a la vez en una repetición: uno, o dos para un A/B. */
  static final int MAX_JUDGES = 2;

  static final int MAX_NOTE = 2000;

  private final JdbcClient jdbc;
  private final WorkflowRunRepository workflowRuns;
  private final StageRunRepository stageRuns;
  private final AgentRunRepository agentRuns;
  private final WorkflowDefinitions definitions;
  private final ProjectService projects;
  private final WorkItemService workItems;
  private final AgentQueue agentQueue;
  private final RunTransitions transitions;
  private final Findings findings;
  private final ObjectMapper json;
  private final TimeSource time;

  Evals(
      JdbcClient jdbc,
      WorkflowRunRepository workflowRuns,
      StageRunRepository stageRuns,
      AgentRunRepository agentRuns,
      WorkflowDefinitions definitions,
      ProjectService projects,
      WorkItemService workItems,
      AgentQueue agentQueue,
      RunTransitions transitions,
      Findings findings,
      ObjectMapper json,
      TimeSource time) {
    this.jdbc = jdbc;
    this.workflowRuns = workflowRuns;
    this.stageRuns = stageRuns;
    this.agentRuns = agentRuns;
    this.definitions = definitions;
    this.projects = projects;
    this.workItems = workItems;
    this.agentQueue = agentQueue;
    this.transitions = transitions;
    this.findings = findings;
    this.json = json;
    this.time = time;
  }

  // ---- Casos ------------------------------------------------------------------------------------

  /** Un escape (EV-A) es un caso que el juez debería haber suspendido. Si ya lo hay, nada. */
  void fromEscape(UUID escapeId, UUID runId, String actor, Instant now) {
    Optional<Source> source = source(runId);
    if (source.isEmpty() || caseOfRun(runId).isPresent()) {
      return;
    }
    insert(source.get(), MUST_FAIL, escapeId, null, actor, now);
  }

  /** Añade a mano la ejecución {@code runId} al banco, p. ej. una buena como control. */
  @Transactional
  CaseView add(UUID runId, String expected, String note, String actor) {
    if (!MUST_FAIL.equals(expected) && !MUST_PASS.equals(expected)) {
      throw new InvalidRequestException(
          "`expected` es " + MUST_FAIL + " o " + MUST_PASS + ", no " + expected);
    }
    String clean = note == null || note.isBlank() ? null : note.strip();
    if (clean != null && clean.length() > MAX_NOTE) {
      throw new InvalidRequestException("La nota tiene más de " + MAX_NOTE + " caracteres");
    }
    if (workflowRuns.findById(runId).isEmpty()) {
      throw new NotFoundException("La ejecución", runId);
    }
    if (caseOfRun(runId).isPresent()) {
      throw new ConflictException("La ejecución ya es un caso del banco");
    }
    Source source =
        source(runId)
            .orElseThrow(
                () ->
                    new ConflictException(
                        "La ejecución no tiene un juez que repetir o no se sabe qué commit juzgó"));
    UUID escape =
        jdbc.sql("SELECT id FROM judge_escape WHERE workflow_run_id = ?")
            .param(runId)
            .query(UUID.class)
            .optional()
            .orElse(null);
    return view(insert(source, expected, escape, clean, actor, time.now()));
  }

  @Transactional
  void remove(UUID caseId, String actor) {
    CaseView c = view(caseId);
    jdbc.sql("DELETE FROM eval_case WHERE id = ?").param(caseId).update();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("caseId", caseId);
    payload.put("actor", actor);
    transitions.append(
        "workflow_run", c.runId(), "eval.case.removed", c.runId(), payload, time.now());
  }

  /** De dónde sale un caso: la última fase de juez de la ejecución y qué commits juzgó. */
  private record Source(
      UUID runId,
      UUID repositoryId,
      UUID definitionId,
      String stageKey,
      String baseSha,
      String headSha,
      String workItemKey,
      String title,
      String description) {}

  private Optional<Source> source(UUID runId) {
    return jdbc.sql(
            """
            SELECT r.id AS run_id, r.repository_id, r.definition_id, s.stage_key, w.key, w.title,
              w.description,
              (SELECT a.base_commit FROM stage_verdict v JOIN agent_run a ON a.id = v.agent_run_id
               WHERE v.stage_run_id = sj.stage_run_id AND a.base_commit IS NOT NULL
               ORDER BY v.round DESC LIMIT 1) AS base_sha,
              coalesce(
                (SELECT p.head_sha FROM github_pull_request p WHERE p.workflow_run_id = r.id
                 AND p.head_sha IS NOT NULL ORDER BY p.created_at DESC LIMIT 1),
                sj.judged_sha) AS head_sha
            FROM stage_judge sj
            JOIN stage_run s ON s.id = sj.stage_run_id
            JOIN workflow_run r ON r.id = sj.workflow_run_id
            JOIN work_item w ON w.id = r.work_item_id
            WHERE sj.workflow_run_id = ? AND r.repository_id IS NOT NULL
            ORDER BY sj.created_at DESC LIMIT 1
            """)
        .param(runId)
        .query(
            (rs, n) ->
                new Source(
                    rs.getObject("run_id", UUID.class),
                    rs.getObject("repository_id", UUID.class),
                    rs.getObject("definition_id", UUID.class),
                    rs.getString("stage_key"),
                    rs.getString("base_sha"),
                    rs.getString("head_sha"),
                    rs.getString("key"),
                    rs.getString("title"),
                    rs.getString("description")))
        .optional()
        .filter(s -> s.baseSha() != null && s.headSha() != null);
  }

  private Optional<UUID> caseOfRun(UUID runId) {
    return jdbc.sql("SELECT id FROM eval_case WHERE source_run_id = ?")
        .param(runId)
        .query(UUID.class)
        .optional();
  }

  private UUID insert(
      Source s, String expected, UUID escapeId, String note, String actor, Instant now) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO eval_case (id, repository_id, source_run_id, definition_id, stage_key,
              escape_id, base_sha, head_sha, work_item_key, title, description, expected, note,
              created_by, created_at)
            VALUES (:id, :repository, :run, :definition, :stage, :escape, :base, :head, :key,
              :title, :description, :expected, :note, :actor, :now)
            """)
        .param("id", id)
        .param("repository", s.repositoryId())
        .param("run", s.runId())
        .param("definition", s.definitionId())
        .param("stage", s.stageKey())
        .param("escape", escapeId)
        .param("base", s.baseSha())
        .param("head", s.headSha())
        .param("key", s.workItemKey())
        .param("title", s.title())
        .param("description", s.description())
        .param("expected", expected)
        .param("note", note)
        .param("actor", actor)
        .param("now", Timestamp.from(now))
        .update();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("caseId", id);
    payload.put("expected", expected);
    payload.put("workItemKey", s.workItemKey());
    payload.put("actor", actor);
    transitions.append("workflow_run", s.runId(), "eval.case.created", s.runId(), payload, now);
    return id;
  }

  // ---- Repetir ----------------------------------------------------------------------------------

  /**
   * Un juez para una repetición: un agente del workflow del caso y, si se quiere, otro proveedor u
   * otro modelo. Sin {@code lens}, la del juez de la fase con ese agente, si la tiene.
   */
  record JudgeChoice(String agent, String lens, String provider, String model) {}

  /** Los jueces que se pueden elegir para un caso: los agentes de su workflow. */
  List<JudgeOption> options(UUID caseId) {
    Case c = load(caseId);
    WorkflowDefinition workflow = definitions.published(c.definitionId()).definition();
    JudgeSpec spec = spec(workflow, c.stageKey());
    List<JudgeOption> out = new ArrayList<>();
    for (AgentDefinition agent : workflow.agents()) {
      String lens =
          spec.agents().stream()
              .filter(j -> j.agent().equals(agent.name()))
              .map(JudgeSpec.Judge::lens)
              .findFirst()
              .orElse(null);
      boolean judge = spec.agents().stream().anyMatch(j -> j.agent().equals(agent.name()));
      out.add(new JudgeOption(agent.name(), lens, agent.provider(), agent.model(), judge));
    }
    return out;
  }

  record JudgeOption(String agent, String lens, String provider, String model, boolean judge) {}

  /**
   * Repite el caso con {@code judges} (uno, o dos para un A/B), en una ejecución de evaluación
   * nueva. Devuelve las repeticiones lanzadas.
   */
  @Transactional
  List<EvalRunView> repeat(UUID caseId, List<JudgeChoice> judges, String actor) {
    if (judges == null || judges.isEmpty() || judges.size() > MAX_JUDGES) {
      throw new InvalidRequestException("Elige uno o dos jueces");
    }
    Case c = load(caseId);
    WorkflowRun source = workflowRuns.findById(c.runId()).orElseThrow();
    WorkflowDefinition workflow = definitions.published(c.definitionId()).definition();
    JudgeSpec spec = spec(workflow, c.stageKey());
    CodeRepository repository = projects.getRepository(c.repositoryId());
    projects.requireActive(repository);
    WorkItem workItem = workItems.get(source.getWorkItemId());
    List<AgentDefinition> agents = new ArrayList<>();
    for (JudgeChoice choice : judges) {
      agents.add(
          workflow
              .agent(choice.agent())
              .orElseThrow(
                  () ->
                      new InvalidRequestException(
                          "El workflow del caso no tiene el agente `" + choice.agent() + "`")));
    }
    Instant now = time.now();
    WorkflowRun run = WorkflowRun.create(source.getWorkItemId(), c.definitionId(), now);
    run.transitionTo(WorkflowRunStatus.RUNNING, now);
    run = workflowRuns.save(run);
    jdbc.sql(
            """
            UPDATE workflow_run SET purpose = 'eval', repository_id = :repository,
              launch_limits = (SELECT launch_limits FROM workflow_run WHERE id = :source)
            WHERE id = :id
            """)
        .param("repository", c.repositoryId())
        .param("source", c.runId())
        .param("id", run.getId())
        .update();
    run = workflowRuns.findById(run.getId()).orElseThrow();
    StageRun stage = stageRuns.save(StageRun.create(run.getId(), c.stageKey(), now));
    stage = transitions.advanceStage(stage, StageStatus.RUNNING, "eval-started", now);
    AgentLimits limits = launchLimits(c.runId());
    String schema = JudgeVerdicts.outputSchema(spec.rubricFile() != null);
    List<String> learned = findings.learned(c.repositoryId());
    UUID pair = judges.size() > 1 ? UUID.randomUUID() : null;
    List<UUID> launched = new ArrayList<>();
    for (int i = 0; i < judges.size(); i++) {
      JudgeChoice choice = judges.get(i);
      AgentDefinition agent = agents.get(i);
      String lens =
          choice.lens() != null
              ? choice.lens()
              : spec.agents().stream()
                  .filter(j -> j.agent().equals(agent.name()))
                  .map(JudgeSpec.Judge::lens)
                  .findFirst()
                  .orElse(null);
      JudgeSpec.Judge judge = new JudgeSpec.Judge(agent.name(), lens);
      String prompt =
          JudgePrompt.build(
              new JudgePrompt.Context(
                      c.workItemKey(),
                      c.title(),
                      c.description(),
                      c.baseSha(),
                      List.of(),
                      1,
                      agent.prompt(),
                      List.of(),
                      null)
                  .withLearned(learned),
              spec,
              judge);
      EffectivePolicy policy =
          EffectivePolicy.of(projects.agentPolicy(repository), agent, limits)
              .withOutputSchema(schema)
              .withMemory(StageDefinition.MEMORY_OFF)
              .withPermissionMode("plan");
      if (choice.provider() != null && !choice.provider().isBlank()) {
        policy = policy.withProvider(choice.provider().strip(), blankToNull(choice.model()));
      } else if (choice.model() != null && !choice.model().isBlank()) {
        policy = policy.withProvider(policy.providerOrDefault(), choice.model().strip());
      }
      AgentRun queued =
          agentQueue.queue(
              run,
              stage,
              workItem,
              repository,
              Invocation.freshAt(prompt, policy, c.headSha()),
              now);
      queued.asEval();
      queued.recordBaseCommit(c.baseSha());
      queued = agentRuns.save(queued);
      UUID id = UUID.randomUUID();
      jdbc.sql(
              """
              INSERT INTO eval_run (id, case_id, pair_id, judge, lens, provider, model,
                workflow_run_id, agent_run_id, status, created_by, created_at, updated_at)
              VALUES (:id, :case, :pair, :judge, :lens, :provider, :model, :run, :agent,
                'PENDING', :actor, :now, :now)
              """)
          .param("id", id)
          .param("case", caseId)
          .param("pair", pair)
          .param("judge", agent.name())
          .param("lens", lens)
          .param("provider", queued.getProvider())
          .param("model", policy.model())
          .param("run", run.getId())
          .param("agent", queued.getId())
          .param("actor", actor)
          .param("now", Timestamp.from(now))
          .update();
      launched.add(id);
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("caseId", caseId);
    payload.put("sourceRunId", c.runId());
    payload.put("judges", judges.stream().map(JudgeChoice::agent).toList());
    payload.put("actor", actor);
    transitions.append("workflow_run", run.getId(), "eval.started", run.getId(), payload, now);
    return launched.stream().map(this::runView).toList();
  }

  /** El banco entero de un repositorio con los mismos jueces: una ejecución por caso. */
  @Transactional
  List<EvalRunView> repeatAll(UUID repositoryId, List<JudgeChoice> judges, String actor) {
    List<UUID> cases =
        jdbc.sql("SELECT id FROM eval_case WHERE repository_id = ? ORDER BY created_at")
            .param(repositoryId)
            .query(UUID.class)
            .list();
    if (cases.isEmpty()) {
      throw new ConflictException("El repositorio no tiene casos en el banco");
    }
    List<EvalRunView> out = new ArrayList<>();
    for (UUID c : cases) {
      out.addAll(repeat(c, judges, actor));
    }
    return out;
  }

  /** Ha terminado un juez del banco: su veredicto y, si era el último, cierra su ejecución. */
  @EventListener
  void finished(EvalAgentFinished event) {
    AgentRun agent = agentRuns.findById(event.agentRunId()).orElse(null);
    if (agent == null) {
      return;
    }
    record Row(UUID id, UUID caseId, String judge, String lens, String expected, String status) {}
    Row row =
        jdbc.sql(
                """
                SELECT e.id, e.case_id, e.judge, e.lens, c.expected, e.status FROM eval_run e
                JOIN eval_case c ON c.id = e.case_id WHERE e.agent_run_id = ?
                """)
            .param(agent.getId())
            .query(
                (rs, n) ->
                    new Row(
                        rs.getObject("id", UUID.class),
                        rs.getObject("case_id", UUID.class),
                        rs.getString("judge"),
                        rs.getString("lens"),
                        rs.getString("expected"),
                        rs.getString("status")))
            .optional()
            .orElse(null);
    Instant now = event.at();
    if (row != null && !"DONE".equals(row.status()) && !"FAILED".equals(row.status())) {
      Case c = load(row.caseId());
      JudgeSpec spec = spec(definitions.published(c.definitionId()).definition(), c.stageKey());
      JsonNode output =
          agent.getStructuredOutput() == null ? null : json.readTree(agent.getStructuredOutput());
      if (agent.getStatus() != AgentObservableStatus.COMPLETED
          || output == null
          || !output.isObject()) {
        String error =
            agent.getStatus() != AgentObservableStatus.COMPLETED
                ? "No terminó bien" + (agent.getError() == null ? "" : ": " + agent.getError())
                : "No devolvió su veredicto";
        jdbc.sql("UPDATE eval_run SET status = 'FAILED', error = ?, updated_at = ? WHERE id = ?")
            .params(error, Timestamp.from(now), row.id())
            .update();
      } else {
        Verdict verdict = JudgeVerdicts.verdict(row.judge(), row.lens(), spec, output);
        boolean correct = verdict.passed() == MUST_PASS.equals(row.expected());
        jdbc.sql(
                """
                UPDATE eval_run SET status = 'DONE', score = :score, passed = :passed,
                  findings = CAST(:findings AS jsonb), summary = :summary, correct = :correct,
                  updated_at = :now
                WHERE id = :id
                """)
            .param("score", verdict.score())
            .param("passed", verdict.passed())
            .param("findings", json.writeValueAsString(StageJudges.findingsOf(verdict.findings())))
            .param("summary", verdict.summary())
            .param("correct", correct)
            .param("now", Timestamp.from(now))
            .param("id", row.id())
            .update();
      }
      EvalRunView view = runView(row.id());
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("evalRunId", row.id());
      payload.put("caseId", row.caseId());
      payload.put("judge", row.judge());
      payload.put("status", view.status());
      payload.put("score", view.score());
      payload.put("passed", view.passed());
      payload.put("correct", view.correct());
      StageRun stage = stageRuns.findById(agent.getStageRunId()).orElseThrow();
      transitions.append(
          "workflow_run",
          stage.getWorkflowRunId(),
          "eval.judged",
          stage.getWorkflowRunId(),
          payload,
          now);
    }
    close(agent, now);
  }

  /** Con todos sus jueces terminados, la fase y la ejecución de evaluación terminan. */
  private void close(AgentRun agent, Instant now) {
    StageRun stage = stageRuns.findById(agent.getStageRunId()).orElseThrow();
    List<AgentRun> agents = agentRuns.findByStageRunIdInOrderByCreatedAtAsc(List.of(stage.getId()));
    if (agents.stream().anyMatch(a -> !a.getStatus().isTerminal())) {
      return;
    }
    AgentObservableStatus outcome = RunTransitions.outcome(agents);
    StageStatus stageStatus =
        switch (outcome) {
          case COMPLETED -> StageStatus.SUCCEEDED;
          case CANCELLED -> StageStatus.CANCELLED;
          default -> StageStatus.FAILED;
        };
    if (!stage.getStatus().isTerminal()) {
      transitions.advanceStage(stage, stageStatus, "eval-finished", now);
    }
    WorkflowRun run = workflowRuns.findById(stage.getWorkflowRunId()).orElseThrow();
    if (!run.getStatus().isTerminal()) {
      WorkflowRunStatus status =
          switch (outcome) {
            case COMPLETED -> WorkflowRunStatus.SUCCEEDED;
            case CANCELLED -> WorkflowRunStatus.CANCELLED;
            default -> WorkflowRunStatus.FAILED;
          };
      transitions.advanceRun(run, status, "eval-finished", now);
    }
  }

  // ---- Lecturas ---------------------------------------------------------------------------------

  private record Case(
      UUID id,
      UUID runId,
      UUID repositoryId,
      UUID definitionId,
      String stageKey,
      String baseSha,
      String headSha,
      String workItemKey,
      String title,
      String description) {}

  private Case load(UUID caseId) {
    return jdbc.sql("SELECT * FROM eval_case WHERE id = ?")
        .param(caseId)
        .query(
            (rs, n) ->
                new Case(
                    rs.getObject("id", UUID.class),
                    rs.getObject("source_run_id", UUID.class),
                    rs.getObject("repository_id", UUID.class),
                    rs.getObject("definition_id", UUID.class),
                    rs.getString("stage_key"),
                    rs.getString("base_sha"),
                    rs.getString("head_sha"),
                    rs.getString("work_item_key"),
                    rs.getString("title"),
                    rs.getString("description")))
        .optional()
        .orElseThrow(() -> new NotFoundException("El caso", caseId));
  }

  /** Un caso del banco, con sus repeticiones de la más nueva a la más vieja. */
  record CaseView(
      UUID id,
      UUID runId,
      UUID repositoryId,
      String repositoryName,
      String workItemKey,
      String title,
      String stageKey,
      String baseSha,
      String headSha,
      String expected,
      boolean fromEscape,
      String note,
      String createdBy,
      Instant createdAt,
      List<EvalRunView> runs) {}

  /** Una repetición: el juez, cómo va y, al terminar, qué dijo y si acertó. */
  record EvalRunView(
      UUID id,
      UUID caseId,
      UUID pairId,
      String judge,
      String lens,
      String provider,
      String model,
      UUID workflowRunId,
      UUID agentRunId,
      String status,
      BigDecimal score,
      Boolean passed,
      Boolean correct,
      List<Map<String, Object>> findings,
      String summary,
      String error,
      BigDecimal costUsd,
      Instant createdAt) {}

  /** Cuánto acierta cada juez y modelo en el banco. */
  record Accuracy(
      String judge,
      String provider,
      String model,
      int runs,
      int correct,
      int failed,
      double ratio) {}

  record Bank(List<CaseView> cases, List<Accuracy> accuracy) {}

  CaseView view(UUID caseId) {
    return cases("c.id = :id", Map.of("id", caseId)).stream()
        .findFirst()
        .orElseThrow(() -> new NotFoundException("El caso", caseId));
  }

  /** El banco: los casos (de un repositorio, o todos) y el acierto de cada juez y modelo. */
  Bank bank(UUID repositoryId) {
    List<CaseView> cases =
        repositoryId == null
            ? cases("true", Map.of())
            : cases("c.repository_id = :repository", Map.of("repository", repositoryId));
    Map<String, int[]> counts = new LinkedHashMap<>();
    Map<String, String[]> keys = new LinkedHashMap<>();
    for (CaseView c : cases) {
      for (EvalRunView r : c.runs()) {
        if (!"DONE".equals(r.status()) && !"FAILED".equals(r.status())) {
          continue;
        }
        String k = r.judge() + "|" + r.provider() + "|" + r.model();
        keys.putIfAbsent(k, new String[] {r.judge(), r.provider(), r.model()});
        int[] n = counts.computeIfAbsent(k, x -> new int[3]);
        n[0]++;
        if (Boolean.TRUE.equals(r.correct())) n[1]++;
        if ("FAILED".equals(r.status())) n[2]++;
      }
    }
    List<Accuracy> accuracy = new ArrayList<>();
    counts.forEach(
        (k, n) -> {
          String[] key = keys.get(k);
          accuracy.add(
              new Accuracy(key[0], key[1], key[2], n[0], n[1], n[2], JudgeStats.ratio(n[1], n[0])));
        });
    accuracy.sort((a, b) -> Double.compare(b.ratio(), a.ratio()));
    return new Bank(cases, accuracy);
  }

  private List<CaseView> cases(String where, Map<String, ?> params) {
    List<CaseView> cases =
        jdbc.sql(
                "SELECT c.*, r.name AS repository_name FROM eval_case c"
                    + " JOIN repository r ON r.id = c.repository_id WHERE "
                    + where
                    + " ORDER BY c.created_at DESC")
            .params(params)
            .query((rs, n) -> caseView(rs, List.of()))
            .list();
    if (cases.isEmpty()) {
      return cases;
    }
    Map<UUID, List<EvalRunView>> runs = new LinkedHashMap<>();
    jdbc.sql(RUNS + " WHERE e.case_id IN (:ids) ORDER BY e.created_at DESC, e.judge")
        .param("ids", cases.stream().map(CaseView::id).toList())
        .query(
            (rs, n) -> {
              EvalRunView v = evalRun(rs);
              runs.computeIfAbsent(v.caseId(), k -> new ArrayList<>()).add(v);
              return null;
            })
        .list();
    return cases.stream()
        .map(
            c ->
                new CaseView(
                    c.id(),
                    c.runId(),
                    c.repositoryId(),
                    c.repositoryName(),
                    c.workItemKey(),
                    c.title(),
                    c.stageKey(),
                    c.baseSha(),
                    c.headSha(),
                    c.expected(),
                    c.fromEscape(),
                    c.note(),
                    c.createdBy(),
                    c.createdAt(),
                    runs.getOrDefault(c.id(), List.of())))
        .toList();
  }

  EvalRunView runView(UUID id) {
    return jdbc.sql(RUNS + " WHERE e.id = ?").param(id).query((rs, n) -> evalRun(rs)).single();
  }

  /**
   * Las repeticiones con el estado de su juez: si el juez acabó sin que se recogiera (p. ej. se
   * canceló en cola), cuenta como fallida.
   */
  private static final String RUNS =
      """
      SELECT e.*, a.status AS agent_status, a.cost_usd FROM eval_run e
      LEFT JOIN agent_run a ON a.id = e.agent_run_id
      """;

  private EvalRunView evalRun(ResultSet rs) throws SQLException {
    String status = rs.getString("status");
    String agentStatus = rs.getString("agent_status");
    String error = rs.getString("error");
    boolean pending = "PENDING".equals(status) || "RUNNING".equals(status);
    if (pending && agentStatus != null) {
      AgentObservableStatus a = AgentObservableStatus.valueOf(agentStatus);
      if (a.isTerminal()) {
        status = "FAILED";
        error = error == null ? "El juez terminó sin veredicto (" + agentStatus + ")" : error;
      } else if (a != AgentObservableStatus.QUEUED) {
        status = "RUNNING";
      }
    }
    String raw = rs.getString("findings");
    List<Map<String, Object>> found =
        raw == null
            ? List.of()
            : json.readValue(
                raw, new tools.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
    return new EvalRunView(
        rs.getObject("id", UUID.class),
        rs.getObject("case_id", UUID.class),
        rs.getObject("pair_id", UUID.class),
        rs.getString("judge"),
        rs.getString("lens"),
        rs.getString("provider"),
        rs.getString("model"),
        rs.getObject("workflow_run_id", UUID.class),
        rs.getObject("agent_run_id", UUID.class),
        status,
        rs.getBigDecimal("score"),
        (Boolean) rs.getObject("passed"),
        (Boolean) rs.getObject("correct"),
        found,
        rs.getString("summary"),
        error,
        rs.getBigDecimal("cost_usd"),
        rs.getTimestamp("created_at").toInstant());
  }

  private static CaseView caseView(ResultSet rs, List<EvalRunView> runs) throws SQLException {
    return new CaseView(
        rs.getObject("id", UUID.class),
        rs.getObject("source_run_id", UUID.class),
        rs.getObject("repository_id", UUID.class),
        rs.getString("repository_name"),
        rs.getString("work_item_key"),
        rs.getString("title"),
        rs.getString("stage_key"),
        rs.getString("base_sha"),
        rs.getString("head_sha"),
        rs.getString("expected"),
        rs.getObject("escape_id") != null,
        rs.getString("note"),
        rs.getString("created_by"),
        rs.getTimestamp("created_at").toInstant(),
        runs);
  }

  // ---- Utilidades -------------------------------------------------------------------------------

  private static JudgeSpec spec(WorkflowDefinition workflow, String stageKey) {
    return workflow
        .stage(stageKey)
        .map(StageDefinition::judge)
        .orElseThrow(
            () ->
                new ConflictException("La fase `" + stageKey + "` del workflow ya no tiene juez"));
  }

  private AgentLimits launchLimits(UUID runId) {
    JsonNode limits =
        json.readTree(
            jdbc.sql("SELECT CAST(launch_limits AS text) FROM workflow_run WHERE id = ?")
                .param(runId)
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

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }
}
