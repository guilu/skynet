package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.definition.CiSpec;
import dev.skynet.controlplane.definition.StageDefinition;
import dev.skynet.controlplane.definition.WorkflowDefinitions;
import dev.skynet.controlplane.github.GitHubCommitChecked;
import dev.skynet.controlplane.github.GitHubException;
import dev.skynet.controlplane.github.GitHubRepositories;
import dev.skynet.controlplane.github.GitHubRepositories.Check;
import dev.skynet.controlplane.github.GitHubRepositories.Link;
import dev.skynet.controlplane.project.CodeRepository;
import dev.skynet.controlplane.project.ProjectService;
import dev.skynet.controlplane.shared.ConflictException;
import dev.skynet.controlplane.shared.PayloadRedactor;
import dev.skynet.controlplane.shared.TimeSource;
import dev.skynet.controlplane.workitem.WorkItem;
import dev.skynet.controlplane.workitem.WorkItemService;
import dev.skynet.protocol.AgentObservableStatus;
import dev.skynet.protocol.StageStatus;
import dev.skynet.protocol.runner.AgentLimits;
import dev.skynet.protocol.runner.PushBranch;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * La espera de la CI de una fase {@code github-check} (EN-B).
 *
 * <ol>
 *   <li>Al arrancar ({@link #start}), la fase pasa a esperar la CI del último commit de la PR de la
 *       ejecución.
 *   <li>Un webhook de CI de ese commit ({@link GitHubCommitChecked}) adelanta la consulta. Si no
 *       llega ninguno, se consulta igual cada {@code recheck}. Siempre decide la API ({@link
 *       #checkDue}), fuera de cualquier transacción.
 *   <li>Verde: la fase termina bien. Roja: si tiene {@code fix} y rondas por delante, el agente de
 *       esa fase corrige en su sesión con lo que falló; al terminar ({@link #settleFixes}) se sube
 *       la rama otra vez y se vuelve a esperar. Si no, la fase falla con lo que falló.
 *   <li>Sin ningún check tras {@code no-checks-after}, la fase pasa (o falla, con {@code
 *       ifNoChecks: fail}). El tiempo máximo es el {@code timeout} de la fase.
 * </ol>
 */
@Service
public class CiChecks {

  private static final Logger LOG = LoggerFactory.getLogger(CiChecks.class);

  static final String WAITING = "WAITING";
  static final String FIXING = "FIXING";
  static final String PUSHING = "PUSHING";
  static final String PASSED = "PASSED";
  static final String FAILED = "FAILED";

  /** Cuánto se reserva una espera mientras se pregunta a GitHub. */
  static final Duration LEASE = Duration.ofMinutes(2);

  /** Tras subir la corrección, lo que se da a GitHub para registrar la CI nueva. */
  static final Duration AFTER_PUSH = Duration.ofSeconds(15);

  /** Si GitHub no responde, cuándo se vuelve a preguntar. */
  static final Duration ON_ERROR = Duration.ofSeconds(30);

  /** Lo que ocupa como mucho el informe del fallo que recibe el agente. */
  static final int MAX_REPORT = 30_000;

  private final JdbcClient jdbc;
  private final GitHubRepositories github;
  private final AgentRunRepository agentRuns;
  private final StageRunRepository stageRuns;
  private final WorkflowRunRepository workflowRuns;
  private final WorkflowDefinitions definitions;
  private final RunTransitions transitions;
  private final ObjectProvider<RunService> runs;
  private final AgentQueue agentQueue;
  private final Workspaces workspaces;
  private final WorkItemService workItems;
  private final ProjectService projects;
  private final PayloadRedactor redactor;
  private final ApplicationEventPublisher publisher;
  private final TransactionTemplate transactions;
  private final ObjectMapper json;
  private final TimeSource time;
  private final Duration recheck;
  private final Duration noChecksAfter;

  CiChecks(
      JdbcClient jdbc,
      GitHubRepositories github,
      AgentRunRepository agentRuns,
      StageRunRepository stageRuns,
      WorkflowRunRepository workflowRuns,
      WorkflowDefinitions definitions,
      RunTransitions transitions,
      ObjectProvider<RunService> runs,
      AgentQueue agentQueue,
      Workspaces workspaces,
      WorkItemService workItems,
      ProjectService projects,
      PayloadRedactor redactor,
      ApplicationEventPublisher publisher,
      TransactionTemplate transactions,
      ObjectMapper json,
      TimeSource time,
      @Value("${skynet.github.ci.recheck:2m}") Duration recheck,
      @Value("${skynet.github.ci.no-checks-after:5m}") Duration noChecksAfter) {
    this.jdbc = jdbc;
    this.github = github;
    this.agentRuns = agentRuns;
    this.stageRuns = stageRuns;
    this.workflowRuns = workflowRuns;
    this.definitions = definitions;
    this.transitions = transitions;
    this.runs = runs;
    this.agentQueue = agentQueue;
    this.workspaces = workspaces;
    this.workItems = workItems;
    this.projects = projects;
    this.redactor = redactor;
    this.publisher = publisher;
    this.transactions = transactions;
    this.json = json;
    this.time = time;
    this.recheck = recheck;
    this.noChecksAfter = noChecksAfter;
  }

  /** La espera de la CI de una fase, tal como la enseña la API. */
  public record View(
      UUID stageRunId,
      String stageKey,
      String status,
      String headSha,
      int round,
      List<Map<String, Object>> checks,
      String error,
      Instant waitingSince,
      Instant updatedAt) {}

  /**
   * Arranca la fase {@code github-check}: espera la CI del último commit subido a la PR de la
   * ejecución en este repositorio.
   *
   * @throws ConflictException si la ejecución no tiene ninguna PR abierta
   */
  void start(WorkflowRun run, StageRun stage, CodeRepository repository, Instant now) {
    PullRequestRow pr =
        pullRequest(run.getId(), repository.getId())
            .filter(p -> PullRequests.OPEN.equals(p.status()) && p.headSha() != null)
            .orElseThrow(
                () ->
                    new ConflictException(
                        "Esta ejecución no tiene ninguna PR abierta: la fase `github-check` va"
                            + " detrás de una `github-pr` que la abra"));
    stage = transitions.advanceStage(stage, StageStatus.STARTING, "ci-waiting", now);
    stage = transitions.advanceStage(stage, StageStatus.RUNNING, "ci-waiting", now);
    jdbc.sql(
            """
            INSERT INTO github_ci_wait (stage_run_id, workflow_run_id, pull_request_id,
                repository_id, head_sha, status, round, waiting_since, next_check_at, created_at,
                updated_at)
            VALUES (:stage, :run, :pr, :repository, :sha, 'WAITING', 0, :now, :now, :now, :now)
            """)
        .param("stage", stage.getId())
        .param("run", run.getId())
        .param("pr", pr.id())
        .param("repository", repository.getId())
        .param("sha", pr.headSha())
        .param("now", Timestamp.from(now))
        .update();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("stageKey", stage.getStageKey());
    payload.put("number", pr.number());
    payload.put("headSha", pr.headSha());
    payload.put("round", 0);
    transitions.append(
        "stage_run", stage.getId(), "stage.github.ci.waiting", run.getId(), payload, now);
  }

  /** Un webhook de CI de un commit: las esperas de ese commit preguntan ya. */
  @EventListener
  void commitChecked(GitHubCommitChecked event) {
    jdbc.sql(
            "UPDATE github_ci_wait SET next_check_at = :now WHERE repository_id = :repository"
                + " AND head_sha = :sha AND status = 'WAITING'")
        .param("now", Timestamp.from(time.now()))
        .param("repository", event.repositoryId())
        .param("sha", event.sha())
        .update();
  }

  /** La espera de la CI de cada fase {@code github-check} de una ejecución. */
  @Transactional(readOnly = true)
  public List<View> ofRun(UUID runId) {
    return jdbc.sql(
            "SELECT w.*, s.stage_key FROM github_ci_wait w JOIN stage_run s ON s.id ="
                + " w.stage_run_id WHERE w.workflow_run_id = ? ORDER BY w.created_at")
        .param(runId)
        .query(
            (rs, n) ->
                new View(
                    rs.getObject("stage_run_id", UUID.class),
                    rs.getString("stage_key"),
                    rs.getString("status"),
                    rs.getString("head_sha"),
                    rs.getInt("round"),
                    json.readValue(
                        rs.getString("checks"), new TypeReference<List<Map<String, Object>>>() {}),
                    rs.getString("error"),
                    rs.getTimestamp("waiting_since").toInstant(),
                    rs.getTimestamp("updated_at").toInstant()))
        .list();
  }

  /** Una espera que toca consultar, reservada para este nodo mientras pregunta a GitHub. */
  record Due(
      UUID stageRunId,
      UUID runId,
      UUID repositoryId,
      UUID pullRequestId,
      String headSha,
      int round,
      Instant waitingSince,
      Integer number) {}

  /** Lo que dijo la CI de un commit. */
  record Verdict(String outcome, List<Check> checks, List<Check> failed, String report) {
    static final String PENDING = "pending";
    static final String PASSED = "passed";
    static final String FAILED = "failed";
    static final String NO_CHECKS = "no-checks";
  }

  /** Consulta la CI de las esperas a las que les toca. */
  @Scheduled(fixedDelayString = "${skynet.github.ci.poll-interval:2s}")
  public void checkDue() {
    List<Due> due = transactions.execute(status -> claim());
    if (due == null) {
      return;
    }
    for (Due wait : due) {
      check(wait);
    }
  }

  private List<Due> claim() {
    Instant now = time.now();
    List<Due> due =
        jdbc.sql(
                """
                SELECT w.*, p.number FROM github_ci_wait w
                JOIN github_pull_request p ON p.id = w.pull_request_id
                WHERE w.status = 'WAITING' AND w.next_check_at <= :now
                ORDER BY w.next_check_at LIMIT 10 FOR UPDATE OF w SKIP LOCKED
                """)
            .param("now", Timestamp.from(now))
            .query(this::due)
            .list();
    for (Due wait : due) {
      jdbc.sql("UPDATE github_ci_wait SET next_check_at = ? WHERE stage_run_id = ?")
          .params(Timestamp.from(now.plus(LEASE)), wait.stageRunId())
          .update();
    }
    return due;
  }

  private Due due(ResultSet rs, int n) throws SQLException {
    return new Due(
        rs.getObject("stage_run_id", UUID.class),
        rs.getObject("workflow_run_id", UUID.class),
        rs.getObject("repository_id", UUID.class),
        rs.getObject("pull_request_id", UUID.class),
        rs.getString("head_sha"),
        rs.getInt("round"),
        rs.getTimestamp("waiting_since").toInstant(),
        (Integer) rs.getObject("number"));
  }

  /** Pregunta a GitHub fuera de la transacción y luego aplica lo que dijo. */
  void check(Due wait) {
    StageRun stage = stageRuns.findById(wait.stageRunId()).orElse(null);
    if (stage == null) {
      return;
    }
    CiSpec spec = spec(stage);
    Link link = github.link(wait.repositoryId()).orElse(null);
    if (link == null) {
      transactions.executeWithoutResult(
          status ->
              finish(
                  wait,
                  false,
                  "No se pudo leer la CI: el repositorio ya no está enlazado con GitHub o la App no"
                      + " está instalada en él",
                  List.of(),
                  null,
                  time.now()));
      return;
    }
    Verdict verdict;
    try {
      List<Check> checks = github.checks(link, wait.headSha());
      verdict = verdict(checks, spec, wait.waitingSince(), time.now());
      if (Verdict.FAILED.equals(verdict.outcome())) {
        String report;
        try {
          report = github.failureReport(link, verdict.failed());
        } catch (GitHubException e) {
          report = "(No se pudo leer el detalle del fallo: " + e.getMessage() + ")";
        }
        verdict =
            new Verdict(verdict.outcome(), checks, verdict.failed(), cut(redactor.redact(report)));
      }
    } catch (GitHubException e) {
      LOG.warn("No se pudo leer la CI de {}: {}", wait.headSha(), e.getMessage());
      transactions.executeWithoutResult(
          status -> {
            if (stillWaiting(wait)) {
              jdbc.sql(
                      "UPDATE github_ci_wait SET next_check_at = :next, error = :error,"
                          + " updated_at = :now WHERE stage_run_id = :stage")
                  .param("next", Timestamp.from(time.now().plus(ON_ERROR)))
                  .param("error", "No se pudo leer la CI: " + e.getMessage())
                  .param("now", Timestamp.from(time.now()))
                  .param("stage", wait.stageRunId())
                  .update();
            }
          });
      return;
    }
    Verdict decided = verdict;
    Fix fix = transactions.execute(status -> apply(wait, spec, decided));
    if (fix != null && wait.number() != null) {
      try {
        github.comment(link, wait.number(), fix.comment());
      } catch (GitHubException e) {
        LOG.warn("No se pudo comentar en la PR #{}: {}", wait.number(), e.getMessage());
      }
    }
  }

  /**
   * Lo que dice la CI: roja si alguno de los checks a los que espera falló; si no, pendiente
   * mientras falte alguno o alguno siga en marcha, y verde cuando han pasado todos. Sin ningún
   * check tras {@code noChecksAfter}, {@code no-checks}.
   */
  Verdict verdict(List<Check> all, CiSpec spec, Instant waitingSince, Instant now) {
    List<Check> relevant =
        spec.required().isEmpty()
            ? all
            : all.stream().filter(c -> spec.required().contains(c.name())).toList();
    List<Check> failed = relevant.stream().filter(c -> Check.FAILURE.equals(c.state())).toList();
    if (!failed.isEmpty()) {
      return new Verdict(Verdict.FAILED, all, failed, null);
    }
    boolean missing =
        spec.required().stream()
            .anyMatch(name -> relevant.stream().noneMatch(c -> c.name().equals(name)));
    if (all.isEmpty() && !now.isBefore(waitingSince.plus(noChecksAfter))) {
      return new Verdict(Verdict.NO_CHECKS, all, List.of(), null);
    }
    if (relevant.isEmpty()
        || missing
        || relevant.stream().anyMatch(c -> Check.PENDING.equals(c.state()))) {
      return new Verdict(Verdict.PENDING, all, List.of(), null);
    }
    return new Verdict(Verdict.PASSED, all, List.of(), null);
  }

  /** Una ronda de corrección que ha empezado: lo que se comenta en la PR. */
  record Fix(String comment) {}

  private Fix apply(Due wait, CiSpec spec, Verdict verdict) {
    Instant now = time.now();
    lockRun(wait.runId());
    if (!stillWaiting(wait)) {
      return null;
    }
    StageRun stage = stageRuns.findById(wait.stageRunId()).orElseThrow();
    if (stage.getStatus().isTerminal()) {
      update(wait.stageRunId(), FAILED, verdict.checks(), "La fase ya había terminado", null, now);
      return null;
    }
    switch (verdict.outcome()) {
      case Verdict.PENDING -> {
        update(wait.stageRunId(), WAITING, verdict.checks(), null, now.plus(recheck), now);
        return null;
      }
      case Verdict.PASSED -> {
        finish(wait, true, null, verdict.checks(), null, now);
        return null;
      }
      case Verdict.NO_CHECKS -> {
        boolean pass = CiSpec.PASS.equals(spec.ifNoChecks());
        finish(
            wait,
            pass,
            pass
                ? null
                : "No ha aparecido ningún check de CI en "
                    + noChecksAfter.toMinutes()
                    + " min (ifNoChecks: fail)",
            List.of(),
            pass ? "El repositorio no tiene CI: no ha aparecido ningún check" : null,
            now);
        return null;
      }
      default -> {
        return failed(wait, stage, spec, verdict, now);
      }
    }
  }

  /** La CI está roja: corrige si quedan rondas o la fase falla con lo que falló. */
  private Fix failed(Due wait, StageRun stage, CiSpec spec, Verdict verdict, Instant now) {
    List<String> names = verdict.failed().stream().map(Check::name).toList();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("stageKey", stage.getStageKey());
    payload.put("headSha", wait.headSha());
    payload.put("round", wait.round());
    payload.put("failed", names);
    payload.put("checks", summary(verdict.checks()));
    transitions.append(
        "stage_run", stage.getId(), "stage.github.ci.failed", wait.runId(), payload, now);
    String failure = "La CI falló en " + String.join(", ", names);
    if (spec.fix() == null || wait.round() >= spec.maxRounds()) {
      String reason =
          spec.fix() == null
              ? failure
              : failure
                  + " tras "
                  + (spec.maxRounds() == 1 ? "1 ronda" : spec.maxRounds() + " rondas")
                  + " de corrección";
      close(wait, stage, StageStatus.FAILED, "ci-failed", reason, verdict.checks(), now);
      return null;
    }
    int round = wait.round() + 1;
    WorkflowRun run = workflowRuns.findById(wait.runId()).orElseThrow();
    StageRun fixStage = latestStage(wait.runId(), spec.fix()).orElse(null);
    AgentRun agent;
    try {
      if (fixStage == null) {
        throw new ConflictException("La fase " + spec.fix() + " no se ha ejecutado");
      }
      agent =
          runs.getObject()
              .queueCorrection(
                  run, fixStage, fixPrompt(wait, round, spec.maxRounds(), verdict.report()), now);
    } catch (ConflictException e) {
      close(
          wait,
          stage,
          StageStatus.FAILED,
          "ci-fix-failed",
          failure + ". No se pudo corregir: " + e.getMessage(),
          verdict.checks(),
          now);
      return null;
    }
    jdbc.sql(
            "UPDATE github_ci_wait SET status = 'FIXING', round = :round, fix_agent_run_id ="
                + " :agent, checks = CAST(:checks AS jsonb), error = :error,"
                + " next_check_at = NULL, updated_at = :now WHERE stage_run_id = :stage")
        .param("round", round)
        .param("agent", agent.getId())
        .param("checks", json.writeValueAsString(summary(verdict.checks())))
        .param("error", failure)
        .param("now", Timestamp.from(now))
        .param("stage", stage.getId())
        .update();
    Map<String, Object> started = new LinkedHashMap<>();
    started.put("stageKey", stage.getStageKey());
    started.put("round", round);
    started.put("maxRounds", spec.maxRounds());
    started.put("fixStageKey", spec.fix());
    started.put("agentRunId", agent.getId());
    started.put("failed", names);
    transitions.append(
        "stage_run", stage.getId(), "stage.github.ci.fix.started", wait.runId(), started, now);
    publisher.publishEvent(new WorkflowRunChanged(wait.runId()));
    return new Fix(
        "**Skynet:** la CI falló en "
            + String.join(", ", names.stream().map(n -> "`" + n + "`").toList())
            + " (`"
            + abbreviate(wait.headSha())
            + "`). El agente de `"
            + spec.fix()
            + "` lo está corrigiendo: ronda "
            + round
            + " de "
            + spec.maxRounds()
            + ".");
  }

  private String fixPrompt(Due wait, int round, int maxRounds, String report) {
    return "La CI de la PR"
        + (wait.number() == null ? "" : " #" + wait.number())
        + " ha fallado en el commit "
        + abbreviate(wait.headSha())
        + " (ronda de corrección "
        + round
        + " de "
        + maxRounds
        + "). Corrige el código para que pase y deja los cambios hechos en el worktree. No toques"
        + " `.github/workflows/`: Skynet no puede subir cambios ahí.\n\n"
        + "## Lo que falló\n\n"
        + (report == null || report.isBlank() ? "(GitHub no dio más detalle)" : report);
  }

  /**
   * Cierra las correcciones de la CI que han terminado (el motor, con la ejecución bloqueada): si
   * la corrección terminó bien, sube la rama otra vez desde su worktree y la fase vuelve a esperar
   * cuando el runner avisa de lo que subió ({@link #pushed}). Si falló o se canceló, la fase falla
   * o se cancela. Devuelve si alguna fase ha cambiado.
   */
  boolean settleFixes(UUID runId) {
    boolean changed = false;
    List<UUID[]> fixing =
        jdbc.sql(
                "SELECT stage_run_id, fix_agent_run_id, pull_request_id FROM github_ci_wait"
                    + " WHERE workflow_run_id = ? AND status = 'FIXING'")
            .param(runId)
            .query(
                (rs, n) ->
                    new UUID[] {
                      rs.getObject("stage_run_id", UUID.class),
                      rs.getObject("fix_agent_run_id", UUID.class),
                      rs.getObject("pull_request_id", UUID.class)
                    })
            .list();
    for (UUID[] ids : fixing) {
      StageRun stage = stageRuns.findById(ids[0]).orElseThrow();
      AgentRun fix = agentRuns.findById(ids[1]).orElseThrow();
      if (!fix.getStatus().isTerminal()) {
        continue;
      }
      Instant now = time.now();
      if (stage.getStatus().isTerminal()) {
        status(stage.getId(), FAILED, now);
        continue;
      }
      if (fix.getStatus() == AgentObservableStatus.CANCELLED) {
        status(stage.getId(), FAILED, now);
        transitions.advanceStage(stage, StageStatus.CANCELLED, "ci-fix-cancelled", now);
        changed = true;
        continue;
      }
      if (fix.getStatus() != AgentObservableStatus.COMPLETED) {
        fail(
            stage,
            "La corrección de la CI no terminó bien"
                + (fix.getError() == null ? "" : ": " + fix.getError()),
            now);
        changed = true;
        continue;
      }
      WorkspaceView workspace =
          fix.getWorkspaceId() == null ? null : workspaces.find(fix.getWorkspaceId()).orElse(null);
      if (workspace == null || workspace.baseCommit() == null) {
        fail(stage, "La corrección de la CI no dejó un worktree que subir", now);
        changed = true;
        continue;
      }
      workspaces.lock(workspace.id());
      if (workspaces.hasLiveInvocation(workspace.id())) {
        // Su verificación sigue en marcha: se sube cuando termine.
        continue;
      }
      try {
        RunService.requireUsable(workspaces.find(workspace.id()).orElseThrow());
        push(runId, stage, ids[2], workspace, now);
      } catch (ConflictException e) {
        fail(stage, "No se pudo subir la corrección: " + e.getMessage(), now);
      }
      changed = true;
    }
    return changed;
  }

  /** Pone en cola el push de la corrección, a la misma rama y sin forzar. */
  private void push(
      UUID runId, StageRun stage, UUID pullRequestId, WorkspaceView workspace, Instant now) {
    WorkflowRun run = workflowRuns.findById(runId).orElseThrow();
    WorkItem workItem = workItems.get(run.getWorkItemId());
    UUID repositoryId =
        jdbc.sql("SELECT repository_id FROM github_pull_request WHERE id = ?")
            .param(pullRequestId)
            .query(UUID.class)
            .single();
    CodeRepository repository = projects.getRepository(repositoryId);
    workItems.requireActive(workItem);
    projects.requireActive(repository);
    Link link =
        github
            .link(repositoryId)
            .orElseThrow(
                () ->
                    new ConflictException(
                        "el repositorio ya no está enlazado con GitHub o la App no está instalada"
                            + " en él"));
    PushBranch branch =
        new PushBranch(
            github.pushUrl(link),
            PullRequests.branchOf(workItem),
            workspace.baseCommit(),
            false,
            workItem.getKey() + ": corrige la CI");
    EffectivePolicy policy =
        EffectivePolicy.of(projects.agentPolicy(repository), AgentLimits.none());
    AgentRun agent =
        agentQueue.queue(
            run,
            stage,
            workItem,
            repository,
            Invocation.inWorkspace("", policy, workspace).asPush(branch),
            now);
    jdbc.sql(
            "UPDATE github_ci_wait SET status = 'PUSHING', push_agent_run_id = :agent,"
                + " updated_at = :now WHERE stage_run_id = :stage")
        .param("agent", agent.getId())
        .param("now", Timestamp.from(now))
        .param("stage", stage.getId())
        .update();
  }

  /**
   * El runner ha subido la corrección ({@code agent.git.pushed}): la PR tiene un commit nuevo y la
   * fase vuelve a esperar su CI. Si no cambió nada, la fase falla: la CI volvería a fallar igual.
   */
  void pushed(AgentRun agent, String sha, Instant now) {
    Optional<UUID[]> wait =
        jdbc.sql(
                "SELECT stage_run_id, pull_request_id FROM github_ci_wait"
                    + " WHERE push_agent_run_id = ? AND status = 'PUSHING'")
            .param(agent.getId())
            .query(
                (rs, n) ->
                    new UUID[] {
                      rs.getObject("stage_run_id", UUID.class),
                      rs.getObject("pull_request_id", UUID.class)
                    })
            .optional();
    if (wait.isEmpty()) {
      return;
    }
    UUID stageRunId = wait.get()[0];
    String previous =
        jdbc.sql("SELECT head_sha FROM github_ci_wait WHERE stage_run_id = ?")
            .param(stageRunId)
            .query(String.class)
            .single();
    if (sha.equals(previous)) {
      StageRun stage = stageRuns.findById(stageRunId).orElseThrow();
      fail(stage, "La corrección no cambió nada: la CI volvería a fallar igual", now);
      publisher.publishEvent(new WorkflowRunChanged(stage.getWorkflowRunId()));
      return;
    }
    jdbc.sql(
            "UPDATE github_ci_wait SET status = 'WAITING', head_sha = :sha, waiting_since = :now,"
                + " next_check_at = :next, checks = '[]'::jsonb, updated_at = :now"
                + " WHERE stage_run_id = :stage")
        .param("sha", sha)
        .param("now", Timestamp.from(now))
        .param("next", Timestamp.from(now.plus(AFTER_PUSH)))
        .param("stage", stageRunId)
        .update();
    jdbc.sql("UPDATE github_pull_request SET head_sha = :sha, updated_at = :now WHERE id = :id")
        .param("sha", sha)
        .param("now", Timestamp.from(now))
        .param("id", wait.get()[1])
        .update();
    StageRun stage = stageRuns.findById(stageRunId).orElseThrow();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("stageKey", stage.getStageKey());
    payload.put("headSha", sha);
    payload.put(
        "round",
        jdbc.sql("SELECT round FROM github_ci_wait WHERE stage_run_id = ?")
            .param(stageRunId)
            .query(Integer.class)
            .single());
    transitions.append(
        "stage_run", stageRunId, "stage.github.ci.waiting", stage.getWorkflowRunId(), payload, now);
  }

  /** La corrección en curso de la fase, para pararla con ella (cancelar, tiempo agotado). */
  Optional<UUID> fixAgent(UUID stageRunId) {
    return jdbc.sql(
            "SELECT fix_agent_run_id FROM github_ci_wait WHERE stage_run_id = ?"
                + " AND status = 'FIXING'")
        .param(stageRunId)
        .query(UUID.class)
        .optional();
  }

  private void finish(
      Due wait, boolean passed, String error, List<Check> checks, String warning, Instant now) {
    lockRun(wait.runId());
    if (!stillWaiting(wait)) {
      return;
    }
    StageRun stage = stageRuns.findById(wait.stageRunId()).orElseThrow();
    if (passed) {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("stageKey", stage.getStageKey());
      payload.put("headSha", wait.headSha());
      payload.put("round", wait.round());
      payload.put("checks", summary(checks));
      if (warning != null) {
        payload.put("warning", warning);
      }
      transitions.append(
          "stage_run", stage.getId(), "stage.github.ci.passed", wait.runId(), payload, now);
      close(wait, stage, StageStatus.SUCCEEDED, "ci-passed", warning, checks, now);
    } else {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("stageKey", stage.getStageKey());
      payload.put("headSha", wait.headSha());
      payload.put("round", wait.round());
      payload.put("error", error);
      transitions.append(
          "stage_run", stage.getId(), "stage.github.ci.failed", wait.runId(), payload, now);
      close(wait, stage, StageStatus.FAILED, "ci-failed", error, checks, now);
    }
  }

  private void close(
      Due wait,
      StageRun stage,
      StageStatus status,
      String reason,
      String error,
      List<Check> checks,
      Instant now) {
    update(
        wait.stageRunId(),
        status == StageStatus.SUCCEEDED ? PASSED : FAILED,
        checks,
        error,
        null,
        now);
    if (!stage.getStatus().isTerminal()) {
      transitions.advanceStage(stage, status, reason, now);
      publisher.publishEvent(new WorkflowRunChanged(wait.runId()));
    }
  }

  private void fail(StageRun stage, String error, Instant now) {
    status(stage.getId(), FAILED, now);
    jdbc.sql("UPDATE github_ci_wait SET error = ? WHERE stage_run_id = ?")
        .params(error, stage.getId())
        .update();
    transitions.append(
        "stage_run",
        stage.getId(),
        "stage.github.ci.failed",
        stage.getWorkflowRunId(),
        Map.of("stageKey", stage.getStageKey(), "error", error),
        now);
    if (!stage.getStatus().isTerminal()) {
      transitions.advanceStage(stage, StageStatus.FAILED, "ci-fix-failed", now);
    }
  }

  private void update(
      UUID stageRunId, String status, List<Check> checks, String error, Instant next, Instant now) {
    jdbc.sql(
            "UPDATE github_ci_wait SET status = :status, checks = CAST(:checks AS jsonb),"
                + " error = :error, next_check_at = :next, updated_at = :now"
                + " WHERE stage_run_id = :stage")
        .param("status", status)
        .param("checks", json.writeValueAsString(summary(checks)))
        .param("error", error)
        .param("next", next == null ? null : Timestamp.from(next))
        .param("now", Timestamp.from(now))
        .param("stage", stageRunId)
        .update();
  }

  private void status(UUID stageRunId, String status, Instant now) {
    jdbc.sql(
            "UPDATE github_ci_wait SET status = ?, next_check_at = NULL, updated_at = ?"
                + " WHERE stage_run_id = ?")
        .params(status, Timestamp.from(now), stageRunId)
        .update();
  }

  /** Lo que se guarda y se enseña de cada check: nombre, tipo, estado, conclusión y enlace. */
  private static List<Map<String, Object>> summary(List<Check> checks) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Check check : checks) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("name", check.name());
      item.put("kind", check.kind());
      item.put("state", check.state());
      item.put("conclusion", check.conclusion());
      item.put("url", check.url());
      out.add(item);
    }
    return out;
  }

  private CiSpec spec(StageRun stage) {
    UUID definitionId =
        workflowRuns.findById(stage.getWorkflowRunId()).orElseThrow().getDefinitionId();
    return definitions
                .published(definitionId)
                .definition()
                .stage(stage.getStageKey())
                .map(StageDefinition::ci)
                .orElse(null)
            instanceof CiSpec ci
        ? ci
        : CiSpec.DEFAULT;
  }

  private Optional<StageRun> latestStage(UUID runId, String stageKey) {
    return jdbc.sql(
            "SELECT id FROM stage_run WHERE workflow_run_id = ? AND stage_key = ?"
                + " ORDER BY attempt DESC LIMIT 1")
        .params(runId, stageKey)
        .query(UUID.class)
        .optional()
        .flatMap(stageRuns::findById);
  }

  private record PullRequestRow(UUID id, String status, String headSha, Integer number) {}

  private Optional<PullRequestRow> pullRequest(UUID runId, UUID repositoryId) {
    return jdbc.sql(
            "SELECT id, status, head_sha, number FROM github_pull_request"
                + " WHERE workflow_run_id = ? AND repository_id = ?")
        .params(runId, repositoryId)
        .query(
            (rs, n) ->
                new PullRequestRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("status"),
                    rs.getString("head_sha"),
                    (Integer) rs.getObject("number")))
        .optional();
  }

  /** Sigue esperando el mismo commit: nadie lo ha decidido mientras se preguntaba a GitHub. */
  private boolean stillWaiting(Due wait) {
    return jdbc.sql(
                "SELECT count(*) FROM github_ci_wait WHERE stage_run_id = ? AND status = 'WAITING'"
                    + " AND head_sha = ?")
            .params(wait.stageRunId(), wait.headSha())
            .query(Integer.class)
            .single()
        > 0;
  }

  /** Como el motor: los cambios de una ejecución se hacen con ella bloqueada. */
  private void lockRun(UUID runId) {
    jdbc.sql("SELECT id FROM workflow_run WHERE id = ? FOR UPDATE")
        .param(runId)
        .query(UUID.class)
        .optional();
  }

  private static String abbreviate(String sha) {
    return sha == null || sha.length() < 7 ? String.valueOf(sha) : sha.substring(0, 7);
  }

  private static String cut(String text) {
    return text.length() <= MAX_REPORT ? text : "…" + text.substring(text.length() - MAX_REPORT);
  }
}
