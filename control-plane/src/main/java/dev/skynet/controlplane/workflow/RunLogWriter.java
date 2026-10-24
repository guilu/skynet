package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.memory.MemoryContent;
import dev.skynet.controlplane.memory.MemoryKind;
import dev.skynet.controlplane.memory.MemoryService;
import dev.skynet.controlplane.memory.MemorySource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * La bitácora de la memoria (ME-C): al terminar una ejecución, Skynet deja en cada repositorio en
 * el que trabajó una observación {@code RUN_LOG} con el workflow, el estado de sus fases, la rama,
 * los commits, la salida estructurada de sus agentes, el coste y el enlace a la ejecución. Se
 * escribe sin modelo, a partir de lo que ya está en la base de datos, con el tema {@code
 * bitacora/<ejecución>}: volver a escribirla no crea otra.
 *
 * <p>Va después del commit y en su propia transacción: si falla, la ejecución termina igual y queda
 * un aviso en el log. Los commits salen del resumen del artefacto {@code GIT_CHANGES}, que el
 * runner sube aparte: si aún no ha llegado, la bitácora solo dice la rama.
 */
@Component
class RunLogWriter {

  private static final Logger log = LoggerFactory.getLogger(RunLogWriter.class);

  static final String TOPIC_PREFIX = "bitacora/";
  static final int MAX_FIELD = 4_000;
  static final int MAX_TITLE = 200;
  static final int MAX_OUTPUT = 600;

  private final JdbcClient jdbc;
  private final MemoryService memory;
  private final JsonMapper json = JsonMapper.builder().build();

