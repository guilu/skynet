package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.memory.MemoryContent;
import dev.skynet.controlplane.memory.MemoryKind;
import dev.skynet.controlplane.memory.MemoryService;
import dev.skynet.controlplane.memory.MemorySource;
import dev.skynet.controlplane.project.ProjectService;
import dev.skynet.controlplane.shared.ConflictException;
import dev.skynet.controlplane.shared.InvalidRequestException;
import dev.skynet.controlplane.shared.NotFoundException;
import dev.skynet.controlplane.shared.TimeSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Las lecciones (AP-B): lo que se repite en los hallazgos de un repositorio, propuesto como regla.
 *
 * <p>Cuenta por repositorio y clase de hallazgo. Si los jueces han tenido que pedir lo mismo, y se
 * ha corregido, en {@link #THRESHOLD} ejecuciones distintas, propone {@link #PREVENT}: una
 * restricción fijada en la memoria del repositorio. Si lo que señalan se ha dado por bueno
 * (aceptado, irrelevante o refutado) en otras tantas, propone {@link #DONT_FLAG}: un criterio para
 * el juez. Hay una sola lección por repositorio, clase y tipo; una descartada vuelve a proponerse
 * con {@link #AFTER_DISMISSAL} ejecuciones nuevas. No aplica nada sin una persona.
 */
@Component
class Lessons {

  static final String PREVENT = "prevenir";
  static final String DONT_FLAG = "no-senalar";

  static final String PROPOSED = "propuesta";
  static final String APPLIED = "aplicada";
  static final String DISMISSED = "descartada";

  /** Ejecuciones distintas con la misma clase para proponer una lección. */
  static final int THRESHOLD = 2;

  /** Ejecuciones nuevas, después de descartarla, para volver a proponerla. */
  static final int AFTER_DISMISSAL = 3;

  /** Hallazgos de ejemplo en el texto propuesto. */
  private static final int EXAMPLES = 3;

  private static final Set<String> STATUSES = Set.of(PROPOSED, APPLIED, DISMISSED);

  /** Los desenlaces que cuentan para cada tipo de lección. */
  private static final Map<String, Set<String>> OUTCOMES =
      Map.of(
          PREVENT,
          Set.of(Findings.FIXED),
          DONT_FLAG,
          Set.of(Findings.ACCEPTED, Findings.IRRELEVANT, Findings.REFUTED));

  private final JdbcClient jdbc;
  private final RunTransitions transitions;
  private final TimeSource time;
  private final MemoryService memory;
  private final ProjectService projects;

  Lessons(
      JdbcClient jdbc,
      RunTransitions transitions,
      TimeSource time,
      MemoryService memory,
      ProjectService projects) {
    this.jdbc = jdbc;
    this.transitions = transitions;
    this.time = time;
    this.memory = memory;
    this.projects = projects;
  }

  // ---- Proponer ---------------------------------------------------------------------------------

  /**
   * Mira si los hallazgos del repositorio ya justifican una lección, después de que cambie el
   * desenlace de alguno en la ejecución {@code runId}. Propone las nuevas, suma pruebas a las que
   * hay y vuelve a proponer una descartada si hay bastantes casos nuevos.
   */
  void consider(UUID repositoryId, UUID runId, Instant now) {
    if (repositoryId == null) {
      return;
    }
    List<Group> groups =
        jdbc.sql(
                """
                SELECT category,
                  CASE WHEN outcome = :fixed THEN :prevent ELSE :dontFlag END AS kind,
                  count(DISTINCT workflow_run_id) AS runs
                FROM finding
                WHERE repository_id = :repository AND category <> :other
                  AND outcome IN (:outcomes)
                GROUP BY 1, 2
                HAVING count(DISTINCT workflow_run_id) >= :threshold
                ORDER BY 1, 2
                """)
            .param("fixed", Findings.FIXED)
            .param("prevent", PREVENT)
            .param("dontFlag", DONT_FLAG)
            .param("repository", repositoryId)
            .param("other", JudgeVerdicts.OTHER)
            .param("outcomes", allOutcomes())
            .param("threshold", THRESHOLD)
            .query((rs, n) -> new Group(rs.getString(1), rs.getString(2), rs.getInt(3)))
            .list();
    for (Group group : groups) {
      consider(repositoryId, runId, group, now);
    }
  }

  private void consider(UUID repositoryId, UUID runId, Group group, Instant now) {
    String text = suggestedText(repositoryId, group);
    UUID id = UUID.randomUUID();
    int inserted =
        jdbc.sql(
                """
                INSERT INTO lesson (id, project_id, repository_id, category, kind, status,
                  suggested_text, runs, created_at, updated_at)
                SELECT :id, project_id, id, :category, :kind, :status, :text, :runs, :now, :now
                FROM repository WHERE id = :repository
                ON CONFLICT (repository_id, category, kind) DO NOTHING
                """)
            .param("id", id)
            .param("category", group.category())
            .param("kind", group.kind())
            .param("status", PROPOSED)
            .param("text", text)
            .param("runs", group.runs())
            .param("now", Timestamp.from(now))
            .param("repository", repositoryId)
            .update();
    if (inserted == 1) {
      link(id, repositoryId, group);
      proposed(id, runId, false, now);
      return;
    }
    Current current =
        jdbc.sql(
                "SELECT id, status, decided_at FROM lesson WHERE repository_id = ? AND category = ?"
                    + " AND kind = ? FOR UPDATE")
            .params(repositoryId, group.category(), group.kind())
            .query(
                (rs, n) ->
                    new Current(
                        rs.getObject("id", UUID.class),
                        rs.getString("status"),
                        rs.getTimestamp("decided_at")))
            .single();
    link(current.id(), repositoryId, group);
    switch (current.status()) {
      case PROPOSED ->
          jdbc.sql(
                  "UPDATE lesson SET runs = :runs, suggested_text = :text, updated_at = :now"
                      + " WHERE id = :id AND (runs <> :runs OR suggested_text <> :text)")
              .param("runs", group.runs())
              .param("text", text)
              .param("now", Timestamp.from(now))
              .param("id", current.id())
              .update();
      case APPLIED ->
          jdbc.sql("UPDATE lesson SET runs = :runs WHERE id = :id AND runs <> :runs")
              .param("runs", group.runs())
              .param("id", current.id())
              .update();
      default -> {
        int fresh = runsSince(repositoryId, group, current.decidedAt());
        if (fresh >= AFTER_DISMISSAL) {
          jdbc.sql(
                  """
                  UPDATE lesson SET status = :status, suggested_text = :text, applied_text = NULL,
                    runs = :runs, decided_by = NULL, decided_at = NULL, reason = NULL,
                    updated_at = :now
                  WHERE id = :id
                  """)
              .param("status", PROPOSED)
              .param("text", text)
              .param("runs", group.runs())
              .param("now", Timestamp.from(now))
              .param("id", current.id())
              .update();
          proposed(current.id(), runId, true, now);
        }
      }
    }
  }

  /** Ejecuciones distintas con casos de la lección después de {@code since}. */
  private int runsSince(UUID repositoryId, Group group, Timestamp since) {
    return jdbc.sql(
            """
            SELECT count(DISTINCT workflow_run_id) FROM finding
            WHERE repository_id = :repository AND category = :category AND outcome IN (:outcomes)
              AND coalesce(outcome_at, updated_at) > :since
            """)
        .param("repository", repositoryId)
        .param("category", group.category())
        .param("outcomes", OUTCOMES.get(group.kind()))
        .param("since", since == null ? Timestamp.from(Instant.EPOCH) : since)
        .query(Integer.class)
        .single();
  }

  /** Une a la lección los hallazgos que la justifican; los que ya estaban no cambian. */
  private void link(UUID lessonId, UUID repositoryId, Group group) {
    jdbc.sql(
            """
            INSERT INTO lesson_finding (lesson_id, finding_id)
            SELECT :lesson, id FROM finding
            WHERE repository_id = :repository AND category = :category AND outcome IN (:outcomes)
            ON CONFLICT DO NOTHING
            """)
        .param("lesson", lessonId)
        .param("repository", repositoryId)
        .param("category", group.category())
        .param("outcomes", OUTCOMES.get(group.kind()))
        .update();
  }

  private void proposed(UUID id, UUID runId, boolean again, Instant now) {
    LessonView lesson = view(id);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("lessonId", id);
    payload.put("projectId", lesson.projectId());
    payload.put("repositoryId", lesson.repositoryId());
    payload.put("repository", lesson.repositoryName());
    payload.put("category", lesson.category());
    payload.put("kind", lesson.kind());
    payload.put("runs", lesson.runs());
    payload.put("again", again);
    transitions.append("lesson", id, "lesson.proposed", runId, payload, now);
  }

  /**
   * El texto que propone Skynet, de una plantilla y sin LLM: lo que se repite y, como ejemplo, los
   * textos de los últimos hallazgos.
   */
  private String suggestedText(UUID repositoryId, Group group) {
    List<String> examples =
        jdbc.sql(
                """
                SELECT file, text FROM finding
                WHERE repository_id = :repository AND category = :category AND outcome IN (:outcomes)
                ORDER BY coalesce(outcome_at, updated_at) DESC, id
                LIMIT 30
                """)
            .param("repository", repositoryId)
            .param("category", group.category())
            .param("outcomes", OUTCOMES.get(group.kind()))
            .query((rs, n) -> example(rs.getString("file"), rs.getString("text")))
            .list();
    return text(group.kind(), group.category(), group.runs(), examples);
  }

  static String text(String kind, String category, int runs, List<String> examples) {
    String what = "*" + category + "* (" + JudgeVerdicts.CATEGORIES.get(category) + ")";
    StringBuilder text = new StringBuilder();
    if (PREVENT.equals(kind)) {
      text.append("En este repositorio, los jueces han tenido que pedir en ")
          .append(runs)
          .append(" ejecuciones: ")
          .append(what)
          .append(
              ". Antes de dar el cambio por terminado, comprueba que no vuelve a pasar, como en:");
    } else {
      text.append("No señales como fallo lo que en este repositorio ya se dio por bueno en ")
          .append(runs)
          .append(" ejecuciones, de la clase ")
          .append(what)
          .append(". Por ejemplo:");
    }
    for (String example : new LinkedHashSet<>(examples).stream().limit(EXAMPLES).toList()) {
      text.append("\n- ").append(example);
    }
    return text.toString();
  }

  private static String example(String file, String text) {
    String clean = text == null ? "" : text.strip().replaceAll("\\s+", " ");
    if (clean.length() > 200) {
      clean = clean.substring(0, 199) + "…";
    }
    return file == null || file.isBlank() ? clean : "`" + file + "`: " + clean;
  }

  private static Set<String> allOutcomes() {
    Set<String> all = new LinkedHashSet<>();
    OUTCOMES.values().forEach(all::addAll);
    return all;
  }

  private record Group(String category, String kind, int runs) {}

  private record Current(UUID id, String status, Timestamp decidedAt) {}

  // ---- Lo que ve y decide la persona ------------------------------------------------------------

  /** Las lecciones del proyecto, de la más reciente a la más antigua; o solo las de un estado. */
  @Transactional(readOnly = true)
  List<LessonView> ofProject(UUID projectId, String status) {
    projects.get(projectId);
    String wanted = status(status);
    return jdbc.sql(
            SELECT
                + " WHERE l.project_id = :project AND (CAST(:status AS text) IS NULL OR l.status ="
                + " :status) ORDER BY l.updated_at DESC, l.id")
        .param("project", projectId)
        .param("status", wanted)
        .query(Lessons::view)
        .list();
  }

  /** Una lección con los hallazgos que la justifican. */
  @Transactional(readOnly = true)
  LessonDetail detail(UUID id) {
    LessonView lesson = view(id);
    List<LessonFinding> findings =
        jdbc.sql(
                """
                SELECT f.id, f.workflow_run_id, w.work_item_id, s.stage_key, f.round, f.judge,
                  f.file, f.line, f.severity, f.text, f.outcome, f.outcome_by, f.outcome_reason,
                  coalesce(f.outcome_at, f.updated_at) AS outcome_at
                FROM lesson_finding lf
                JOIN finding f ON f.id = lf.finding_id
                JOIN stage_run s ON s.id = f.stage_run_id
                JOIN workflow_run w ON w.id = f.workflow_run_id
                WHERE lf.lesson_id = ?
                ORDER BY coalesce(f.outcome_at, f.updated_at) DESC, f.id
                """)
            .param(id)
            .query(
                (rs, n) ->
                    new LessonFinding(
                        rs.getObject("id", UUID.class),
                        rs.getObject("workflow_run_id", UUID.class),
                        rs.getObject("work_item_id", UUID.class),
                        rs.getString("stage_key"),
                        rs.getInt("round"),
                        rs.getString("judge"),
                        rs.getString("file"),
                        (Integer) rs.getObject("line"),
                        rs.getString("severity"),
                        rs.getString("text"),
                        rs.getString("outcome"),
                        rs.getString("outcome_by"),
                        rs.getString("outcome_reason"),
                        rs.getTimestamp("outcome_at").toInstant()))
            .list();
    return new LessonDetail(lesson, findings);
  }

  /**
   * Aplica una lección propuesta con el texto que diga la persona (o el propuesto). {@link
   * #PREVENT} la fija como restricción en la memoria del repositorio, con el tema {@code
   * leccion/{clase}}: aplicarla otra vez es una versión nueva, no otra observación. {@link
   * #DONT_FLAG} la deja como criterio aprendido del repositorio.
   */
  @Transactional
  LessonView apply(UUID id, String text, String actor) {
    LessonView lesson = lock(id);
    requireProposed(lesson);
    String applied = text == null || text.isBlank() ? lesson.suggestedText() : text.strip();
    Instant now = time.now();
    UUID observation = null;
    if (PREVENT.equals(lesson.kind())) {
      MemoryContent content =
          new MemoryContent(
              MemoryKind.CONSTRAINT,
              "Lección: " + lesson.category(),
              "leccion/" + lesson.category(),
              applied,
              "Los jueces lo han pedido en "
                  + lesson.runs()
                  + " ejecuciones distintas de este repositorio (lección de Skynet)",
              null,
              null,
              List.of("leccion", lesson.category()));
      observation =
          memory
              .save(lesson.projectId(), lesson.repositoryId(), content, MemorySource.human(actor))
              .observation()
              .id();
      memory.pin(observation, true, actor);
    }
    jdbc.sql(
            """
            UPDATE lesson SET status = :status, applied_text = :text, observation_id = :observation,
              decided_by = :actor, decided_at = :now, reason = NULL, updated_at = :now
            WHERE id = :id
            """)
        .param("status", APPLIED)
        .param("text", applied)
        .param("observation", observation)
        .param("actor", actor)
        .param("now", Timestamp.from(now))
        .param("id", id)
        .update();
    Map<String, Object> payload = event(lesson, actor);
    payload.put("observationId", observation);
    payload.put("edited", !applied.equals(lesson.suggestedText()));
    transitions.append("lesson", id, "lesson.applied", null, payload, now);
    return view(id);
  }

  /**
   * Descarta una lección propuesta, o desactiva una aplicada (AP-C): no vuelve hasta que haya
   * {@link #AFTER_DISMISSAL} casos nuevos. Desactivar una {@link #PREVENT} archiva su restricción
   * de la memoria; una {@link #DONT_FLAG} deja de llegar al juez.
   */
  @Transactional
  LessonView dismiss(UUID id, String reason, String actor) {
    if (reason == null || reason.isBlank()) {
      throw new InvalidRequestException("Falta `reason`: por qué no vale esta lección");
    }
    LessonView lesson = lock(id);
    if (DISMISSED.equals(lesson.status())) {
      throw new ConflictException("La lección ya está descartada");
    }
    boolean applied = APPLIED.equals(lesson.status());
    if (applied && lesson.observationId() != null) {
      memory.archive(lesson.observationId(), actor);
    }
    Instant now = time.now();
    jdbc.sql(
            """
            UPDATE lesson SET status = :status, decided_by = :actor, decided_at = :now,
              reason = :reason, updated_at = :now
            WHERE id = :id
            """)
        .param("status", DISMISSED)
        .param("actor", actor)
        .param("now", Timestamp.from(now))
        .param("reason", reason.strip())
        .param("id", id)
        .update();
    Map<String, Object> payload = event(lesson, actor);
    payload.put("reason", reason.strip());
    transitions.append(
        "lesson", id, applied ? "lesson.disabled" : "lesson.dismissed", null, payload, now);
    return view(id);
  }

  /**
   * Los criterios aprendidos del repositorio (AP-C): el texto de sus lecciones {@link #DONT_FLAG}
   * aplicadas, de la más antigua a la más reciente.
   */
  List<String> learned(UUID repositoryId) {
    if (repositoryId == null) {
      return List.of();
    }
    return jdbc.sql(
            "SELECT applied_text FROM lesson WHERE repository_id = ? AND kind = ? AND status = ?"
                + " AND applied_text IS NOT NULL ORDER BY decided_at, id")
        .params(repositoryId, DONT_FLAG, APPLIED)
        .query(String.class)
        .list();
  }

  /** Las lecciones de todos los proyectos activos, para Fábrica → Lecciones (AP-C). */
  @Transactional(readOnly = true)
  List<LessonView> all(String status) {
    String wanted = status(status);
    return jdbc.sql(
            SELECT
                + " WHERE p.archived_at IS NULL AND r.archived_at IS NULL AND (CAST(:status AS"
                + " text) IS NULL OR l.status = :status) ORDER BY l.updated_at DESC, l.id")
        .param("status", wanted)
        .query(Lessons::view)
        .list();
  }

  private static String status(String status) {
    String wanted = status == null || status.isBlank() ? null : status.strip();
    if (wanted != null && !STATUSES.contains(wanted)) {
      throw new InvalidRequestException(
          "Estado desconocido: " + status + ". Admite: propuesta, aplicada o descartada");
    }
    return wanted;
  }

  private LessonView lock(UUID id) {
    jdbc.sql("SELECT id FROM lesson WHERE id = ? FOR UPDATE")
        .param(id)
        .query(UUID.class)
        .optional()
        .orElseThrow(() -> new NotFoundException("Lección", id));
    return view(id);
  }

  private static void requireProposed(LessonView lesson) {
    if (!PROPOSED.equals(lesson.status())) {
      throw new ConflictException(
          "La lección ya está "
              + lesson.status().toLowerCase(Locale.ROOT)
              + ": no se puede decidir");
    }
  }

  private static Map<String, Object> event(LessonView lesson, String actor) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("lessonId", lesson.id());
    payload.put("projectId", lesson.projectId());
    payload.put("repositoryId", lesson.repositoryId());
    payload.put("repository", lesson.repositoryName());
    payload.put("category", lesson.category());
    payload.put("kind", lesson.kind());
    payload.put("actor", actor);
    return payload;
  }

  private LessonView view(UUID id) {
    return jdbc.sql(SELECT + " WHERE l.id = ?")
        .param(id)
        .query(Lessons::view)
        .optional()
        .orElseThrow(() -> new NotFoundException("Lección", id));
  }

  private static final String SELECT =
      """
      SELECT l.id, l.project_id, p.name AS project_name, l.repository_id,
        r.name AS repository_name, l.category, l.kind,
        l.status, l.suggested_text, l.applied_text, l.runs,
        (SELECT count(*) FROM lesson_finding lf WHERE lf.lesson_id = l.id) AS findings,
        l.observation_id, l.decided_by, l.decided_at, l.reason, l.created_at, l.updated_at
      FROM lesson l
      JOIN repository r ON r.id = l.repository_id
      JOIN project p ON p.id = l.project_id
      """;

  /** Una lección, como la ve la web. */
  record LessonView(
      UUID id,
      UUID projectId,
      String projectName,
      UUID repositoryId,
      String repositoryName,
      String category,
      String kind,
      String status,
      String suggestedText,
      String appliedText,
      int runs,
      int findings,
      UUID observationId,
      String decidedBy,
      Instant decidedAt,
      String reason,
      Instant createdAt,
      Instant updatedAt) {}

  /** Un hallazgo que justifica una lección, con su ejecución. */
  record LessonFinding(
      UUID id,
      UUID runId,
      UUID workItemId,
      String stageKey,
      int round,
      String judge,
      String file,
      Integer line,
      String severity,
      String text,
      String outcome,
      String outcomeBy,
      String outcomeReason,
      Instant outcomeAt) {}

  record LessonDetail(LessonView lesson, List<LessonFinding> findings) {}

  private static LessonView view(ResultSet rs, int n) throws SQLException {
    Timestamp decidedAt = rs.getTimestamp("decided_at");
    return new LessonView(
        rs.getObject("id", UUID.class),
        rs.getObject("project_id", UUID.class),
        rs.getString("project_name"),
        rs.getObject("repository_id", UUID.class),
        rs.getString("repository_name"),
        rs.getString("category"),
        rs.getString("kind"),
        rs.getString("status"),
        rs.getString("suggested_text"),
        rs.getString("applied_text"),
        rs.getInt("runs"),
        rs.getInt("findings"),
        rs.getObject("observation_id", UUID.class),
        rs.getString("decided_by"),
        decidedAt == null ? null : decidedAt.toInstant(),
        rs.getString("reason"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }
}
