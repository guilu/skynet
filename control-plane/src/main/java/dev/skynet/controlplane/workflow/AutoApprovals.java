package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.definition.StageDefinition;
import dev.skynet.controlplane.project.CodeRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Cuándo se aprueba sola una puerta de fusión (AU-C). Una puerta {@code human-approval} con {@code
 * autoApprove} que desbloquea una fase {@code github-merge} se aprueba sin preguntar si se cumple
 * todo esto:
 *
 * <ul>
 *   <li>el repositorio tiene la autonomía encendida (viene apagada);
 *   <li>el nivel de riesgo de la ejecución (AU-A) es como mucho {@code maxRisk};
 *   <li>las líneas de código cambiadas son como mucho {@code maxLines};
 *   <li>la CI de la PR está en verde;
 *   <li>lo que se fusiona está juzgado (AU-B): el juez aprobó ese commit, el delta pasó o no hizo
 *       falta, o la fase pasó sin juez por su {@code minRisk} y el nivel no ha subido desde
 *       entonces;
 *   <li>no se ha llegado al tope de aprobaciones solas del repositorio en el día.
 * </ul>
 *
 * <p>Si falla una sola, la puerta pregunta como siempre y dice por qué no se aprobó sola.
 */
@Component
class AutoApprovals {

  /** Quien decide cuando la puerta se aprueba sola. */
  static final String ACTOR = "skynet";

  private final JdbcClient jdbc;
  private final ObjectMapper json;
  private final int maxPerDay;

  AutoApprovals(
      JdbcClient jdbc,
      ObjectMapper json,
      @Value("${skynet.autonomy.max-merges-per-day:10}") int maxPerDay) {
    this.jdbc = jdbc;
    this.json = json;
    this.maxPerDay = maxPerDay;
  }

  /** Una condición: si se cumple y cómo se lee. */
  record Condition(String id, boolean met, String text) {

    Map<String, Object> toMap() {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("id", id);
      map.put("met", met);
      map.put("text", text);
      return map;
    }
  }

  /** El resultado: si se aprueba sola, cada condición y una línea para la puerta. */
  record Check(boolean approved, List<Condition> conditions, String note) {

    Map<String, Object> toMap() {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("approved", approved);
      map.put("note", note);
      map.put("conditions", conditions.stream().map(Condition::toMap).toList());
      return map;
    }

    /** El comentario de la decisión: lo que se cumplió, una línea por condición. */
    String comment() {
      StringBuilder text = new StringBuilder("Se aprueba sola (AU-C):");
      conditions.forEach(c -> text.append("\n- ").append(c.text()));
      return text.toString();
    }
  }

  /**
   * Mira si la puerta {@code gate} de la ejecución {@code run} se aprueba sola. {@code pullRequest}
   * es la PR de su evidencia, o {@code null} si no desbloquea ninguna fusión. Vacío si la puerta no
   * tiene {@code autoApprove}.
   */
  Check check(
      WorkflowRun run,
      StageDefinition gate,
      CodeRepository repository,
      Map<String, Object> pullRequest,
      Instant now) {
    StageDefinition.AutoApprove auto = gate.approval().autoApprove();
    List<Condition> conditions = new ArrayList<>();
    conditions.add(
        new Condition(
            "autonomy",
            repository.isAutoMerge(),
            repository.isAutoMerge()
                ? "La autonomía del repositorio está encendida"
                : "La autonomía del repositorio está apagada"));
    Risk risk = risk(run.getId());
    if (risk == null) {
      conditions.add(new Condition("risk", false, "El nivel de riesgo no se ha medido"));
    } else {
      conditions.add(
          new Condition(
              "risk",
              risk.tier() <= auto.maxRisk(),
              risk.tier() <= auto.maxRisk()
                  ? "Nivel " + risk.tier() + " (como mucho " + auto.maxRisk() + ")"
                  : risk.summary() == null ? "Nivel " + risk.tier() : risk.summary()));
    }
    Integer lines = codeLines(run.getId());
    if (lines == null) {
      conditions.add(new Condition("lines", false, "Las líneas cambiadas no se han medido"));
    } else {
      conditions.add(
          new Condition(
              "lines",
              lines <= auto.maxLines(),
              lines
                  + " líneas de código"
                  + (lines <= auto.maxLines()
                      ? " (como mucho " + auto.maxLines() + ")"
                      : ", más de " + auto.maxLines())));
    }
    if (pullRequest == null) {
      conditions.add(new Condition("ci", false, "La puerta no fusiona ninguna PR"));
    } else {
      Object ci = pullRequest.get("ci");
      String status = ci instanceof Map<?, ?> c ? String.valueOf(c.get("status")) : null;
      conditions.add(
          new Condition(
              "ci",
              "PASSED".equals(status),
              status == null
                  ? "La PR no tiene CI que esperar"
                  : "PASSED".equals(status)
                      ? "La CI de la PR está en verde"
                      : "La CI de la PR no está en verde"));
    }
    conditions.add(judged(run.getId(), pullRequest, risk));
    int today = approvedToday(repository.getId(), now);
    conditions.add(
        new Condition(
            "limit",
            today < maxPerDay,
            today < maxPerDay
                ? (today + 1)
                    + "ª fusión sola de hoy en el repositorio (como mucho "
                    + maxPerDay
                    + ")"
                : "Ya van "
                    + today
                    + " fusiones solas hoy en el repositorio (como mucho "
                    + maxPerDay
                    + ")"));
    boolean approved = conditions.stream().allMatch(Condition::met);
    String note =
        approved
            ? "Se aprueba sola: "
                + (risk == null ? "" : "nivel " + risk.tier() + ", ")
                + lines
                + " líneas, CI en verde y juzgado"
            : "No se aprueba sola: "
                + conditions.stream()
                    .filter(c -> !c.met())
                    .map(Condition::text)
                    .map(AutoApprovals::lower)
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("");
    return new Check(approved, conditions, note);
  }

