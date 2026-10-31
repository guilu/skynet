package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.github.GitHubException;
import dev.skynet.controlplane.github.GitHubRepositories;
import dev.skynet.controlplane.github.GitHubRepositories.Link;
import dev.skynet.controlplane.github.GitHubRepositories.PullRequest;
import dev.skynet.controlplane.project.CodeRepository;
import dev.skynet.controlplane.shared.ConflictException;
import dev.skynet.controlplane.shared.NotFoundException;
import dev.skynet.controlplane.shared.TimeSource;
import dev.skynet.controlplane.workitem.WorkItem;
import dev.skynet.protocol.StageStatus;
import dev.skynet.protocol.runner.PushBranch;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * La entrega por PR de una fase {@code github-pr} (EN-A).
 *
 * <ol>
 *   <li>Al arrancar la fase ({@link #prepare}), comprueba que el repositorio está en GitHub con
 *       acceso y decide la rama: {@code skynet/<clave del trabajo>}.
 *   <li>El runner sube el worktree con un token que pide justo antes ({@link #pushToken}) y avisa
 *       con {@code agent.git.pushed} ({@link #pushed}).
 *   <li>Su invocación termina bien, pero la fase sigue en marcha ({@link
 *       RunTransitions#finishStage} no la cierra) hasta que {@link #openPending} abre o actualiza
 *       la PR con la API de GitHub, fuera de cualquier transacción. Entonces la fase termina bien,
 *       o falla con el motivo.
 * </ol>
 *
 * <p>Hay una fila de {@code github_pull_request} por ejecución y repositorio: los reintentos suben
 * a la misma rama y actualizan la misma PR.
 */
@Service
public class PullRequests {

  private static final Logger LOG = LoggerFactory.getLogger(PullRequests.class);

  static final String PUSHING = "PUSHING";
  static final String PUSHED = "PUSHED";
  static final String OPEN = "OPEN";
  static final String FAILED = "FAILED";

  /** Cuánto se reserva una fila mientras se habla con GitHub. */
  static final Duration LEASE = Duration.ofMinutes(2);

  /** Intentos de abrir la PR antes de rendirse cuando GitHub no responde. */
  static final int MAX_ATTEMPTS = 5;

  private final JdbcClient jdbc;
  private final GitHubRepositories github;
  private final AgentRunRepository agentRuns;
  private final StageRunRepository stageRuns;
  private final RunTransitions transitions;
  private final ApplicationEventPublisher publisher;
  private final TransactionTemplate transactions;
  private final ObjectMapper json;
  private final TimeSource time;
  private final CiChecks ciChecks;

  PullRequests(
      JdbcClient jdbc,
      GitHubRepositories github,
      AgentRunRepository agentRuns,
      StageRunRepository stageRuns,
      RunTransitions transitions,
      ApplicationEventPublisher publisher,
      TransactionTemplate transactions,
      ObjectMapper json,
      TimeSource time,
      CiChecks ciChecks) {
    this.jdbc = jdbc;
    this.github = github;
    this.agentRuns = agentRuns;
    this.stageRuns = stageRuns;
    this.transitions = transitions;
    this.publisher = publisher;
    this.transactions = transactions;
    this.json = json;
    this.time = time;
    this.ciChecks = ciChecks;
  }

  /** Lo que sube una fase {@code github-pr}: el enlace con GitHub y la orden de push. */
  record Delivery(Link link, PushBranch push) {}

  /** La PR de una ejecución, tal como la enseña la API. */
  public record View(
      UUID repositoryId,
      String fullName,
      String branch,
      String base,
      String status,
      String headSha,
      Integer number,
      String url,
      String title,
      String error,
      Instant updatedAt) {}

  /** Si el repositorio está en GitHub con acceso: la condición {@code repository.github}. */
  boolean connected(UUID repositoryId) {
    return github.link(repositoryId).isPresent();
  }

  /** La rama de un trabajo: {@code skynet/<clave en minúsculas>}. */
  static String branchOf(WorkItem workItem) {
    return PushBranch.BRANCH_PREFIX + workItem.getKey().toLowerCase(Locale.ROOT);
  }

  /**
   * Prepara el push de la fase: el repositorio tiene que estar en GitHub con acceso. Sobrescribe la
   * rama remota en el primer push de la ejecución (puede quedar la de una ejecución anterior del
   * mismo trabajo) y en los reintentos, que pueden haber vuelto a una instantánea.
   *
   * @throws ConflictException si el repositorio no está en GitHub o la App no llega a él
   */
  Delivery prepare(
      WorkflowRun run,
      StageRun stage,
      WorkItem workItem,
      CodeRepository repository,
      WorkspaceView source) {
    if (!github.configured()) {
      throw new ConflictException(
          "La GitHub App no está configurada: Skynet no puede subir la rama ni abrir la PR (ver"
              + " Ajustes → GitHub)");
    }
    Link link =
        github
            .link(repository.getId())
            .orElseThrow(
                () ->
                    new ConflictException(
                        "El repositorio "
                            + repository.getName()
                            + " no está enlazado con GitHub o la App no está instalada en él"
                            + " (ver su sección GitHub en el proyecto)"));
    if (source.baseCommit() == null) {
      throw new ConflictException("El worktree " + source.path() + " no tiene commit base");
    }
    boolean firstPush = find(run.getId(), repository.getId()).isEmpty();
    String branch = branchOf(workItem);
    return new Delivery(
        link,
        new PushBranch(
            github.pushUrl(link),
            branch,
            source.baseCommit(),
            firstPush || stage.getAttempt() > 1,
            workItem.getKey() + ": " + workItem.getTitle()));
  }

  /**
   * El push de la fase está en cola con {@code agent}: guarda (o reutiliza) la PR de la ejecución
   * con su título y su descripción, a la espera de que suba.
   */
  void pushing(
      WorkflowRun run,
      StageRun stage,
      CodeRepository repository,
      Delivery delivery,
      AgentRun agent,
      String title,
      String body,
      String base,
      boolean draft,
      List<String> labels,
      Instant now) {
    jdbc.sql(
            """
            INSERT INTO github_pull_request (id, workflow_run_id, repository_id, stage_run_id,
                agent_run_id, full_name, branch, base, title, body, draft, labels, status,
                created_at, updated_at)
            VALUES (:id, :run, :repository, :stage, :agent, :fullName, :branch, :base, :title,
                :body, :draft, CAST(:labels AS jsonb), 'PUSHING', :now, :now)
            ON CONFLICT (workflow_run_id, repository_id) DO UPDATE SET
                stage_run_id = EXCLUDED.stage_run_id, agent_run_id = EXCLUDED.agent_run_id,
                full_name = EXCLUDED.full_name, branch = EXCLUDED.branch, base = EXCLUDED.base,
                title = EXCLUDED.title, body = EXCLUDED.body, draft = EXCLUDED.draft,
                labels = EXCLUDED.labels, status = 'PUSHING', error = NULL, attempts = 0,
                next_attempt_at = NULL, updated_at = EXCLUDED.updated_at
            """)
        .param("id", UUID.randomUUID())
        .param("run", run.getId())
        .param("repository", repository.getId())
        .param("stage", stage.getId())
        .param("agent", agent.getId())
        .param("fullName", delivery.link().fullName())
        .param("branch", delivery.push().branch())
        .param("base", base)
        .param("title", title)
        .param("body", body)
        .param("draft", draft)
        .param("labels", json.writeValueAsString(labels))
        .param("now", Timestamp.from(now))
        .update();
  }

  /**
   * El runner ha subido la rama ({@code agent.git.pushed}): queda pendiente abrir la PR en cuanto
   * termine la invocación.
   */
  void pushed(AgentRun agent, String sha, Instant now) {
    jdbc.sql(
            "UPDATE github_pull_request SET status = 'PUSHED', head_sha = :sha, attempts = 0,"
                + " next_attempt_at = NULL, error = NULL, updated_at = :now"
                + " WHERE agent_run_id = :agent AND status = 'PUSHING'")
        .param("sha", sha)
        .param("now", Timestamp.from(now))
        .param("agent", agent.getId())
        .update();
    // O es la corrección de la CI de una fase github-check (EN-B).
    ciChecks.pushed(agent, sha, now);
  }

  /**
   * Token para el push de {@code agentRunId}, que lo pide su runner: solo para una invocación de
   * push en curso de ese runner. Limitado al repositorio y a {@code contents: write}.
   */
  @Transactional(readOnly = true)
  public String pushToken(UUID runnerId, UUID agentRunId) {
    AgentRun agent =
        agentRuns
            .findById(agentRunId)
            .orElseThrow(() -> new NotFoundException("Invocación", agentRunId));
    if (!runnerId.equals(agent.getRunnerId())) {
      throw new NotFoundException("Invocación", agentRunId);
    }
    if (!agent.isPush()) {
      throw new ConflictException("Esta invocación no sube ninguna rama");
    }
    if (agent.getStatus().isTerminal()) {
      throw new ConflictException("La invocación ya terminó: no necesita el token");
    }
    Link link =
        github
            .link(agent.getRepositoryId())
            .orElseThrow(
                () -> new ConflictException("El repositorio ya no está enlazado con GitHub"));
    try {
      return github.pushToken(link);
    } catch (GitHubException e) {
      throw new ConflictException("GitHub no dio el token: " + e.getMessage());
    }
  }

  /** La PR de cada repositorio de una ejecución, si tiene. */
  @Transactional(readOnly = true)
  public List<View> ofRun(UUID runId) {
    return jdbc.sql(
            "SELECT * FROM github_pull_request WHERE workflow_run_id = ? ORDER BY created_at")
        .param(runId)
        .query(
            (rs, n) ->
                new View(
                    rs.getObject("repository_id", UUID.class),
                    rs.getString("full_name"),
                    rs.getString("branch"),
                    rs.getString("base"),
                    rs.getString("status"),
                    rs.getString("head_sha"),
                    (Integer) rs.getObject("number"),
                    rs.getString("url"),
                    rs.getString("title"),
                    rs.getString("error"),
                    rs.getTimestamp("updated_at").toInstant()))
        .list();
  }

  private Optional<UUID> find(UUID runId, UUID repositoryId) {
    return jdbc.sql(
            "SELECT id FROM github_pull_request WHERE workflow_run_id = ? AND repository_id = ?")
        .params(runId, repositoryId)
        .query(UUID.class)
        .optional();
  }

  /** Una PR pendiente de abrir, reservada para este nodo mientras habla con GitHub. */
  record Pending(
      UUID id,
      UUID runId,
      UUID repositoryId,
      UUID stageRunId,
      UUID agentRunId,
      String branch,
      String base,
      String title,
      String body,
      boolean draft,
      List<String> labels,
      String headSha,
      int attempts) {}

  /**
   * Abre o actualiza las PR cuya rama ya está subida y cuyo push terminó bien. Una invocación de
   * push que terminó bien sin decir qué subió hace fallar su fase.
   */
  @Scheduled(fixedDelayString = "${skynet.github.pull-requests.poll-interval:2s}")
  public void openPending() {
    failSilentPushes();
    List<Pending> pending = transactions.execute(status -> claim());
    if (pending == null) {
      return;
    }
    for (Pending pr : pending) {
      open(pr);
    }
  }

  private List<Pending> claim() {
    Instant now = time.now();
    List<Pending> pending =
        jdbc.sql(
                """
                SELECT p.* FROM github_pull_request p JOIN agent_run a ON a.id = p.agent_run_id
                WHERE p.status = 'PUSHED' AND a.status = 'COMPLETED'
                  AND (p.next_attempt_at IS NULL OR p.next_attempt_at <= :now)
                ORDER BY p.updated_at LIMIT 10 FOR UPDATE OF p SKIP LOCKED
                """)
            .param("now", Timestamp.from(now))
            .query(this::pending)
            .list();
    for (Pending pr : pending) {
      jdbc.sql("UPDATE github_pull_request SET next_attempt_at = ? WHERE id = ?")
          .params(Timestamp.from(now.plus(LEASE)), pr.id())
          .update();
    }
    return pending;
  }

  private Pending pending(ResultSet rs, int n) throws SQLException {
    return new Pending(
        rs.getObject("id", UUID.class),
        rs.getObject("workflow_run_id", UUID.class),
        rs.getObject("repository_id", UUID.class),
        rs.getObject("stage_run_id", UUID.class),
        rs.getObject("agent_run_id", UUID.class),
        rs.getString("branch"),
        rs.getString("base"),
        rs.getString("title"),
        rs.getString("body"),
        rs.getBoolean("draft"),
        json.readValue(rs.getString("labels"), new TypeReference<List<String>>() {}),
        rs.getString("head_sha"),
        rs.getInt("attempts"));
  }

  /** Habla con GitHub fuera de la transacción y luego aplica el resultado. */
  void open(Pending pr) {
    Link link = github.link(pr.repositoryId()).orElse(null);
    if (link == null) {
      transactions.executeWithoutResult(
          status -> {
            lockRun(pr.runId());
            if (stillPending(pr)) {
              fail(
                  pr.id(),
                  pr.stageRunId(),
                  pr.runId(),
                  "No se pudo abrir la PR: el repositorio ya no está enlazado con GitHub o la App"
                      + " no está instalada en él",
                  time.now());
            }
          });
      return;
    }
    PullRequest opened;
    try {
      String base = pr.base() != null ? pr.base() : github.defaultBranch(link);
      opened =
          github.openOrUpdate(
              link, pr.branch(), base, pr.title(), pr.body(), pr.draft(), pr.labels());
    } catch (GitHubException e) {
      LOG.warn("No se pudo abrir la PR de {}: {}", pr.branch(), e.getMessage());
      transactions.executeWithoutResult(status -> failed(pr, e));
      return;
    }
    transactions.executeWithoutResult(status -> opened(pr, opened));
  }

  private void opened(Pending pr, PullRequest opened) {
    Instant now = time.now();
    lockRun(pr.runId());
    if (!stillPending(pr)) {
      return;
    }
    jdbc.sql(
            "UPDATE github_pull_request SET status = 'OPEN', number = :number, url = :url,"
                + " base = :base, error = NULL, next_attempt_at = NULL, updated_at = :now"
                + " WHERE id = :id")
        .param("number", opened.number())
        .param("url", opened.url())
        .param("base", opened.base())
        .param("now", Timestamp.from(now))
        .param("id", pr.id())
        .update();
    StageRun stage = stageRuns.findById(pr.stageRunId()).orElseThrow();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("stageKey", stage.getStageKey());
    payload.put("repositoryId", pr.repositoryId());
    payload.put("number", opened.number());
    payload.put("url", opened.url());
    payload.put("branch", pr.branch());
    payload.put("base", opened.base());
    payload.put("headSha", pr.headSha());
    payload.put("title", pr.title());
    transitions.append(
        "stage_run",
        stage.getId(),
        opened.created() ? "stage.github.pr.opened" : "stage.github.pr.updated",
        pr.runId(),
        payload,
        now);
    if (!stage.getStatus().isTerminal()) {
      transitions.advanceStage(stage, StageStatus.SUCCEEDED, "pull-request-opened", now);
      publisher.publishEvent(new WorkflowRunChanged(pr.runId()));
    }
  }

  /**
   * GitHub no abrió la PR: se reintenta con espera creciente si no respondió o falló, y la fase
   * falla si GitHub dice que no (4xx salvo 429) o tras {@link #MAX_ATTEMPTS} intentos.
   */
  private void failed(Pending pr, GitHubException e) {
    Instant now = time.now();
    lockRun(pr.runId());
    if (!stillPending(pr)) {
      return;
    }
    int attempts = pr.attempts() + 1;
    boolean refused = e.status() / 100 == 4 && e.status() != 429;
    if (!refused && attempts < MAX_ATTEMPTS) {
      jdbc.sql(
              "UPDATE github_pull_request SET attempts = :attempts, error = :error,"
                  + " next_attempt_at = :next, updated_at = :now WHERE id = :id")
          .param("attempts", attempts)
          .param("error", e.getMessage())
          .param("next", Timestamp.from(now.plusSeconds(10L << (attempts - 1))))
          .param("now", Timestamp.from(now))
          .param("id", pr.id())
          .update();
      return;
    }
    fail(pr.id(), pr.stageRunId(), pr.runId(), "No se pudo abrir la PR: " + e.getMessage(), now);
  }

  private void fail(UUID id, UUID stageRunId, UUID runId, String error, Instant now) {
    jdbc.sql(
            "UPDATE github_pull_request SET status = 'FAILED', error = :error,"
                + " next_attempt_at = NULL, updated_at = :now WHERE id = :id")
        .param("error", error)
        .param("now", Timestamp.from(now))
        .param("id", id)
        .update();
    StageRun stage = stageRuns.findById(stageRunId).orElseThrow();
    transitions.append(
        "stage_run",
        stage.getId(),
        "stage.github.pr.failed",
        runId,
        Map.of("stageKey", stage.getStageKey(), "error", error),
        now);
    if (!stage.getStatus().isTerminal()) {
      transitions.advanceStage(stage, StageStatus.FAILED, "pull-request-failed", now);
      publisher.publishEvent(new WorkflowRunChanged(runId));
    }
  }

  /**
   * Un push que terminó bien sin {@code agent.git.pushed} (un runner antiguo) o una fase que se
   * canceló mientras subía: la PR no se abrirá.
   */
  private void failSilentPushes() {
    transactions.executeWithoutResult(
        status -> {
          Instant now = time.now();
          jdbc.sql(
                  """
                  SELECT p.id, p.stage_run_id, p.workflow_run_id FROM github_pull_request p
                  JOIN agent_run a ON a.id = p.agent_run_id
                  WHERE p.status = 'PUSHING' AND a.status = 'COMPLETED'
                  FOR UPDATE OF p SKIP LOCKED
                  """)
              .query(
                  (rs, n) ->
                      new UUID[] {
                        rs.getObject("id", UUID.class),
                        rs.getObject("stage_run_id", UUID.class),
                        rs.getObject("workflow_run_id", UUID.class)
                      })
              .list()
              .forEach(
                  ids -> {
                    lockRun(ids[2]);
                    fail(
                        ids[0],
                        ids[1],
                        ids[2],
                        "El runner terminó el push sin decir qué subió: actualízalo",
                        now);
                  });
        });
  }

  /** Sigue pendiente: nadie la ha abierto ni ha vuelto a subir mientras se hablaba con GitHub. */
  private boolean stillPending(Pending pr) {
    return jdbc.sql(
                "SELECT count(*) FROM github_pull_request WHERE id = ? AND status = 'PUSHED'"
                    + " AND agent_run_id = ?")
            .params(pr.id(), pr.agentRunId())
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
}
