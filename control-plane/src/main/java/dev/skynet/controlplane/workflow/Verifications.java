package dev.skynet.controlplane.workflow;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Verificaciones de los worktrees y sus estados. */
@Component
class Verifications {

  static final String QUEUED = "QUEUED";
  static final String RUNNING = "RUNNING";
  static final String PASSED = "PASSED";
  static final String FAILED = "FAILED";
  static final String ERROR = "ERROR";

  private static final ObjectMapper JSON = JsonMapper.shared();

  private final JdbcClient jdbc;

  Verifications(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  UUID create(
      UUID agentRunId,
      UUID workspaceId,
      UUID runnerId,
      String trigger,
      String command,
      Instant now) {
    return create(agentRunId, workspaceId, runnerId, trigger, command, null, List.of(), now);
  }

  /** Con {@code stageRunId}, la verificación de esa fase con sus comprobaciones pendientes. */
  UUID create(
      UUID agentRunId,
      UUID workspaceId,
      UUID runnerId,
      String trigger,
      String command,
      UUID stageRunId,
      List<VerificationResult.CheckResult> checks,
      Instant now) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO verification_run (id, agent_run_id, workspace_id, runner_id, trigger,"
                + " command, status, created_at, stage_run_id, checks)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))")
        .params(
            id,
            agentRunId,
            workspaceId,
            runnerId,
            trigger,
            command,
            QUEUED,
            Timestamp.from(now),
            stageRunId,
            stageRunId == null ? null : JSON.writeValueAsString(checks))
        .update();
    return id;
  }

  /** La última verificación de fase de cada agente de {@code stageRunId}, por agente. */
  Map<UUID, VerificationResult> latestOfStage(UUID stageRunId) {
    Map<UUID, VerificationResult> latest = new LinkedHashMap<>();
    jdbc.sql(
            "SELECT * FROM verification_run WHERE stage_run_id = ?"
                + " ORDER BY created_at ASC, id ASC")
        .param(stageRunId)
        .query(this::result)
        .list()
        .forEach(v -> latest.put(v.agentRunId(), v));
    return latest;
  }

  /**
   * La última verificación de fase de cada agente de {@code stageRunIds}, por fase y en el orden en
   * que se crearon sus agentes.
   */
  Map<UUID, List<VerificationResult>> latestOfStages(List<UUID> stageRunIds) {
    if (stageRunIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, Map<UUID, VerificationResult>> latest = new LinkedHashMap<>();
    jdbc.sql(
            "SELECT * FROM verification_run WHERE stage_run_id IN (:ids)"
                + " ORDER BY created_at ASC, id ASC")
        .param("ids", stageRunIds)
        .query(this::result)
        .list()
        .forEach(
            v ->
                latest
                    .computeIfAbsent(v.stageRunId(), k -> new LinkedHashMap<>())
                    .put(v.agentRunId(), v));
    Map<UUID, List<VerificationResult>> byStage = new LinkedHashMap<>();
    latest.forEach((stage, results) -> byStage.put(stage, List.copyOf(results.values())));
    return byStage;
  }

  /** Si alguna verificación de {@code stageRunId} ha terminado sin pasar. */
  boolean stageFailed(UUID stageRunId) {
    return jdbc.sql(
                "SELECT count(*) FROM verification_run WHERE stage_run_id = ?"
                    + " AND status IN ('FAILED', 'ERROR')")
            .param(stageRunId)
            .query(Integer.class)
            .single()
        > 0;
  }

  Optional<VerificationResult> find(UUID id) {
    return jdbc.sql("SELECT * FROM verification_run WHERE id = ?")
        .param(id)
        .query(this::result)
        .optional();
  }

  /** Verificaciones de un agente, de la más reciente a la más antigua. */
  List<VerificationResult> ofAgent(UUID agentRunId) {
    return jdbc.sql(
            "SELECT * FROM verification_run WHERE agent_run_id = ?"
                + " ORDER BY created_at DESC, id DESC")
        .param(agentRunId)
        .query(this::result)
        .list();
  }

  /** El runner ha empezado a ejecutar el comando. */
  void started(UUID id, Instant at) {
    jdbc.sql("UPDATE verification_run SET status = ?, started_at = ? WHERE id = ? AND status = ?")
        .params(RUNNING, Timestamp.from(at), id, QUEUED)
        .update();
  }

  /** Resultado del comando. Una verificación ya terminada no cambia. */
  void completed(UUID id, Outcome outcome, Instant at) {
    VerificationResult.TestTotals tests = outcome.tests();
    jdbc.sql(
            "UPDATE verification_run SET status = ?, exit_code = ?, signal = ?, error = ?,"
                + " tests_total = ?, tests_failed = ?, tests_errors = ?, tests_skipped = ?,"
                + " started_at = COALESCE(started_at, ?), finished_at = ?,"
                + " checks = COALESCE(CAST(? AS jsonb), checks)"
                + " WHERE id = ? AND status IN ('QUEUED', 'RUNNING')")
        .params(
            outcome.status(),
            outcome.exitCode(),
            outcome.signal(),
            outcome.error(),
            tests == null ? null : tests.total(),
            tests == null ? null : tests.failed(),
            tests == null ? null : tests.errors(),
            tests == null ? null : tests.skipped(),
            Timestamp.from(at),
            Timestamp.from(at),
            outcome.checks() == null ? null : JSON.writeValueAsString(outcome.checks()),
            id)
        .update();
  }

  /**
   * Resultado de una verificación. Fallar los tests es {@code FAILED}; no poder ejecutar el comando
   * o agotar su tiempo es {@code ERROR}. La de una fase ({@code checks}) pasa si pasan todas sus
   * comprobaciones, y sin ellas (el runner no pudo verificar) es {@code ERROR}.
   */
  record Outcome(
      Integer exitCode,
      String signal,
      String error,
      VerificationResult.TestTotals tests,
      List<VerificationResult.CheckResult> checks) {

    Outcome(Integer exitCode, String signal, String error, VerificationResult.TestTotals tests) {
      this(exitCode, signal, error, tests, null);
    }

    String status() {
      if (checks != null) {
        return checks.stream().allMatch(c -> Boolean.TRUE.equals(c.passed())) ? PASSED : FAILED;
      }
      if (error != null) {
        return ERROR;
      }
      boolean testsPassed = tests == null || tests.failed() + tests.errors() == 0;
      return exitCode != null && exitCode == 0 && testsPassed ? PASSED : FAILED;
    }
  }

  private VerificationResult result(ResultSet rs, int row) throws SQLException {
    Integer total = (Integer) rs.getObject("tests_total");
    VerificationResult.TestTotals tests =
        total == null
            ? null
            : new VerificationResult.TestTotals(
                total,
                rs.getInt("tests_failed"),
                rs.getInt("tests_errors"),
                rs.getInt("tests_skipped"));
    return new VerificationResult(
        rs.getObject("id", UUID.class),
        rs.getObject("agent_run_id", UUID.class),
        rs.getString("trigger"),
        rs.getString("status"),
        rs.getString("command"),
        (Integer) rs.getObject("exit_code"),
        rs.getString("signal"),
        rs.getString("error"),
        tests,
        instant(rs, "created_at"),
        instant(rs, "started_at"),
        instant(rs, "finished_at"),
        rs.getObject("stage_run_id", UUID.class),
        checks(rs.getString("checks")));
  }

  private static List<VerificationResult.CheckResult> checks(String json) {
    if (json == null) {
      return List.of();
    }
    return List.of(JSON.readValue(json, VerificationResult.CheckResult[].class));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}