  private static String lower(String text) {
    return text.isEmpty() ? text : Character.toLowerCase(text.charAt(0)) + text.substring(1);
  }

  /**
   * Si lo que se fusiona está juzgado (AU-B), o el juez se omitió por un nivel que no ha subido.
   */
  @SuppressWarnings("unchecked")
  private Condition judged(UUID runId, Map<String, Object> pullRequest, Risk risk) {
    Object judged = pullRequest == null ? null : pullRequest.get("judged");
    if (judged instanceof Map<?, ?> map) {
      Object status = ((Map<String, Object>) map).get("status");
      boolean ok =
          DeltaJudges.UNCHANGED.equals(status)
              || DeltaJudges.SKIPPED.equals(status)
              || DeltaJudges.PASSED.equals(status);
      String text = String.valueOf(((Map<String, Object>) map).get("text"));
      return new Condition("judged", ok, ok ? text : "Lo que se fusiona no está juzgado: " + text);
    }
    Integer minRisk =
        jdbc.sql(
                "SELECT min(min_risk) FROM stage_judge WHERE workflow_run_id = ? AND status ="
                    + " 'SKIPPED'")
            .param(runId)
            .query(Integer.class)
            .optional()
            .orElse(null);
    if (minRisk == null) {
      return new Condition("judged", false, "Ningún juez ha aprobado lo que se fusiona");
    }
    if (risk != null && risk.tier() < minRisk) {
      return new Condition(
          "judged",
          true,
          "Nivel " + risk.tier() + ": sin juez (juzga desde el nivel " + minRisk + ")");
    }
    return new Condition(
        "judged",
        false,
        "El juez se omitió por el nivel, pero el cambio ha subido"
            + (risk == null ? "" : " al nivel " + risk.tier()));
  }

  private record Risk(int tier, String summary) {}

  private Risk risk(UUID runId) {
    return jdbc.sql(
            "SELECT risk, risk_detail ->> 'summary' AS summary FROM workflow_run WHERE id = ? AND"
                + " risk IS NOT NULL")
        .param(runId)
        .query((rs, n) -> new Risk(rs.getInt("risk"), rs.getString("summary")))
        .optional()
        .orElse(null);
  }

  /**
   * Las líneas de código que cambió la ejecución, sin tests ni documentación, según lo que midió el
   * runner al terminar cada agente (AU-A): en cada worktree, la última medida desde su commit de
   * partida más las de las correcciones medidas desde lo juzgado (AU-B). La mayor de sus worktrees;
   * {@code null} si no hay ninguna medida.
   */
  Integer codeLines(UUID runId) {
    List<Measure> measures =
        jdbc.sql(
                """
                SELECT a.workspace_id, w.base_commit, CAST(x.metadata -> 'risk' AS text) AS risk
                FROM artifact x
                JOIN agent_run a ON a.id = x.agent_run_id
                JOIN stage_run s ON s.id = a.stage_run_id
                LEFT JOIN workspace w ON w.id = a.workspace_id
                WHERE s.workflow_run_id = ? AND x.type = 'GIT_CHANGES'
                  AND x.verification_run_id IS NULL AND x.metadata -> 'risk' IS NOT NULL
                ORDER BY x.created_at
                """)
            .param(runId)
            .query(
                (rs, n) ->
                    new Measure(
                        rs.getObject("workspace_id", UUID.class),
                        rs.getString("base_commit"),
                        rs.getString("risk")))
            .list();
    if (measures.isEmpty()) {
      return null;
    }
    Map<UUID, Integer> cumulative = new HashMap<>();
    Map<UUID, Integer> deltas = new HashMap<>();
    for (Measure m : measures) {
      Map<String, Object> risk =
          json.readValue(m.risk(), new TypeReference<LinkedHashMap<String, Object>>() {});
      int lines = risk.get("lines") instanceof Number number ? number.intValue() : 0;
      Object base = risk.get("base");
      if (base == null || m.baseCommit() == null || base.equals(m.baseCommit())) {
        cumulative.put(m.workspaceId(), lines);
        deltas.remove(m.workspaceId());
      } else {
        deltas.merge(m.workspaceId(), lines, Integer::sum);
      }
    }
    int max = 0;
    for (UUID workspace : cumulative.keySet()) {
      max = Math.max(max, cumulative.get(workspace) + deltas.getOrDefault(workspace, 0));
    }
    for (Map.Entry<UUID, Integer> delta : deltas.entrySet()) {
      if (!cumulative.containsKey(delta.getKey())) {
        max = Math.max(max, delta.getValue());
      }
    }
    return max;
  }

  private record Measure(UUID workspaceId, String baseCommit, String risk) {}

  /** Cuántas puertas ha aprobado Skynet solo hoy (UTC) en el repositorio. */
  private int approvedToday(UUID repositoryId, Instant now) {
    Instant day = now.atOffset(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS).toInstant();
    return jdbc.sql(
            """
            SELECT count(*) FROM approval_decision d
            JOIN approval_request r ON r.id = d.approval_request_id
            JOIN workflow_run w ON w.id = r.workflow_run_id
            WHERE d.actor = ? AND d.action = 'APPROVE' AND d.created_at >= ?
              AND w.repository_id = ?
            """)
        .params(ACTOR, Timestamp.from(day), repositoryId)
        .query(Integer.class)
        .single();
  }
}
