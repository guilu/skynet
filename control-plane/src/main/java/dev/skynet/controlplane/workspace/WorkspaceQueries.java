package dev.skynet.controlplane.workspace;

import dev.skynet.controlplane.project.ProjectService;
import dev.skynet.controlplane.shared.TimeSource;
import dev.skynet.controlplane.workspace.ProjectOverview.RepositoryOverview;
import dev.skynet.controlplane.workspace.WorkItemSummary.PullRequestRef;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consultas de la fábrica (FS-B). Dejan fuera lo archivado: proyectos, repositorios, trabajos y
 * ejecuciones. El coste de un trabajo suma todos sus intentos; el de un repositorio o un proyecto,
 * las ejecuciones creadas en el periodo.
 */
@Service
public class WorkspaceQueries {

  /** Periodo del coste de proyectos y repositorios. */
  static final Duration PERIOD = Duration.ofDays(30);

  /** Máximo de cambios por lista. */
  static final int CHANGES_LIMIT = 50;

  private static final String ITEMS =
      "WITH items AS (SELECT w.id, w.project_id, w.key, w.title, w.type, w.status,"
          + " w.repository_id, w.created_at, p.key AS project_key, p.name AS project_name"
          + " FROM work_item w JOIN project p ON p.id = w.project_id"
          + " WHERE w.archived_at IS NULL AND p.archived_at IS NULL %s),"
          + " runs AS (SELECT r.id, r.work_item_id, r.status, r.created_at, r.definition_id"
          + " FROM workflow_run r JOIN items i ON i.id = r.work_item_id"
          + " WHERE r.archived_at IS NULL AND r.purpose IS NULL)"
          + " SELECT i.*, repo.name AS repository_name,"
          + " (SELECT count(*) FROM runs WHERE runs.work_item_id = i.id) AS attempts,"
          + " (SELECT sum(a.cost_usd) FROM agent_run a JOIN stage_run s ON s.id = a.stage_run_id"
          + " JOIN runs ON runs.id = s.workflow_run_id WHERE runs.work_item_id = i.id) AS cost_usd,"
          + " last.id AS last_run_id, last.status AS last_run_status,"
          + " last.created_at AS last_run_at, d.key AS workflow_key,"
          + " EXISTS (SELECT 1 FROM approval_request ap JOIN runs ON runs.id = ap.workflow_run_id"
          + " WHERE runs.work_item_id = i.id AND ap.status = 'PENDING') AS waiting,"
          + " EXISTS (SELECT 1 FROM runs WHERE runs.work_item_id = i.id"
          + " AND runs.status IN ('PENDING', 'RUNNING')) AS running,"
          + " pr.number AS pr_number, pr.url AS pr_url, pr.status AS pr_status"
          + " FROM items i LEFT JOIN repository repo ON repo.id = i.repository_id"
          + " LEFT JOIN LATERAL (SELECT * FROM runs WHERE runs.work_item_id = i.id"
          + " ORDER BY runs.created_at DESC LIMIT 1) last ON true"
          + " LEFT JOIN workflow_definition d ON d.id = last.definition_id"
          + " LEFT JOIN LATERAL (SELECT g.number, g.url, g.status FROM github_pull_request g"
          + " JOIN runs ON runs.id = g.workflow_run_id WHERE runs.work_item_id = i.id"
          + " AND g.status IN ('OPEN', 'MERGED')"
          + " ORDER BY (g.status = 'OPEN') DESC, g.updated_at DESC LIMIT 1) pr ON true"
          + " ORDER BY coalesce(last.created_at, i.created_at) DESC, i.key";

  private static final String CHANGES =
      "SELECT g.id, g.number, g.url, g.title, g.status, g.branch, g.created_at, g.updated_at,"
          + " g.workflow_run_id, repo.id AS repository_id, repo.name AS repository_name,"
          + " w.id AS work_item_id, w.key AS work_item_key, w.title AS work_item_title,"
          + " p.id AS project_id, p.key AS project_key, p.name AS project_name"
          + " FROM github_pull_request g JOIN workflow_run x ON x.id = g.workflow_run_id"
          + " JOIN work_item w ON w.id = x.work_item_id JOIN project p ON p.id = w.project_id"
          + " JOIN repository repo ON repo.id = g.repository_id"
          + " WHERE g.status IN ('OPEN', 'MERGED', 'CLOSED') AND x.archived_at IS NULL"
          + " AND w.archived_at IS NULL AND p.archived_at IS NULL %s"
          + " ORDER BY g.updated_at DESC LIMIT :limit";

  private final JdbcClient jdbc;
  private final ProjectService projects;
  private final TimeSource time;

