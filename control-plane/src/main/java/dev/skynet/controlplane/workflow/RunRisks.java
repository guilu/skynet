package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.artifact.ArtifactCreated;
import dev.skynet.controlplane.event.EventDraft;
import dev.skynet.controlplane.event.EventStore;
import dev.skynet.controlplane.shared.TimeSource;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * El nivel de riesgo de las fases y de la ejecución (AU-A). El runner lo mide al terminar cada
 * agente que trabaja en el worktree de la ejecución y lo manda con sus cambios ({@code
 * GIT_CHANGES}); aquí se guarda en {@code stage_run.risk} (el máximo de sus agentes) y en {@code
 * workflow_run.risk} (el máximo de sus fases), cada uno con el resultado que lo fijó. Solo sube:
 * una corrección posterior no baja el nivel de lo que ya se hizo.
 */
@Component
class RunRisks {

  private final JdbcClient jdbc;
  private final AgentRunRepository agentRuns;
  private final StageRunRepository stageRuns;
  private final EventStore events;
  private final ObjectMapper json;
  private final TimeSource time;

  RunRisks(
      JdbcClient jdbc,
      AgentRunRepository agentRuns,
      StageRunRepository stageRuns,
      EventStore events,
      ObjectMapper json,
      TimeSource time) {
    this.jdbc = jdbc;
    this.time = time;
    this.agentRuns = agentRuns;
    this.stageRuns = stageRuns;
    this.events = events;
    this.json = json;
  }

  /**
   * Un agente ha subido sus cambios ({@code GIT_CHANGES}): si traen el nivel de riesgo, se guarda.
   * Va en la transacción que guarda el artefacto.
   */
  @EventListener
  void on(ArtifactCreated artifact) {
    if (!"GIT_CHANGES".equals(artifact.type()) || artifact.verificationRunId() != null) {
      return;
    }
    if (artifact.metadata().get("risk") instanceof Map<?, ?> measured) {
      @SuppressWarnings("unchecked")
      Map<String, Object> risk = (Map<String, Object>) measured;
      record(artifact.agentRunId(), artifact.artifactId(), risk, time.now());
    }
  }

  /** Guarda el nivel {@code risk} que midió el runner en los cambios de {@code agentRunId}. */
  void record(UUID agentRunId, UUID artifactId, Map<String, Object> risk, Instant now) {
    if (!(risk.get("tier") instanceof Number number)) {
      return;
    }
    int tier = number.intValue();
    if (tier < 0 || tier > 2) {
      return;
    }
    AgentRun agent = agentRuns.findById(agentRunId).orElse(null);
    if (agent == null || agent.getStageRunId() == null) {
      return;
    }
    StageRun stage = stageRuns.findById(agent.getStageRunId()).orElse(null);
    if (stage == null) {
      return;
    }
    String detail = json.writeValueAsString(risk);
    jdbc.sql(
            "UPDATE stage_run SET risk = :tier, risk_detail = CAST(:detail AS jsonb)"
                + " WHERE id = :id AND (risk IS NULL OR risk <= :tier)")
        .param("tier", tier)
        .param("detail", detail)
        .param("id", stage.getId())
        .update();
    Integer before =
        jdbc.sql("SELECT risk FROM workflow_run WHERE id = ? FOR UPDATE")
            .param(stage.getWorkflowRunId())
            .query(Integer.class)
            .optional()
            .orElse(null);
    if (before == null || before <= tier) {
      jdbc.sql(
              "UPDATE workflow_run SET risk = :tier, risk_detail = CAST(:detail AS jsonb)"
                  + " WHERE id = :id")
          .param("tier", tier)
          .param("detail", detail)
          .param("id", stage.getWorkflowRunId())
          .update();
    }

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("stageKey", stage.getStageKey());
    if (agent.getChildKey() != null) {
      payload.put("childKey", agent.getChildKey());
    }
    payload.put("agentRunId", agent.getId());
    payload.put("artifactId", artifactId);
    payload.put("tier", tier);
    payload.put("summary", risk.get("summary"));
    payload.put("files", risk.get("files"));
    payload.put("lines", risk.get("lines"));
    events.append(
        EventDraft.of(
            "stage_run",
            stage.getId(),
            "stage.risk.assessed",
            stage.getWorkflowRunId(),
            payload,
            now));
    if (before == null || before < tier) {
      Map<String, Object> raised = new LinkedHashMap<>();
      if (before != null) {
        raised.put("from", before);
      }
      raised.put("to", tier);
      raised.put("stageKey", stage.getStageKey());
      raised.put("summary", risk.get("summary"));
      events.append(
          EventDraft.of(
              "workflow_run",
              stage.getWorkflowRunId(),
              "run.risk.raised",
              stage.getWorkflowRunId(),
              raised,
              now));
    }
  }

  /**
   * El nivel de lo que han cambiado los agentes de {@code runId} en el worktree {@code workspaceId}
   * (AU-C): el máximo de lo que midió el runner al terminar cada uno. Vacío si alguno terminó bien
   * sin medida (aún no ha llegado o no se midió) o si no hay ninguno: sin saberlo, se trata como si
   * pudiera ser cualquiera.
   */
  Optional<Integer> workspaceTier(UUID runId, UUID workspaceId) {
    List<Integer> tiers =
        jdbc.sql(
                """
                SELECT (SELECT CAST(x.metadata -> 'risk' ->> 'tier' AS integer) FROM artifact x
                        WHERE x.agent_run_id = a.id AND x.type = 'GIT_CHANGES'
                          AND x.verification_run_id IS NULL
                        ORDER BY x.created_at DESC LIMIT 1) AS tier
                FROM agent_run a JOIN stage_run s ON s.id = a.stage_run_id
                WHERE s.workflow_run_id = :run AND a.workspace_id = :workspace
                  AND a.role IS NULL AND a.status = 'COMPLETED'
                  AND a.provider NOT IN ('command', 'git-push', 'verification')
                """)
            .param("run", runId)
            .param("workspace", workspaceId)
            .query((rs, n) -> rs.getObject("tier", Integer.class))
            .list();
    if (tiers.isEmpty() || tiers.contains(null)) {
      return Optional.empty();
    }
    return tiers.stream().max(Integer::compare);
  }

  /** El nivel de cada fase de {@code stageRunIds} que ya lo tiene. */
  Map<UUID, RiskView> ofStages(Collection<UUID> stageRunIds) {
    return of("stage_run", stageRunIds);
  }

  /** El nivel de cada ejecución de {@code runIds} que ya lo tiene. */
  Map<UUID, RiskView> ofRuns(Collection<UUID> runIds) {
    return of("workflow_run", runIds);
  }

  private Map<UUID, RiskView> of(String table, Collection<UUID> ids) {
    Map<UUID, RiskView> views = new HashMap<>();
    if (ids.isEmpty()) {
      return views;
    }
    jdbc.sql(
            "SELECT id, risk, risk_detail FROM "
                + table
                + " WHERE id IN (:ids) AND risk IS NOT NULL")
        .param("ids", ids)
        .query(
            (rs, row) -> {
              String detail = rs.getString("risk_detail");
              views.put(
                  rs.getObject("id", UUID.class),
                  RiskView.of(
                      rs.getInt("risk"),
                      detail == null
                          ? null
                          : json.readValue(detail, new TypeReference<Map<String, Object>>() {})));
              return null;
            })
        .list();
    return views;
  }
}