  RunLogWriter(JdbcClient jdbc, MemoryService memory) {
    this.jdbc = jdbc;
    this.memory = memory;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void on(WorkflowRunFinished finished) {
    try {
      write(finished.workflowRunId());
    } catch (RuntimeException e) {
      log.warn("No se pudo escribir la bitácora de la ejecución {}", finished.workflowRunId(), e);
    }
  }

  private record Run(
      UUID id,
      UUID projectId,
      String workItemKey,
      String workItemTitle,
      String workflow,
      int version,
      String status) {}

  private record Agent(
      UUID id,
      UUID repositoryId,
      String repository,
      String stageKey,
      String childKey,
      String status,
      String provider,
      BigDecimal cost,
      String branch,
      String structuredOutput) {}

  void write(UUID runId) {
    Run run =
        jdbc.sql(
                "SELECT r.id, w.project_id, w.key AS item_key, w.title, d.key AS workflow,"
                    + " d.version, r.status FROM workflow_run r"
                    + " JOIN work_item w ON w.id = r.work_item_id"
                    + " JOIN workflow_definition d ON d.id = r.definition_id WHERE r.id = ?")
            .param(runId)
            .query(
                (rs, n) ->
                    new Run(
                        rs.getObject("id", UUID.class),
                        rs.getObject("project_id", UUID.class),
                        rs.getString("item_key"),
                        rs.getString("title"),
                        rs.getString("workflow"),
                        rs.getInt("version"),
                        rs.getString("status")))
            .single();
    List<Agent> agents =
        jdbc.sql(
                "SELECT a.id, a.repository_id, p.name AS repository, s.stage_key, a.child_key,"
                    + " a.status, a.provider, a.cost_usd, ws.branch, a.structured_output"
                    + " FROM agent_run a JOIN stage_run s ON s.id = a.stage_run_id"
                    + " JOIN repository p ON p.id = a.repository_id"
                    + " LEFT JOIN workspace ws ON ws.id = a.workspace_id"
                    + " WHERE s.workflow_run_id = ? ORDER BY a.created_at, a.id")
            .param(runId)
            .query(
                (rs, n) ->
                    new Agent(
                        rs.getObject("id", UUID.class),
                        rs.getObject("repository_id", UUID.class),
                        rs.getString("repository"),
                        rs.getString("stage_key"),
                        rs.getString("child_key"),
                        rs.getString("status"),
                        rs.getString("provider"),
                        rs.getBigDecimal("cost_usd"),
                        rs.getString("branch"),
                        rs.getString("structured_output")))
            .list();
    Map<UUID, List<Agent>> byRepository = new LinkedHashMap<>();
    agents.forEach(
        a -> byRepository.computeIfAbsent(a.repositoryId(), k -> new ArrayList<>()).add(a));
    String stages = stages(runId);
    for (Map.Entry<UUID, List<Agent>> entry : byRepository.entrySet()) {
      memory.save(
          run.projectId(),
          entry.getKey(),
          content(run, stages, entry.getValue()),
          MemorySource.system(runId));
    }
  }

  private MemoryContent content(Run run, String stages, List<Agent> agents) {
    String title =
        clip(
            "Ejecución "
                + run.workflow()
                + " v"
                + run.version()
                + " de "
                + run.workItemKey()
                + ": "
                + statusText(run.status()),
            MAX_TITLE);
    StringBuilder what = new StringBuilder();
    what.append("Workflow: ")
        .append(run.workflow())
        .append(" v")
        .append(run.version())
        .append(", sobre ")
        .append(run.workItemKey())
        .append(" «")
        .append(run.workItemTitle())
        .append("».\nEstado: ")
        .append(run.status())
        .append(".\nFases: ")
        .append(stages)
        .append('.');
    List<String> changes = changes(agents);
    if (!changes.isEmpty()) {
      what.append("\nCambios:");
      changes.forEach(c -> what.append("\n- ").append(c));
    } else {
      List<String> branches =
          agents.stream().map(Agent::branch).filter(b -> b != null).distinct().toList();
      if (!branches.isEmpty()) {
        what.append("\nRama: ").append(String.join(", ", branches)).append('.');
      }
    }
    BigDecimal cost =
        agents.stream()
            .map(Agent::cost)
            .filter(c -> c != null)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    what.append("\nCoste: ")
        .append(cost.setScale(2, RoundingMode.HALF_UP).toPlainString())
        .append(" USD.\nEnlace: /runs/")
        .append(run.id());
    StringBuilder learned = new StringBuilder();
    for (Agent agent : agents) {
      if (agent.structuredOutput() == null) {
        continue;
      }
      learned
          .append(learned.isEmpty() ? "" : "\n")
          .append(
              agent.childKey() == null
                  ? agent.stageKey()
                  : agent.stageKey() + "." + agent.childKey())
          .append(" (")
          .append(agent.provider())
          .append(", ")
          .append(agent.status())
          .append("): ")
          .append(clip(agent.structuredOutput(), MAX_OUTPUT));
    }
    return new MemoryContent(
        MemoryKind.RUN_LOG,
        title,
        TOPIC_PREFIX + run.id(),
        clip(what.toString(), MAX_FIELD),
        null,
        null,
        learned.isEmpty() ? null : clip("Salida estructurada:\n" + learned, MAX_FIELD),
        List.of("bitacora", run.workflow(), run.status().toLowerCase(Locale.ROOT)));
  }

  /** Cada fase con el estado de su último intento, en el orden en que se crearon. */
  private String stages(UUID runId) {
    Map<String, String> latest = new LinkedHashMap<>();
    jdbc.sql(
            "SELECT stage_key, status FROM stage_run WHERE workflow_run_id = ?"
                + " ORDER BY created_at, attempt")
        .param(runId)
        .query((rs, n) -> Map.entry(rs.getString("stage_key"), rs.getString("status")))
        .list()
        .forEach(e -> latest.put(e.getKey(), e.getValue()));
    if (latest.isEmpty()) {
      return "ninguna";
    }
    List<String> parts = new ArrayList<>();
    latest.forEach((key, status) -> parts.add(key + " " + status));
    return String.join(", ", parts);
  }

  /**
   * Los cambios de cada rama según el último artefacto {@code GIT_CHANGES} de sus agentes: commits,
   * archivos, líneas y el {@code HEAD}.
   */
  private List<String> changes(List<Agent> agents) {
    Map<String, Map<String, Object>> byBranch = new LinkedHashMap<>();
    for (Agent agent : agents) {
      jdbc.sql(
              "SELECT CAST(metadata AS text) FROM artifact WHERE agent_run_id = ?"
                  + " AND verification_run_id IS NULL AND type = 'GIT_CHANGES'"
                  + " ORDER BY created_at")
          .param(agent.id())
          .query(String.class)
          .list()
          .forEach(
              m -> {
                Map<String, Object> summary = summary(m);
                if (summary.get("branch") != null) {
                  byBranch.put(String.valueOf(summary.get("branch")), summary);
                }
              });
    }
    List<String> lines = new ArrayList<>();
    byBranch.forEach(
        (branch, summary) -> {
          StringBuilder line =
              new StringBuilder(branch)
                  .append(": ")
                  .append(number(summary, "commits"))
                  .append(" commits, ")
                  .append(number(summary, "files"))
                  .append(" archivos (+")
                  .append(number(summary, "insertions"))
                  .append(" −")
                  .append(number(summary, "deletions"))
                  .append(')');
          if (summary.get("headCommit") instanceof String head && !head.isBlank()) {
            line.append(", HEAD ").append(head, 0, Math.min(12, head.length()));
          }
          if (number(summary, "uncommittedFiles") > 0) {
            line.append(", ").append(number(summary, "uncommittedFiles")).append(" sin confirmar");
          }
          lines.add(line.toString());
        });
    return lines;
  }

  private Map<String, Object> summary(String metadata) {
    try {
      return json.readValue(metadata, new TypeReference<Map<String, Object>>() {});
    } catch (JacksonException e) {
      return Map.of();
    }
  }

  private static long number(Map<String, Object> summary, String key) {
    return summary.get(key) instanceof Number n ? n.longValue() : 0;
  }

  private static String statusText(String status) {
    return switch (status) {
      case "SUCCEEDED" -> "terminada bien";
      case "FAILED" -> "fallida";
      case "CANCELLED" -> "cancelada";
      default -> status;
    };
  }

  private static String clip(String text, int max) {
    return text.length() <= max ? text : text.substring(0, max - 1) + "…";
  }
}