  WorkspaceQueries(JdbcClient jdbc, ProjectService projects, TimeSource time) {
    this.jdbc = jdbc;
    this.projects = projects;
    this.time = time;
  }

  /** Centro de operaciones de un proyecto: cifras, repositorios y trabajos. */
  @Transactional(readOnly = true)
  public ProjectOverview overview(UUID projectId) {
    projects.get(projectId);
    Instant since = time.now().minus(PERIOD);
    Timestamp from = Timestamp.from(since);
    List<WorkItemSummary> items =
        jdbc.sql(ITEMS.formatted("AND w.project_id = :project"))
            .param("project", projectId)
            .query(WorkspaceQueries::summary)
            .list();
    List<RepositoryOverview> repositories =
        jdbc.sql(
                "SELECT r.id, r.name, r.default_branch,"
                    + " (SELECT count(*) FROM work_item w WHERE w.repository_id = r.id"
                    + " AND w.status = 'OPEN' AND w.archived_at IS NULL) AS work_items,"
                    + " (SELECT count(*) FROM workflow_run x JOIN work_item w"
                    + " ON w.id = x.work_item_id WHERE x.repository_id = r.id"
                    + " AND x.status IN ('PENDING', 'RUNNING') AND x.archived_at IS NULL"
                    + " AND x.purpose IS NULL"
                    + " AND w.archived_at IS NULL) AS active_runs,"
                    + " (SELECT count(*) FROM github_pull_request g WHERE g.repository_id = r.id"
                    + " AND g.status = 'OPEN') AS open_prs,"
                    + " (SELECT sum(a.cost_usd) FROM agent_run a JOIN stage_run s"
                    + " ON s.id = a.stage_run_id JOIN workflow_run x ON x.id = s.workflow_run_id"
                    + " WHERE x.repository_id = r.id AND x.created_at >= :since"
                    + " AND x.archived_at IS NULL) AS cost_usd"
                    + " FROM repository r WHERE r.project_id = :project AND r.archived_at IS NULL"
                    + " ORDER BY r.name")
            .param("project", projectId)
            .param("since", from)
            .query(
                (rs, n) ->
                    new RepositoryOverview(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("default_branch"),
                        rs.getLong("work_items"),
                        rs.getLong("active_runs"),
                        rs.getLong("open_prs"),
                        rs.getBigDecimal("cost_usd")))
            .list();
    // Cifras del proyecto sobre sus ejecuciones no archivadas.
    String runs =
        "FROM workflow_run x JOIN work_item w ON w.id = x.work_item_id"
            + " WHERE w.project_id = :project AND x.archived_at IS NULL AND w.archived_at IS NULL"
            + " AND x.purpose IS NULL";
    Map<String, Object> totals =
        jdbc.sql(
                "SELECT (SELECT count(*) "
                    + runs
                    + " AND x.status IN ('PENDING', 'RUNNING')) AS active_runs,"
                    + " (SELECT count(*) FROM github_pull_request g"
                    + " WHERE g.status = 'OPEN' AND g.workflow_run_id IN (SELECT x.id "
                    + runs
                    + ")) AS open_prs,"
                    + " (SELECT count(*) FROM github_pull_request g"
                    + " WHERE g.status = 'MERGED' AND g.workflow_run_id IN (SELECT x.id "
                    + runs
                    + ")) AS merged_prs,"
                    + " (SELECT count(*) FROM approval_request ap"
                    + " WHERE ap.status = 'PENDING' AND ap.workflow_run_id IN (SELECT x.id "
                    + runs
                    + ")) AS approvals,"
                    + " (SELECT sum(a.cost_usd) FROM agent_run a JOIN stage_run s"
                    + " ON s.id = a.stage_run_id WHERE s.workflow_run_id IN (SELECT x.id "
                    + runs
                    + " AND x.created_at >= :since)) AS cost_usd")
            .param("project", projectId)
            .param("since", from)
            .query()
            .singleRow();
    long open = items.stream().filter(i -> "OPEN".equals(i.status())).count();
    return new ProjectOverview(
        projectId,
        since,
        open,
        ((Number) totals.get("active_runs")).longValue(),
        ((Number) totals.get("open_prs")).longValue(),
        ((Number) totals.get("merged_prs")).longValue(),
        ((Number) totals.get("approvals")).longValue(),
        (BigDecimal) totals.get("cost_usd"),
        repositories,
        items);
  }

