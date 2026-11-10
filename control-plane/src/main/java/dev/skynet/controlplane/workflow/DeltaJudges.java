package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.definition.StageDefinition;
import dev.skynet.controlplane.definition.WorkflowDefinition;
import dev.skynet.controlplane.definition.WorkflowDefinitions;
import dev.skynet.controlplane.shared.ConflictException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Lo que se fusiona es lo juzgado (AU-B). Cuando la CI de una fase {@code github-check} pasa
 * después de corregirla, lo que va a fusionarse ya no es lo que aprobó el juez. Antes de dar la
 * fase por buena, se mira el delta entre los dos:
 *
 * <ul>
 *   <li>sin cambios (o sin correcciones), la fase pasa: «sin cambios desde el juicio»;
 *   <li>nivel 0 (solo documentación), pasa sin juez;
 *   <li>nivel 1, lo juzga el primer juez de la fase que aprobó; nivel 2 (o sin medir), todos. Solo
 *       el delta, en una ronda que no gasta las de corrección del juez. Si suspende, la fase falla
 *       con el motivo.
 * </ul>
 *
 * <p>El nivel del delta lo mide el runner al terminar la corrección, desde el commit juzgado (ver
 * {@code AgentQueue#riskRequest}). El resultado va en {@code judged_delta}.
 */
@Component
class DeltaJudges {

  static final String UNCHANGED = "UNCHANGED";
  static final String SKIPPED = "SKIPPED";
  static final String JUDGING = "JUDGING";
  static final String REFUTING = "REFUTING";
  static final String PASSED = "PASSED";
  static final String FAILED = "FAILED";

  private final JdbcClient jdbc;
  private final StageRunRepository stageRuns;
  private final WorkflowRunRepository workflowRuns;
  private final AgentRunRepository agentRuns;
  private final WorkflowDefinitions definitions;
  private final Workspaces workspaces;
  private final StageJudges judges;
  private final Findings findings;
  private final RunTransitions transitions;
  private final ObjectMapper json;

  DeltaJudges(
      JdbcClient jdbc,
      StageRunRepository stageRuns,
      WorkflowRunRepository workflowRuns,
      AgentRunRepository agentRuns,
      WorkflowDefinitions definitions,
      Workspaces workspaces,
      StageJudges judges,
      Findings findings,
      RunTransitions transitions,
      ObjectMapper json) {
    this.jdbc = jdbc;
    this.stageRuns = stageRuns;
    this.workflowRuns = workflowRuns;
    this.agentRuns = agentRuns;
    this.definitions = definitions;
    this.workspaces = workspaces;
    this.judges = judges;
    this.findings = findings;
    this.transitions = transitions;
    this.json = json;
  }

  /** Cómo quedó el delta al pasar la CI: {@code judging} si hay que esperar a sus jueces. */
  record Start(boolean judging, String note) {

    static final Start NONE = new Start(false, null);
  }

  /** Lo que decidieron los jueces del delta. */
  record Decision(boolean passed, String note) {}

  /** La fase con juez que aprobó el worktree de la PR, con lo que se juzgó. */
  private record Judged(StageRun stage, StageDefinition definition, String sha) {}

  /**
   * La CI de {@code stage} ha pasado en {@code headSha}: si después del juicio se corrigió algo,
   * decide qué hacer con el delta. {@code fixAgentRunId} es la última corrección de la CI, o {@code
   * null} si no hubo.
   */
  Start start(StageRun stage, String headSha, UUID fixAgentRunId, Instant now) {
    Judged judged = judged(stage.getWorkflowRunId()).orElse(null);
    if (judged == null) {
      return Start.NONE;
    }
    if (judged.sha().equals(headSha) || fixAgentRunId == null) {
      String note = "sin cambios desde el juicio";
      save(stage, judged, headSha, UNCHANGED, null, null, null, now);
      event(stage, judged, headSha, "stage.judge.delta.unchanged", null, null, note, now);
      return new Start(false, note);
    }
    Map<String, Object> risk = measured(fixAgentRunId, judged.sha());
    Integer files = risk == null ? null : number(risk.get("files"));
    Integer tier = risk == null ? null : number(risk.get("tier"));
    if (files != null && files == 0) {
      String note = "sin cambios desde el juicio";
      save(stage, judged, headSha, UNCHANGED, 0, risk, null, now);
      event(stage, judged, headSha, "stage.judge.delta.unchanged", risk, null, note, now);
      return new Start(false, note);
    }
    if (tier != null && tier == 0) {
      String note = "delta de nivel 0: no se vuelve a juzgar";
      save(stage, judged, headSha, SKIPPED, 0, risk, null, now);
      event(stage, judged, headSha, "stage.judge.delta.skipped", risk, null, note, now);
      return new Start(false, note);
    }
    // Sin medir (no llegó o se midió desde otro commit), con todo el panel.
    int level = tier == null ? 2 : tier;
    AgentRun fix = agentRuns.findById(fixAgentRunId).orElseThrow();
    WorkspaceView source =
        fix.getWorkspaceId() == null ? null : workspaces.find(fix.getWorkspaceId()).orElse(null);
    if (source == null || source.unusable()) {
      throw new ConflictException(
          "el worktree de la PR ya no existe y no se puede juzgar el delta");
    }
    List<Map<String, Object>> launched =
        judges.launchDelta(
            stage, judged.stage(), judged.definition(), source, judged.sha(), level, now);
    save(stage, judged, headSha, JUDGING, level, risk, null, now);
    event(stage, judged, headSha, "stage.judge.delta.started", risk, launched, null, now);
    return new Start(true, null);
  }

  /** Si ya han terminado los jueces del delta de {@code stage}, lo que decidieron. */
  Optional<Decision> settle(StageRun stage, Instant now) {
    Row row = row(stage.getId()).orElse(null);
    if (row == null || !(JUDGING.equals(row.status()) || REFUTING.equals(row.status()))) {
      return Optional.empty();
    }
    StageRun judgeStage = stageRuns.findById(row.judgeStageRunId()).orElseThrow();
    StageDefinition definition = definitionOf(judgeStage);
    boolean refuting = REFUTING.equals(row.status());
    StageJudges.DeltaOutcome outcome =
        (refuting
                ? judges.collectDeltaRefutations(stage.getId(), definition.judge(), now)
                : judges.collectDelta(stage.getId(), definition.judge(), now))
            .orElse(null);
    if (outcome == null) {
      return Optional.empty();
    }
    if (!refuting
        && outcome.error() == null
        && !outcome.aggregate().passed()
        && definition.judge().refutesAt(row.tier())
        && refute(stage, judgeStage, definition, row, outcome, now)) {
      return Optional.empty();
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("stageKey", stage.getStageKey());
    payload.put("judgeStageKey", judgeStage.getStageKey());
    payload.put("judgedSha", row.judgedSha());
    payload.put("headSha", row.headSha());
    payload.put("tier", row.tier());
    if (outcome.error() != null) {
      update(stage.getId(), FAILED, null, outcome.error(), now);
      payload.put("passed", false);
      payload.put("error", outcome.error());
      append(stage, "stage.judge.delta.verdict", payload, now);
      return Optional.of(new Decision(false, "No se pudo juzgar el delta: " + outcome.error()));
    }
    JudgeVerdicts.Aggregate aggregate = outcome.aggregate();
    String note =
        "delta "
            + (aggregate.passed() ? "revisado" : "suspendido")
            + " (nivel "
            + row.tier()
            + ", "
            + JudgeVerdicts.format(aggregate.score())
            + " / 5)";
    update(stage.getId(), aggregate.passed() ? PASSED : FAILED, aggregate, null, now);
    // Lo que encontraron los jueces del delta (RH-A): nadie lo corrige después, así que se aparca.
    findings.record(stage.getId(), 1, Findings.DELTA, outcome.verdicts(), now);
    findings.park(stage.getId(), now);
    payload.put("score", aggregate.score());
    payload.put("passed", aggregate.passed());
    payload.put("approvals", aggregate.approvals());
    payload.put("judges", aggregate.judges());
    payload.put(
        "blocking", aggregate.findings().stream().filter(JudgeVerdicts.Finding::blocking).count());
    payload.put(
        "refuted", aggregate.findings().stream().filter(JudgeVerdicts.Finding::discarded).count());
    append(stage, "stage.judge.delta.verdict", payload, now);
    return Optional.of(
        new Decision(
            aggregate.passed(),
            aggregate.passed()
                ? note
                : "El juez suspende lo que se cambió después de aprobar: "
                    + JudgeVerdicts.note(aggregate)));
  }

  /**
   * Los jueces del delta encontraron algo que bloquea y su nivel llega a {@code refuteMinRisk}
   * (AU-C): antes de suspender, los escépticos intentan refutarlo. Devuelve si se lanzaron.
   */
  private boolean refute(
      StageRun stage,
      StageRun judgeStage,
      StageDefinition definition,
      Row row,
      StageJudges.DeltaOutcome outcome,
      Instant now) {
    WorkspaceView source =
        judges
            .state(judgeStage.getId())
            .flatMap(state -> workspaces.find(state.workspaceId()))
            .orElse(null);
    if (source == null || source.unusable()) {
      return false;
    }
    int findings =
        judges.launchDeltaSkeptics(
            stage, judgeStage, definition, source, row.judgedSha(), outcome.verdicts(), now);
    if (findings == 0) {
      return false;
    }
    jdbc.sql("UPDATE judged_delta SET status = ?, updated_at = ? WHERE stage_run_id = ?")
        .params(REFUTING, Timestamp.from(now), stage.getId())
        .update();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("stageKey", stage.getStageKey());
    payload.put("judgeStageKey", judgeStage.getStageKey());
    payload.put("tier", row.tier());
    payload.put("findings", findings);
    payload.put("skeptics", definition.judge().refute());
    append(stage, "stage.judge.delta.refute.started", payload, now);
    return true;
  }

  /**
   * Para la puerta de fusión: qué se juzgó, qué se fusiona y qué pasó con lo de en medio, o {@code
   * null} si la ejecución no tiene un juez que aprobara. {@code mergeSha} es el commit de la PR.
   */
  Map<String, Object> summary(UUID runId, String mergeSha) {
    Judged judged = judged(runId).orElse(null);
    if (judged == null) {
      return null;
    }
    Row row =
        jdbc.sql(
                "SELECT * FROM judged_delta WHERE workflow_run_id = ? AND judge_stage_run_id = ?"
                    + " ORDER BY created_at DESC LIMIT 1")
            .params(runId, judged.stage().getId())
            .query(DeltaJudges::row)
            .optional()
            .filter(r -> mergeSha == null || mergeSha.equals(r.headSha()))
            .orElse(null);
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("stageKey", judged.stage().getStageKey());
    summary.put("judgedSha", judged.sha());
    summary.put("mergeSha", mergeSha);
    String delta;
    if (row != null) {
      summary.put("status", row.status());
      summary.put("tier", row.tier());
      summary.put("score", row.score());
      delta =
          switch (row.status()) {
            case UNCHANGED -> "sin cambios desde el juicio";
            case SKIPPED -> "delta de nivel 0, sin volver a juzgar";
            case JUDGING -> "juzgando el delta (nivel " + row.tier() + ")";
            case REFUTING -> "los escépticos revisan lo que encontró el juez del delta";
            case PASSED ->
                "delta revisado (nivel "
                    + row.tier()
                    + ", "
                    + JudgeVerdicts.format(row.score())
                    + " / 5)";
            default -> "el delta no pasó el juez";
          };
    } else if (mergeSha != null && !mergeSha.equals(judged.sha())) {
      summary.put("status", null);
      delta = "lo que cambió desde el juicio no se ha juzgado";
    } else {
      summary.put("status", UNCHANGED);
      delta = "sin cambios desde el juicio";
    }
    summary.put(
        "text",
        "Juzgado en `"
            + abbreviate(judged.sha())
            + "`"
            + (mergeSha == null ? "" : " · se fusiona `" + abbreviate(mergeSha) + "`")
            + " · "
            + delta);
    return summary;
  }

  // ---- Datos ----------------------------------------------------------------------------------

  private record Row(
      UUID judgeStageRunId,
      String judgedSha,
      String headSha,
      String status,
      Integer tier,
      java.math.BigDecimal score) {}

  private static Row row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
    return new Row(
        rs.getObject("judge_stage_run_id", UUID.class),
        rs.getString("judged_sha"),
        rs.getString("head_sha"),
        rs.getString("status"),
        rs.getObject("tier", Integer.class),
        rs.getBigDecimal("score"));
  }

  private Optional<Row> row(UUID stageRunId) {
    return jdbc.sql("SELECT * FROM judged_delta WHERE stage_run_id = ?")
        .param(stageRunId)
        .query(DeltaJudges::row)
        .optional();
  }

  /**
   * El último juez con panel (no una guía, VA-D) que aprobó en la ejecución, con lo que vio. La
   * guía no se vuelve a ejecutar sobre el delta: lo juzga el panel de jueces.
   */
  private Optional<Judged> judged(UUID runId) {
    List<Map<String, Object>> rows =
        jdbc.sql(
                "SELECT stage_run_id, judged_sha FROM stage_judge WHERE workflow_run_id = ? AND"
                    + " status = 'PASSED' AND judged_sha IS NOT NULL ORDER BY updated_at DESC")
            .param(runId)
            .query()
            .listOfRows();
    for (Map<String, Object> row : rows) {
      StageRun stage = stageRuns.findById((UUID) row.get("stage_run_id")).orElse(null);
      StageDefinition definition = stage == null ? null : definitionOf(stage);
      if (definition != null && definition.judge() != null && !definition.judge().isGuide()) {
        return Optional.of(new Judged(stage, definition, (String) row.get("judged_sha")));
      }
    }
    return Optional.empty();
  }

  /** El nivel que midió el runner al terminar la corrección, si se midió desde {@code judged}. */
  private Map<String, Object> measured(UUID agentRunId, String judged) {
    String risk =
        jdbc.sql(
                "SELECT CAST(metadata -> 'risk' AS text) FROM artifact WHERE agent_run_id = ? AND"
                    + " type = 'GIT_CHANGES' AND verification_run_id IS NULL ORDER BY created_at"
                    + " DESC LIMIT 1")
            .param(agentRunId)
            .query(String.class)
            .optional()
            .orElse(null);
    if (risk == null || risk.equals("null")) {
      return null;
    }
    Map<String, Object> map =
        json.readValue(risk, new TypeReference<LinkedHashMap<String, Object>>() {});
    return judged.equals(map.get("base")) ? map : null;
  }

  private StageDefinition definitionOf(StageRun stage) {
    WorkflowRun run = workflowRuns.findById(stage.getWorkflowRunId()).orElseThrow();
    WorkflowDefinition workflow = definitions.published(run.getDefinitionId()).definition();
    return workflow.stage(stage.getStageKey()).orElse(null);
  }

  private void save(
      StageRun stage,
      Judged judged,
      String headSha,
      String status,
      Integer tier,
      Map<String, Object> risk,
      String error,
      Instant now) {
    jdbc.sql(
            """
            INSERT INTO judged_delta (stage_run_id, workflow_run_id, judge_stage_run_id, judged_sha,
                head_sha, status, tier, risk, error, created_at, updated_at)
            VALUES (:stage, :run, :judge, :judged, :head, :status, :tier, CAST(:risk AS jsonb),
                :error, :now, :now)
            ON CONFLICT (stage_run_id) DO UPDATE SET judge_stage_run_id = :judge,
                judged_sha = :judged, head_sha = :head, status = :status, tier = :tier,
                risk = CAST(:risk AS jsonb), error = :error, score = NULL, approvals = NULL,
                judges = NULL, findings = '[]'::jsonb, updated_at = :now
            """)
        .param("stage", stage.getId())
        .param("run", stage.getWorkflowRunId())
        .param("judge", judged.stage().getId())
        .param("judged", judged.sha())
        .param("head", headSha)
        .param("status", status)
        .param("tier", tier)
        .param("risk", risk == null ? null : json.writeValueAsString(risk))
        .param("error", error)
        .param("now", Timestamp.from(now))
        .update();
  }

  private void update(
      UUID stageRunId,
      String status,
      JudgeVerdicts.Aggregate aggregate,
      String error,
      Instant now) {
    jdbc.sql(
            "UPDATE judged_delta SET status = :status, score = :score, approvals = :approvals,"
                + " judges = :judges, findings = CAST(:findings AS jsonb), error = :error,"
                + " updated_at = :now WHERE stage_run_id = :stage")
        .param("status", status)
        .param("score", aggregate == null ? null : aggregate.score())
        .param("approvals", aggregate == null ? null : aggregate.approvals())
        .param("judges", aggregate == null ? null : aggregate.judges())
        .param(
            "findings",
            json.writeValueAsString(
                aggregate == null ? List.of() : StageJudges.findingsOf(aggregate.findings())))
        .param("error", error)
        .param("now", Timestamp.from(now))
        .param("stage", stageRunId)
        .update();
  }

  private void event(
      StageRun stage,
      Judged judged,
      String headSha,
      String type,
      Map<String, Object> risk,
      List<Map<String, Object>> launched,
      String note,
      Instant now) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("stageKey", stage.getStageKey());
    payload.put("judgeStageKey", judged.stage().getStageKey());
    payload.put("judgedSha", judged.sha());
    payload.put("headSha", headSha);
    if (risk != null) {
      payload.put("tier", risk.get("tier"));
      payload.put("files", risk.get("files"));
      payload.put("lines", risk.get("lines"));
      payload.put("summary", risk.get("summary"));
    }
    if (launched != null) {
      payload.put("judges", launched);
    }
    if (note != null) {
      payload.put("note", note);
    }
    append(stage, type, payload, now);
  }

  private void append(StageRun stage, String type, Map<String, Object> payload, Instant now) {
    transitions.append("stage_run", stage.getId(), type, stage.getWorkflowRunId(), payload, now);
  }

  private static Integer number(Object value) {
    return value instanceof Number n ? n.intValue() : null;
  }

  static String abbreviate(String sha) {
    return sha == null || sha.length() <= 7 ? sha : sha.substring(0, 7);
  }
}
