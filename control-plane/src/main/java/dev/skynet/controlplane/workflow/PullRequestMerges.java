package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.definition.MergeSpec;
import dev.skynet.controlplane.definition.StageDefinition;
import dev.skynet.controlplane.definition.WorkflowDefinitions;
import dev.skynet.controlplane.github.GitHubException;
import dev.skynet.controlplane.github.GitHubPullRequestClosed;
import dev.skynet.controlplane.github.GitHubRepositories;
import dev.skynet.controlplane.github.GitHubRepositories.Check;
import dev.skynet.controlplane.github.GitHubRepositories.Link;
import dev.skynet.controlplane.github.GitHubRepositories.PullRequestState;
import dev.skynet.controlplane.project.CodeRepository;
import dev.skynet.controlplane.shared.ConflictException;
import dev.skynet.controlplane.shared.TimeSource;
import dev.skynet.controlplane.workitem.WorkItemService;
import dev.skynet.protocol.StageStatus;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * La fusión de la PR de una fase {@code github-merge} (EN-C).
 *
 * <ol>
 *   <li>Al arrancar ({@link #start}), la fase espera a que se fusione la PR de la ejecución con el
 *       último commit que subió Skynet: el que pasó la CI y se aprobó.
 *   <li>{@link #mergePending} habla con GitHub fuera de cualquier transacción: comprueba que la PR
 *       sigue abierta en ese commit y con la CI en verde, la fusiona pasando ese {@code sha} (si
 *       alguien ha subido algo después, GitHub no la fusiona) y borra la rama.
 *   <li>Fusionada, la fase termina bien, el trabajo pasa a hecho y se pide eliminar el worktree.
 * </ol>
 *
 * <p>Si la PR se fusiona a mano en GitHub ({@code pull_request.closed}), la fase cuenta como hecha;
 * si se cierra sin fusionar, la ejecución se cancela con el motivo.
 */
@Service
public class PullRequestMerges {

  private static final Logger LOG = LoggerFactory.getLogger(PullRequestMerges.class);

  static final String PENDING = "PENDING";
  static final String MERGED = "MERGED";
  static final String FAILED = "FAILED";

  /** Cuánto se reserva una fusión mientras se habla con GitHub. */
  static final Duration LEASE = Duration.ofMinutes(2);

  /** Mientras la CI del commit sigue en marcha, cuándo se vuelve a mirar. */
  static final Duration CI_PENDING = Duration.ofSeconds(30);

  /** Intentos cuando GitHub no responde antes de rendirse. */
  static final int MAX_ATTEMPTS = 5;

  private final JdbcClient jdbc;
  private final GitHubRepositories github;
  private final StageRunRepository stageRuns;
  private final WorkflowRunRepository workflowRuns;
  private final WorkflowDefinitions definitions;
  private final RunTransitions transitions;
  private final ObjectProvider<RunService> runs;
  private final WorkspaceCleanup cleanup;
  private final WorkItemService workItems;
  private final ApplicationEventPublisher publisher;
  private final TransactionTemplate transactions;
  private final TimeSource time;

  PullRequestMerges(
      JdbcClient jdbc,
      GitHubRepositories github,
      StageRunRepository stageRuns,
      WorkflowRunRepository workflowRuns,
      WorkflowDefinitions definitions,
      RunTransitions transitions,
      ObjectProvider<RunService> runs,
      WorkspaceCleanup cleanup,
      WorkItemService workItems,
      ApplicationEventPublisher publisher,
      TransactionTemplate transactions,
      TimeSource time) {
    this.jdbc = jdbc;
    this.github = github;
    this.stageRuns = stageRuns;
    this.workflowRuns = workflowRuns;
    this.definitions = definitions;
    this.transitions = transitions;
    this.runs = runs;
    this.cleanup = cleanup;
    this.workItems = workItems;
    this.publisher = publisher;
    this.transactions = transactions;
    this.time = time;
  }

  private record PullRequestRow(
      UUID id, String status, String headSha, Integer number, String url, String branch) {}

  /**
   * Arranca la fase {@code github-merge}: si la PR ya está fusionada (a mano en GitHub), termina
   * bien en el acto; si no, queda pendiente de fusionar con el último commit que subió Skynet.
   *
   * @throws ConflictException si la ejecución no tiene una PR abierta
   */
  void start(
      WorkflowRun run, StageRun stage, CodeRepository repository, MergeSpec spec, Instant now) {
    PullRequestRow pr =
        pullRequest(run.getId(), repository.getId())
            .orElseThrow(
                () ->
                    new ConflictException(
                        "Esta ejecución no ha abierto ninguna PR: la fase `github-merge` va detrás"
                            + " de una `github-pr`"));
    if (PullRequests.CLOSED.equals(pr.status())) {
      throw new ConflictException("La PR #" + pr.number() + " se cerró en GitHub sin fusionarla");
    }
    if (!MERGED.equals(pr.status())
        && (!PullRequests.OPEN.equals(pr.status()) || pr.headSha() == null)) {
      throw new ConflictException(
          "La PR de esta ejecución no está abierta: no hay nada que fusionar");
    }
    stage = transitions.advanceStage(stage, StageStatus.STARTING, "merge-requested", now);
    stage = transitions.advanceStage(stage, StageStatus.RUNNING, "merge-requested", now);
    jdbc.sql(
            """
            INSERT INTO github_merge (stage_run_id, workflow_run_id, pull_request_id, method,
                delete_branch, expected_sha, status, next_attempt_at, created_at, updated_at)
            VALUES (:stage, :run, :pr, :method, :delete, :sha, 'PENDING', :now, :now, :now)
            """)
        .param("stage", stage.getId())
        .param("run", run.getId())
        .param("pr", pr.id())
        .param("method", spec.method())
        .param("delete", spec.deleteBranch())
        .param("sha", pr.headSha() == null ? "" : pr.headSha())
        .param("now", Timestamp.from(now))
        .update();
    if (MERGED.equals(pr.status())) {
      merged(stage.getId(), run.getId(), pr.id(), null, false, true, now);
    }
  }

  /** Una fusión pendiente, reservada para este nodo mientras habla con GitHub. */
  record Pending(
      UUID stageRunId,
      UUID runId,
      UUID pullRequestId,
      UUID repositoryId,
      long number,
      String branch,
      String method,
      boolean deleteBranch,
      String expectedSha,
      int attempts,
      StageStatus stageStatus) {}

  /** Fusiona las PR pendientes a las que les toca. */
  @Scheduled(fixedDelayString = "${skynet.github.pull-requests.poll-interval:2s}")
  public void mergePending() {
    List<Pending> pending = transactions.execute(status -> claim());
    if (pending == null) {
      return;
    }
    for (Pending merge : pending) {
      merge(merge);
    }
  }

  private List<Pending> claim() {
    Instant now = time.now();
    List<Pending> claimed =
        jdbc.sql(
                """
                SELECT m.*, p.repository_id, p.number, p.branch, s.status AS stage_status
                FROM github_merge m
                JOIN github_pull_request p ON p.id = m.pull_request_id
                JOIN stage_run s ON s.id = m.stage_run_id
                WHERE m.status = 'PENDING' AND m.next_attempt_at <= :now
                ORDER BY m.next_attempt_at LIMIT 10 FOR UPDATE OF m SKIP LOCKED
                """)
            .param("now", Timestamp.from(now))
            .query(this::pending)
            .list();
    List<Pending> pending = new ArrayList<>();
    for (Pending merge : claimed) {
      if (merge.stageStatus().isTerminal()) {
        // La fase ya terminó (se acabó su tiempo, se canceló la ejecución): ya no fusiona.
        jdbc.sql(
                "UPDATE github_merge SET status = 'FAILED', next_attempt_at = NULL,"
                    + " updated_at = ?, error = 'La fase terminó antes de fusionar la PR'"
                    + " WHERE stage_run_id = ?")
            .params(Timestamp.from(now), merge.stageRunId())
            .update();
        continue;
      }
      jdbc.sql("UPDATE github_merge SET next_attempt_at = ? WHERE stage_run_id = ?")
          .params(Timestamp.from(now.plus(LEASE)), merge.stageRunId())
          .update();
      pending.add(merge);
    }
    return pending;
  }

  private Pending pending(ResultSet rs, int n) throws SQLException {
    return new Pending(
        rs.getObject("stage_run_id", UUID.class),
        rs.getObject("workflow_run_id", UUID.class),
        rs.getObject("pull_request_id", UUID.class),
        rs.getObject("repository_id", UUID.class),
        rs.getLong("number"),
        rs.getString("branch"),
        rs.getString("method"),
        rs.getBoolean("delete_branch"),
        rs.getString("expected_sha"),
        rs.getInt("attempts"),
        StageStatus.valueOf(rs.getString("stage_status")));
  }

  /** Habla con GitHub fuera de la transacción y luego aplica el resultado. */
  void merge(Pending merge) {
    Link link = github.link(merge.repositoryId()).orElse(null);
    if (link == null) {
      fail(
          merge,
          "No se pudo fusionar la PR: el repositorio ya no está enlazado con GitHub o la App no"
              + " está instalada en él");
      return;
    }
    String mergedSha;
    try {
      PullRequestState state = github.pullRequestState(link, merge.number());
      if (state.merged()) {
        transactions.executeWithoutResult(
            status ->
                merged(
                    merge.stageRunId(),
                    merge.runId(),
                    merge.pullRequestId(),
                    state.mergeSha(),
                    false,
                    true,
                    time.now()));
        return;
      }
      if (!"open".equals(state.state())) {
        fail(merge, "La PR #" + merge.number() + " se cerró en GitHub sin fusionarla");
        return;
      }
      if (!merge.expectedSha().equals(state.headSha())) {
        fail(merge, changed(merge, state.headSha()));
        return;
      }
      List<Check> checks = github.checks(link, merge.expectedSha());
      List<String> failed =
          checks.stream().filter(c -> Check.FAILURE.equals(c.state())).map(Check::name).toList();
      if (!failed.isEmpty()) {
        fail(merge, "La CI ya no está en verde: falló en " + String.join(", ", failed));
        return;
      }
      if (checks.stream().anyMatch(c -> Check.PENDING.equals(c.state()))) {
        later(merge, CI_PENDING, "La CI del commit aún no ha terminado");
        return;
      }
      mergedSha = github.merge(link, merge.number(), merge.method(), merge.expectedSha());
    } catch (GitHubException e) {
      LOG.warn("No se pudo fusionar la PR #{}: {}", merge.number(), e.getMessage());
      if (e.status() == 409) {
        fail(merge, changed(merge, null));
      } else if (e.status() / 100 == 4 && e.status() != 429) {
        fail(merge, "GitHub no fusionó la PR: " + e.getMessage());
      } else if (merge.attempts() + 1 >= MAX_ATTEMPTS) {
        fail(merge, "No se pudo fusionar la PR: " + e.getMessage());
      } else {
        later(
            merge,
            Duration.ofSeconds(10L << merge.attempts()),
            "No se pudo fusionar la PR: " + e.getMessage());
      }
      return;
    }
    boolean deleted = false;
    if (merge.deleteBranch()) {
      try {
        github.deleteBranch(link, merge.branch());
        deleted = true;
      } catch (GitHubException e) {
        LOG.warn("No se pudo borrar la rama {}: {}", merge.branch(), e.getMessage());
      }
    }
    boolean branchDeleted = deleted;
    transactions.executeWithoutResult(
        status ->
            merged(
                merge.stageRunId(),
                merge.runId(),
                merge.pullRequestId(),
                mergedSha,
                branchDeleted,
                false,
                time.now()));
  }

  private static String changed(Pending merge, String headSha) {
    return "La PR cambió después de la aprobación: se aprobó el commit "
        + abbreviate(merge.expectedSha())
        + (headSha == null ? "" : " y ahora tiene el " + abbreviate(headSha))
        + ". No se fusiona: revísala en GitHub";
  }

  /**
   * La PR está fusionada (por Skynet o a mano): la fase termina bien, el trabajo pasa a hecho y se
   * pide eliminar los worktrees que se subieron.
   */
  private void merged(
      UUID stageRunId,
      UUID runId,
      UUID pullRequestId,
      String mergedSha,
      boolean branchDeleted,
      boolean external,
      Instant now) {
    lockRun(runId);
    int updated =
        jdbc.sql(
                "UPDATE github_merge SET status = 'MERGED', merged_sha = :sha,"
                    + " branch_deleted = :deleted, error = NULL, next_attempt_at = NULL,"
                    + " updated_at = :now WHERE stage_run_id = :stage AND status = 'PENDING'")
            .param("sha", mergedSha)
            .param("deleted", branchDeleted)
            .param("now", Timestamp.from(now))
            .param("stage", stageRunId)
            .update();
    if (updated == 0) {
      return;
    }
    markPullRequest(pullRequestId, MERGED, mergedSha, now);
    StageRun stage = stageRuns.findById(stageRunId).orElseThrow();
    Map<String, Object> pr =
        jdbc.sql("SELECT number, url, branch, head_sha FROM github_pull_request WHERE id = ?")
            .param(pullRequestId)
            .query()
            .singleRow();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("stageKey", stage.getStageKey());
    payload.put("number", pr.get("number"));
    payload.put("url", pr.get("url"));
    payload.put("headSha", pr.get("head_sha"));
    payload.put("mergedSha", mergedSha);
    payload.put("branch", pr.get("branch"));
    payload.put("branchDeleted", branchDeleted);
    payload.put("external", external);
    transitions.append("stage_run", stageRunId, "stage.github.pr.merged", runId, payload, now);
    WorkflowRun run = workflowRuns.findById(runId).orElseThrow();
    workItems.close(run.getWorkItemId(), runId, "pull-request-merged");
    cleanup.requestAll(pushedWorkspaces(pullRequestId));
    if (!stage.getStatus().isTerminal()) {
      transitions.advanceStage(stage, StageStatus.SUCCEEDED, "pull-request-merged", now);
      publisher.publishEvent(new WorkflowRunChanged(runId));
    }
  }

  private void fail(Pending merge, String error) {
    transactions.executeWithoutResult(
        status -> {
          Instant now = time.now();
          lockRun(merge.runId());
          int updated =
              jdbc.sql(
                      "UPDATE github_merge SET status = 'FAILED', error = :error,"
                          + " next_attempt_at = NULL, updated_at = :now"
                          + " WHERE stage_run_id = :stage AND status = 'PENDING'")
                  .param("error", error)
                  .param("now", Timestamp.from(now))
                  .param("stage", merge.stageRunId())
                  .update();
          if (updated == 0) {
            return;
          }
          StageRun stage = stageRuns.findById(merge.stageRunId()).orElseThrow();
          transitions.append(
              "stage_run",
              stage.getId(),
              "stage.github.pr.merge.failed",
              merge.runId(),
              Map.of("stageKey", stage.getStageKey(), "number", merge.number(), "error", error),
              now);
          if (!stage.getStatus().isTerminal()) {
            transitions.advanceStage(stage, StageStatus.FAILED, "merge-failed", now);
            publisher.publishEvent(new WorkflowRunChanged(merge.runId()));
          }
        });
  }

  private void later(Pending merge, Duration delay, String error) {
    transactions.executeWithoutResult(
        status ->
            jdbc.sql(
                    "UPDATE github_merge SET attempts = attempts + 1, error = :error,"
                        + " next_attempt_at = :next, updated_at = :now"
                        + " WHERE stage_run_id = :stage AND status = 'PENDING'")
                .param("error", error)
                .param("next", Timestamp.from(time.now().plus(delay)))
                .param("now", Timestamp.from(time.now()))
                .param("stage", merge.stageRunId())
                .update());
  }

  /**
   * Un webhook {@code pull_request.closed}: si es la PR de una ejecución, queda fusionada o
   * cerrada. Fusionada, su fase {@code github-merge} pendiente termina bien (y la que arranque
   * después, también). Cerrada sin fusionar, la ejecución se cancela con el motivo.
   */
  @EventListener
  void pullRequestClosed(GitHubPullRequestClosed event) {
    Instant now = time.now();
    List<UUID[]> open =
        jdbc.sql(
                """
                SELECT p.id, p.workflow_run_id FROM github_pull_request p
                JOIN workflow_run r ON r.id = p.workflow_run_id
                WHERE p.repository_id = :repository AND p.number = :number AND p.status = 'OPEN'
                """)
            .param("repository", event.repositoryId())
            .param("number", event.number())
            .query(
                (rs, n) ->
                    new UUID[] {
                      rs.getObject("id", UUID.class), rs.getObject("workflow_run_id", UUID.class)
                    })
            .list();
    for (UUID[] ids : open) {
      transactions.executeWithoutResult(status -> closed(event, ids[0], ids[1], now));
    }
  }

  private void closed(GitHubPullRequestClosed event, UUID pullRequestId, UUID runId, Instant now) {
    lockRun(runId);
    if (event.merged()) {
      Optional<UUID> pending =
          jdbc.sql(
                  "SELECT stage_run_id FROM github_merge WHERE pull_request_id = ?"
                      + " AND status = 'PENDING'")
              .param(pullRequestId)
              .query(UUID.class)
              .optional();
      if (pending.isPresent()) {
        merged(pending.get(), runId, pullRequestId, event.mergeSha(), false, true, now);
      } else {
        markPullRequest(pullRequestId, MERGED, event.mergeSha(), now);
      }
      return;
    }
    markPullRequest(pullRequestId, PullRequests.CLOSED, null, now);
    jdbc.sql(
            "UPDATE github_merge SET status = 'FAILED', error = :error, next_attempt_at = NULL,"
                + " updated_at = :now WHERE pull_request_id = :pr AND status = 'PENDING'")
        .param("error", "La PR #" + event.number() + " se cerró en GitHub sin fusionarla")
        .param("now", Timestamp.from(now))
        .param("pr", pullRequestId)
        .update();
    transitions.append(
        "workflow_run",
        runId,
        "workflow.github.pr.closed",
        runId,
        Map.of("number", event.number()),
        now);
    runs.getObject().cancelRun(runId, "pull-request-closed", now);
  }

  /** La fusión de cada fase {@code github-merge} de una ejecución, para la API. */
  public record View(
      UUID stageRunId,
      String method,
      String status,
      String expectedSha,
      String mergedSha,
      boolean branchDeleted,
      String error) {}

  public List<View> ofRun(UUID runId) {
    return jdbc.sql("SELECT * FROM github_merge WHERE workflow_run_id = ? ORDER BY created_at")
        .param(runId)
        .query(
            (rs, n) ->
                new View(
                    rs.getObject("stage_run_id", UUID.class),
                    rs.getString("method"),
                    rs.getString("status"),
                    rs.getString("expected_sha"),
                    rs.getString("merged_sha"),
                    rs.getBoolean("branch_deleted"),
                    rs.getString("error")))
        .list();
  }

  /** Cómo fusiona una fase, de su definición publicada. */
  MergeSpec spec(StageRun stage) {
    UUID definitionId =
        workflowRuns.findById(stage.getWorkflowRunId()).orElseThrow().getDefinitionId();
    return definitions
                .published(definitionId)
                .definition()
                .stage(stage.getStageKey())
                .map(StageDefinition::merge)
                .orElse(null)
            instanceof MergeSpec merge
        ? merge
        : MergeSpec.DEFAULT;
  }

  private void markPullRequest(UUID id, String status, String mergedSha, Instant now) {
    jdbc.sql(
            "UPDATE github_pull_request SET status = :status, merged_sha = :sha, updated_at = :now"
                + " WHERE id = :id")
        .param("status", status)
        .param("sha", mergedSha)
        .param("now", Timestamp.from(now))
        .param("id", id)
        .update();
  }

  /** Los worktrees que subió la entrega: el del push de la PR y los de las correcciones de CI. */
  private List<UUID> pushedWorkspaces(UUID pullRequestId) {
    return jdbc.sql(
            """
            SELECT DISTINCT a.workspace_id FROM agent_run a
            WHERE a.workspace_id IS NOT NULL AND (
              a.id = (SELECT agent_run_id FROM github_pull_request WHERE id = :pr)
              OR a.id IN (SELECT push_agent_run_id FROM github_ci_wait WHERE pull_request_id = :pr))
            """)
        .param("pr", pullRequestId)
        .query(UUID.class)
        .list();
  }

  private Optional<PullRequestRow> pullRequest(UUID runId, UUID repositoryId) {
    return jdbc.sql(
            "SELECT id, status, head_sha, number, url, branch FROM github_pull_request"
                + " WHERE workflow_run_id = ? AND repository_id = ?")
        .params(runId, repositoryId)
        .query(
            (rs, n) ->
                new PullRequestRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("status"),
                    rs.getString("head_sha"),
                    (Integer) rs.getObject("number"),
                    rs.getString("url"),
                    rs.getString("branch")))
        .optional();
  }

  private void lockRun(UUID runId) {
    jdbc.sql("SELECT id FROM workflow_run WHERE id = ? FOR UPDATE")
        .param(runId)
        .query(UUID.class)
        .optional();
  }

  private static String abbreviate(String sha) {
    return sha == null || sha.length() < 7 ? String.valueOf(sha) : sha.substring(0, 7);
  }
}
