package dev.skynet.controlplane.workflow;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Verificaciones de los worktrees y sus estados. */
@Component
class Verifications {

  static final String QUEUED = "QUEUED";
  static final String RUNNING = "RUNNING";
  static final String PASSED = "PASSED";
  static final String FAILED = "FAILED";
  static final String ERROR = "ERROR";

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
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO verification_run (id, agent_run_id, workspace_id, runner_id, trigger,"
                + " command, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
        .params(
            id, agentRunId, workspaceId, runnerId, trigger, command, QUEUED, Timestamp.from(now))
        .update();
    return id;
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
                + " started_at = COALESCE(started_at, ?), finished_at = ?"
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
            id)
        .update();
  }

  /**
   * Resultado de una verificación. Fallar los tests es {@code FAILED}; no poder ejecutar el comando
   * o agotar su tiempo es {@code ERROR}.
   */
  record Outcome(
      Integer exitCode, String signal, String error, VerificationResult.TestTotals tests) {

    String status() {
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
        instant(rs, "finished_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}
