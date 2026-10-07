package dev.skynet.controlplane.runner;

import dev.skynet.controlplane.shared.NotFoundException;
import dev.skynet.controlplane.shared.TimeSource;
import dev.skynet.controlplane.workflow.AgentCancelRequested;
import dev.skynet.controlplane.workflow.AgentRunQueued;
import dev.skynet.controlplane.workflow.AgentRunWithdrawn;
import dev.skynet.controlplane.workflow.RunService;
import dev.skynet.controlplane.workflow.VerificationQueued;
import dev.skynet.controlplane.workflow.WorkspaceCleanupRequested;
import dev.skynet.protocol.runner.CleanupWorkspace;
import dev.skynet.protocol.runner.RunVerification;
import dev.skynet.protocol.runner.RunnerCommand;
import dev.skynet.protocol.runner.RunnerCommandType;
import dev.skynet.protocol.runner.StartAgent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Cola de órdenes para los runners.
 *
 * <p>Las órdenes se crean en la misma transacción que el cambio que las origina (lanzar o cancelar
 * un agente), escuchando los eventos de dominio del módulo {@code workflow}.
 */
@Component
class CommandQueue {

  private static final String PENDING = "PENDING";
  private static final String DELIVERED = "DELIVERED";
  private static final String ACKED = "ACKED";
  private static final String CANCELLED = "CANCELLED";

  private final JdbcClient jdbc;
  private final ObjectMapper json;
  private final TimeSource time;
  private final RunService runs;
  private final RunnerRegistry registry;
  private final RunnerProperties properties;
  private final CommandSignal signal;

  CommandQueue(
      JdbcClient jdbc,
      ObjectMapper json,
      TimeSource time,
      RunService runs,
      RunnerRegistry registry,
      RunnerProperties properties,
      CommandSignal signal) {
    this.jdbc = jdbc;
    this.json = json;
    this.time = time;
    this.runs = runs;
    this.registry = registry;
    this.properties = properties;
    this.signal = signal;
  }

  /**
   * Orden de arranque: sin runner, la reclamará el primero con capacidad libre; una reanudación va
   * al runner de la invocación anterior, que la recibirá cuando tenga capacidad.
   */
  @EventListener
  void on(AgentRunQueued queued) {
    insert(
        queued.runnerId(),
        queued.agentRunId(),
        queued.isResume() ? RunnerCommandType.RESUME : RunnerCommandType.START,
        json.writeValueAsString(queued.start()));
  }

  /** Orden de cancelación para el runner que ejecuta el agente. */
  @EventListener
  void on(AgentCancelRequested requested) {
    insert(requested.runnerId(), requested.agentRunId(), RunnerCommandType.CANCEL, null);
  }

  /** Orden de verificación para el runner del worktree. */
  @EventListener
  void on(VerificationQueued queued) {
    insert(
        queued.runnerId(),
        queued.agentRunId(),
        RunnerCommandType.VERIFY,
        json.writeValueAsString(queued.verify()));
  }

  /** Orden de eliminar un worktree, para su runner. */
  @EventListener
  void on(WorkspaceCleanupRequested requested) {
    insert(
        requested.runnerId(),
        requested.agentRunId(),
        RunnerCommandType.CLEANUP,
        json.writeValueAsString(new CleanupWorkspace(requested.workspaceId(), requested.path())));
  }

  /**
   * Cuenta, para cada invocación confirmada y aún activa del runner, los latidos seguidos en los
   * que no la ha declarado, y devuelve las que llegan a {@code threshold}: su proceso ya no existe.
   * Un runner declara también las invocaciones terminadas cuyo fin aún no ha enviado, así que una
   * que acaba de terminar no se da por perdida.
   */
  @Transactional
  List<UUID> missedBy(UUID runnerId, List<UUID> reported, int threshold) {
    // IN () no es SQL válido: sin nada declarado, un id que no existe.
    List<UUID> present = reported.isEmpty() ? List.of(new UUID(0, 0)) : reported;
    return jdbc
        .sql(
            "UPDATE runner_command SET missed_heartbeats = CASE WHEN agent_run_id IN (:present)"
                + " THEN 0 ELSE missed_heartbeats + 1 END WHERE runner_id = :runner"
                + " AND status = :acked AND type IN (:start, :resume)"
                + " AND agent_run_id IN (SELECT id FROM agent_run WHERE status NOT IN"
                + " ('COMPLETED', 'FAILED', 'CANCELLED'))"
                + " RETURNING agent_run_id, missed_heartbeats")
        .param("present", present)
        .param("runner", runnerId)
        .param("acked", ACKED)
        .param("start", RunnerCommandType.START.name())
        .param("resume", RunnerCommandType.RESUME.name())
        .query(
            (rs, n) ->
                rs.getInt("missed_heartbeats") >= threshold
                    ? rs.getObject("agent_run_id", UUID.class)
                    : null)
        .list()
        .stream()
        .filter(Objects::nonNull)
        .toList();
  }

  /** El agente se canceló en cola: su arranque ya no debe entregarse. */
  @EventListener
  void on(AgentRunWithdrawn withdrawn) {
    jdbc.sql(
            "UPDATE runner_command SET status = ? WHERE agent_run_id = ? AND type IN (?, ?)"
                + " AND status = ?")
        .params(
            CANCELLED,
            withdrawn.agentRunId(),
            RunnerCommandType.START.name(),
            RunnerCommandType.RESUME.name(),
            PENDING)
        .update();
  }