  /**
   * Trabajos de todos los proyectos (o de uno) en esas fases; sin fases, todos los que no están
   * terminados.
   */
  @Transactional(readOnly = true)
  public List<WorkItemSummary> workItems(Set<WorkPhase> phases, UUID projectId) {
    Set<WorkPhase> wanted =
        phases.isEmpty()
            ? Set.of(
                WorkPhase.TODO,
                WorkPhase.RUNNING,
                WorkPhase.WAITING,
                WorkPhase.STUCK,
                WorkPhase.REVIEW)
            : phases;
    List<WorkItemSummary> all =
        projectId == null
            ? jdbc.sql(ITEMS.formatted("")).query(WorkspaceQueries::summary).list()
            : jdbc.sql(ITEMS.formatted("AND w.project_id = :project"))
                .param("project", projectId)
                .query(WorkspaceQueries::summary)
                .list();
    return all.stream().filter(i -> wanted.contains(i.phase())).toList();
  }

  /** Fase de cada trabajo; los archivados o inexistentes no salen. */
  @Transactional(readOnly = true)
  public Map<UUID, WorkPhase> phases(Collection<UUID> workItemIds) {
    if (workItemIds.isEmpty()) {
      return Map.of();
    }
    return jdbc
        .sql(ITEMS.formatted("AND w.id IN (:ids)"))
        .param("ids", workItemIds)
        .query(WorkspaceQueries::summary)
        .list()
        .stream()
        .collect(Collectors.toMap(WorkItemSummary::id, WorkItemSummary::phase));
  }

  /** Un trabajo con su fase, sus intentos y su coste; vacío si está archivado o no existe. */
  @Transactional(readOnly = true)
  public java.util.Optional<WorkItemSummary> workItem(UUID workItemId) {
    return jdbc.sql(ITEMS.formatted("AND w.id = :id"))
        .param("id", workItemId)
        .query(WorkspaceQueries::summary)
        .optional();
  }

  /** PRs del proyecto, las últimas primero. */
  @Transactional(readOnly = true)
  public List<ChangeView> changes(UUID projectId) {
    projects.get(projectId);
    return jdbc.sql(CHANGES.formatted("AND p.id = :project"))
        .param("project", projectId)
        .param("limit", CHANGES_LIMIT)
        .query(WorkspaceQueries::change)
        .list();
  }

  /** PRs de toda la fábrica, las últimas primero. */
  @Transactional(readOnly = true)
  public List<ChangeView> results() {
    return jdbc.sql(CHANGES.formatted(""))
        .param("limit", CHANGES_LIMIT)
        .query(WorkspaceQueries::change)
        .list();
  }

  private static WorkItemSummary summary(ResultSet rs, int n) throws SQLException {
    String prStatus = rs.getString("pr_status");
    String lastRun = rs.getString("last_run_status");
    String status = rs.getString("status");
    Instant created = rs.getTimestamp("created_at").toInstant();
    Timestamp lastAt = rs.getTimestamp("last_run_at");
    return new WorkItemSummary(
        rs.getObject("id", UUID.class),
        rs.getObject("project_id", UUID.class),
        rs.getString("project_key"),
        rs.getString("project_name"),
        rs.getString("key"),
        rs.getString("title"),
        rs.getString("type"),
        status,
        rs.getObject("repository_id", UUID.class),
        rs.getString("repository_name"),
        WorkPhase.of(
            "CLOSED".equals(status),
            rs.getBoolean("waiting"),
            rs.getBoolean("running"),
            prStatus,
            lastRun),
        rs.getLong("attempts"),
        rs.getBigDecimal("cost_usd"),
        rs.getObject("last_run_id", UUID.class),
        lastRun,
        rs.getString("workflow_key"),
        prStatus == null
            ? null
            : new PullRequestRef(
                rs.getObject("pr_number", Integer.class), rs.getString("pr_url"), prStatus),
        created,
        lastAt == null ? created : lastAt.toInstant());
  }

  private static ChangeView change(ResultSet rs, int n) throws SQLException {
    Function<String, Instant> at =
        column -> {
          try {
            Timestamp t = rs.getTimestamp(column);
            return t == null ? null : t.toInstant();
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        };
    return new ChangeView(
        rs.getObject("id", UUID.class),
        rs.getObject("number", Integer.class),
        rs.getString("url"),
        rs.getString("title"),
        rs.getString("status"),
        rs.getString("branch"),
        rs.getObject("repository_id", UUID.class),
        rs.getString("repository_name"),
        rs.getObject("project_id", UUID.class),
        rs.getString("project_key"),
        rs.getString("project_name"),
        rs.getObject("work_item_id", UUID.class),
        rs.getString("work_item_key"),
        rs.getString("work_item_title"),
        rs.getObject("workflow_run_id", UUID.class),
        at.apply("created_at"),
        at.apply("updated_at"));
  }
}