  /**
   * Reclama las órdenes que corresponden al runner: las suyas pendientes, las entregadas hace más
   * de {@code redeliverAfter} sin confirmar y, mientras tenga capacidad libre, sus reanudaciones
   * pendientes y arranques sin asignar. Una invocación reclamada pasa el agente a {@code STARTING}
   * en la misma transacción.
   */
  @Transactional
  List<RunnerCommand> claim(UUID runnerId) {
    Instant now = time.now();
    // Una invocación sin confirmar cuyo agente ya terminó no debe volver a entregarse.
    jdbc.sql(
            "UPDATE runner_command SET status = ? WHERE runner_id = ? AND status = ?"
                + " AND type IN (?, ?) AND agent_run_id IN (SELECT id FROM agent_run"
                + " WHERE status IN ('COMPLETED', 'FAILED', 'CANCELLED'))")
        .params(
            CANCELLED,
            runnerId,
            DELIVERED,
            RunnerCommandType.START.name(),
            RunnerCommandType.RESUME.name())
        .update();
    List<Row> claimed = new ArrayList<>();
    // Las reanudaciones aún no entregadas esperan a que haya capacidad, como los arranques.
    claimed.addAll(
        jdbc.sql(
                "SELECT * FROM runner_command WHERE runner_id = ? AND ((status = ? AND NOT"
                    + " (type = ? AND delivery_count = 0)) OR (status = ? AND delivered_at < ?))"
                    + " ORDER BY created_at FOR UPDATE SKIP LOCKED")
            .params(
                runnerId,
                PENDING,
                RunnerCommandType.RESUME.name(),
                DELIVERED,
                Timestamp.from(now.minus(properties.redeliverAfter())))
            .query(this::row)
            .list());

    int free = registry.capacity(runnerId) - activeAgents(runnerId);
    if (free > 0) {
      List<Row> invocations =
          jdbc.sql(
                  "SELECT * FROM runner_command WHERE status = ? AND ((runner_id IS NULL AND type"
                      + " = ?) OR (runner_id = ? AND type = ? AND delivery_count = 0))"
                      + " ORDER BY created_at LIMIT ? FOR UPDATE SKIP LOCKED")
              .params(
                  PENDING,
                  RunnerCommandType.START.name(),
                  runnerId,
                  RunnerCommandType.RESUME.name(),
                  free)
              .query(this::row)
              .list();
      for (Row invocation : invocations) {
        if (runs.assignRunner(invocation.agentRunId(), runnerId)) {
          jdbc.sql("UPDATE runner_command SET runner_id = ? WHERE id = ?")
              .params(runnerId, invocation.id())
              .update();
          claimed.add(invocation);
        } else {
          jdbc.sql("UPDATE runner_command SET status = ? WHERE id = ?")
              .params(CANCELLED, invocation.id())
              .update();
        }
      }
    }

    for (Row row : claimed) {
      jdbc.sql(
              "UPDATE runner_command SET status = ?, delivered_at = ?,"
                  + " delivery_count = delivery_count + 1 WHERE id = ?")
          .params(DELIVERED, Timestamp.from(now), row.id())
          .update();
    }
    return claimed.stream().map(this::command).toList();
  }

  /** Confirma una orden. Confirmarla otra vez no tiene efecto. */
  @Transactional
  void ack(UUID runnerId, UUID commandId) {
    int updated =
        jdbc.sql(
                "UPDATE runner_command SET status = ?, acked_at = ? WHERE id = ? AND runner_id = ?"
                    + " AND status = ?")
            .params(ACKED, Timestamp.from(time.now()), commandId, runnerId, DELIVERED)
            .update();
    if (updated == 0
        && jdbc.sql("SELECT count(*) FROM runner_command WHERE id = ? AND runner_id = ?")
                .params(commandId, runnerId)
                .query(Integer.class)
                .single()
            == 0) {
      throw new NotFoundException("Orden", commandId);
    }
  }

  private int activeAgents(UUID runnerId) {
    return jdbc.sql(
            "SELECT count(*) FROM agent_run WHERE runner_id = ?"
                + " AND status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')")
        .param(runnerId)
        .query(Integer.class)
        .single();
  }

  private void insert(UUID runnerId, UUID agentRunId, RunnerCommandType type, String payload) {
    jdbc.sql(
            "INSERT INTO runner_command (id, runner_id, agent_run_id, type, payload, status,"
                + " created_at) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)")
        .params(
            UUID.randomUUID(),
            runnerId,
            agentRunId,
            type.name(),
            payload,
            PENDING,
            Timestamp.from(time.now()))
        .update();
    signal.wakeUpAfterCommit();
  }

  private RunnerCommand command(Row row) {
    StartAgent start = null;
    RunVerification verify = null;
    CleanupWorkspace cleanup = null;
    if (row.type() == RunnerCommandType.VERIFY) {
      verify = json.readValue(row.payload(), RunVerification.class);
    } else if (row.type() == RunnerCommandType.CLEANUP) {
      cleanup = json.readValue(row.payload(), CleanupWorkspace.class);
    } else if (row.payload() != null) {
      start = json.readValue(row.payload(), StartAgent.class);
    }
    return new RunnerCommand(
        row.id(), row.type(), row.agentRunId(), row.createdAt(), start, verify, cleanup);
  }

  private Row row(ResultSet rs, int n) throws SQLException {
    return new Row(
        rs.getObject("id", UUID.class),
        rs.getObject("agent_run_id", UUID.class),
        RunnerCommandType.valueOf(rs.getString("type")),
        rs.getString("payload"),
        rs.getTimestamp("created_at").toInstant());
  }

  private record Row(
      UUID id, UUID agentRunId, RunnerCommandType type, String payload, Instant createdAt) {}
}
