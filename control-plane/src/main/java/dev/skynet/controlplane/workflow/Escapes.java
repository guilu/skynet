package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.github.GitHubPullRequestClosed;
import dev.skynet.controlplane.github.GitHubPushed;
import dev.skynet.controlplane.shared.ConflictException;
import dev.skynet.controlplane.shared.InvalidRequestException;
import dev.skynet.controlplane.shared.NotFoundException;
import dev.skynet.controlplane.shared.TimeSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Los escapes del juez (EV-A): un cambio que el juez aprobó, Skynet fusionó y luego se revirtió en
 * la rama por defecto, o que una persona marcó porque se le escapó un fallo. Hay uno como mucho por
 * ejecución: la PR del revert y el push que deja en la rama por defecto son el mismo escape.
 *
 * <p>Se ve de tres maneras: una PR de revert fusionada ({@link #REVERT_PR}, lo que escribe el botón
 * «Revert» de GitHub), un commit «This reverts commit …» en la rama por defecto ({@link
 * #REVERT_PUSH}, con el evento Push de la App) o a mano ({@link #MANUAL}). Solo lo registra: no
 * revierte nada.
 */
@Component
class Escapes {

  static final String REVERT_PR = "revert-pr";
  static final String REVERT_PUSH = "revert-push";
  static final String MANUAL = "manual";

  /** Quien registra los escapes que se ven solos. */
  static final String ACTOR = "skynet";

  /** «Reverts guilu/demo-repo#12», en una línea de la descripción de la PR. */
  private static final Pattern REVERTS =
      Pattern.compile("(?im)^\\s*Reverts\\s+([\\w.-]+/[\\w.-]+)#(\\d+)\\b");

  /** «This reverts commit 1a2b3c4…», en un mensaje de commit. */
  private static final Pattern REVERTS_COMMIT =
      Pattern.compile("(?i)\\bThis reverts commit ([0-9a-f]{7,40})\\b");

  private final JdbcClient jdbc;
  private final RunTransitions transitions;
  private final TimeSource time;
  private final Evals evals;

  Escapes(JdbcClient jdbc, RunTransitions transitions, TimeSource time, Evals evals) {
    this.jdbc = jdbc;
    this.evals = evals;
    this.transitions = transitions;
    this.time = time;
  }

  // ---- Qué es un revert -------------------------------------------------------------------------

  /**
   * El número de la PR que revierte una PR de {@code fullName}: su título empieza por {@code Revert
   * "} y su descripción dice {@code Reverts fullName#N}. {@code null} si no es un revert.
   */
  static Long revertedNumber(String fullName, String title, String body) {
    if (fullName == null || title == null || body == null || !title.startsWith("Revert \"")) {
      return null;
    }
    Matcher m = REVERTS.matcher(body);
    while (m.find()) {
      if (m.group(1).equalsIgnoreCase(fullName)) {
        return Long.parseLong(m.group(2));
      }
    }
    return null;
  }

  /** Los commits que dice revertir un mensaje ({@code This reverts commit …}), en minúsculas. */
  static List<String> revertedShas(String message) {
    List<String> shas = new ArrayList<>();
    if (message == null) {
      return shas;
    }
    Matcher m = REVERTS_COMMIT.matcher(message);
    while (m.find()) {
      shas.add(m.group(1).toLowerCase(Locale.ROOT));
    }
    return shas;
  }

  // ---- Lo que llega de GitHub -------------------------------------------------------------------

  /** Una PR de revert fusionada: por el número que dice revertir o por los commits que nombra. */
  @EventListener
  @Transactional
  void pullRequestClosed(GitHubPullRequestClosed event) {
    if (!event.merged()) {
      return;
    }
    Long number = revertedNumber(event.fullName(), event.title(), event.body());
    List<Merged> reverted = new ArrayList<>();
    if (number != null) {
      byNumber(event.repositoryId(), number).ifPresent(reverted::add);
    }
    if (reverted.isEmpty() && event.title() != null && event.title().startsWith("Revert \"")) {
      for (String sha : revertedShas(event.body())) {
        bySha(event.repositoryId(), sha).ifPresent(reverted::add);
      }
    }
    Instant now = time.now();
    for (Merged merged : reverted) {
      record(merged, REVERT_PR, "#" + event.number(), event.url(), null, ACTOR, now);
    }
  }

  /** Un push a la rama por defecto: cada commit que revierte uno que fusionó Skynet. */
  @EventListener
  @Transactional
  void pushed(GitHubPushed event) {
    Instant now = time.now();
    for (GitHubPushed.Commit commit : event.commits()) {
      for (String sha : revertedShas(commit.message())) {
        Optional<Merged> merged = bySha(event.repositoryId(), sha);
        if (merged.isPresent()) {
          String ref = commit.sha().length() > 7 ? commit.sha().substring(0, 7) : commit.sha();
          record(merged.get(), REVERT_PUSH, ref, commit.url(), null, ACTOR, now);
        }
      }
    }
  }

  /** Una PR de Skynet fusionada en el repositorio, por su número. */
  private Optional<Merged> byNumber(UUID repositoryId, long number) {
    return jdbc.sql(
            MERGED
                + " WHERE p.repository_id = ? AND p.number = ? AND p.status = 'MERGED'"
                + " ORDER BY p.updated_at DESC LIMIT 1")
        .params(repositoryId, number)
        .query(Escapes::merged)
        .optional();
  }

  /** Una PR de Skynet fusionada en el repositorio, por el principio del commit de la fusión. */
  private Optional<Merged> bySha(UUID repositoryId, String sha) {
    return jdbc.sql(
            MERGED
                + " WHERE p.repository_id = ? AND p.status = 'MERGED' AND p.merged_sha LIKE ?"
                + " ORDER BY p.updated_at DESC LIMIT 1")
        .params(repositoryId, sha + "%")
        .query(Escapes::merged)
        .optional();
  }

  private static final String MERGED =
      "SELECT p.id, p.workflow_run_id, p.repository_id, p.merged_sha, p.number FROM"
          + " github_pull_request p";

  private record Merged(UUID id, UUID runId, UUID repositoryId, String sha, Integer number) {}

  private static Merged merged(ResultSet rs, int n) throws SQLException {
    return new Merged(
        rs.getObject("id", UUID.class),
        rs.getObject("workflow_run_id", UUID.class),
        rs.getObject("repository_id", UUID.class),
        rs.getString("merged_sha"),
        (Integer) rs.getObject("number"));
  }

  /** Guarda el escape, si la ejecución aún no tiene uno, y avisa. */
  private Optional<UUID> record(
      Merged merged,
      String origin,
      String seenRef,
      String seenUrl,
      String note,
      String actor,
      Instant now) {
    UUID id = UUID.randomUUID();
    int inserted =
        jdbc.sql(
                """
                INSERT INTO judge_escape (id, repository_id, workflow_run_id, pull_request_id,
                  merged_sha, origin, seen_ref, seen_url, note, created_by, created_at)
                VALUES (:id, :repository, :run, :pr, :sha, :origin, :ref, :url, :note, :actor, :now)
                ON CONFLICT (workflow_run_id) DO NOTHING
                """)
            .param("id", id)
            .param("repository", merged.repositoryId())
            .param("run", merged.runId())
            .param("pr", merged.id())
            .param("sha", merged.sha())
            .param("origin", origin)
            .param("ref", seenRef)
            .param("url", seenUrl)
            .param("note", note)
            .param("actor", actor)
            .param("now", Timestamp.from(now))
            .update();
    if (inserted == 0) {
      return Optional.empty();
    }
    EscapeView escape = view(id);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("escapeId", id);
    payload.put("origin", origin);
    payload.put("workItemKey", escape.workItemKey());
    payload.put("number", escape.pullRequestNumber());
    payload.put("seenRef", seenRef);
    payload.put("seenUrl", seenUrl);
    payload.put("note", note);
    payload.put("actor", actor);
    transitions.append("workflow_run", merged.runId(), "run.escaped", merged.runId(), payload, now);
    // Cada escape es un caso del banco que el juez debería haber suspendido (EV-C).
    evals.fromEscape(id, merged.runId(), actor, now);
    return Optional.of(id);
  }

  // ---- Lo que ve y decide la persona ------------------------------------------------------------

  /** Marca a mano que a la ejecución se le escapó un fallo. Tiene que haber fusionado una PR. */
  @Transactional
  EscapeView mark(UUID runId, String note, String actor) {
    requireRun(runId);
    Merged merged =
        jdbc.sql(
                MERGED
                    + " WHERE p.workflow_run_id = ? AND p.status = 'MERGED'"
                    + " ORDER BY p.updated_at DESC LIMIT 1")
            .param(runId)
            .query(Escapes::merged)
            .optional()
            .orElseThrow(
                () ->
                    new ConflictException(
                        "La ejecución no fusionó ninguna PR: no se le puede escapar nada"));
    String clean = note == null || note.isBlank() ? null : note.strip();
    if (clean != null && clean.length() > 2000) {
      throw new InvalidRequestException("La nota pasa de 2000 caracteres");
    }
    UUID id =
        record(merged, MANUAL, null, null, clean, actor, time.now())
            .orElseThrow(() -> new ConflictException("La ejecución ya tiene un escape"));
    return view(id);
  }

  /** Quita un escape: no fue un fallo del juez (p. ej. se revirtió por otro motivo). */
  @Transactional
  void remove(UUID id, String actor) {
    EscapeView escape = view(id);
    jdbc.sql("DELETE FROM judge_escape WHERE id = ?").param(id).update();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("escapeId", id);
    payload.put("origin", escape.origin());
    payload.put("workItemKey", escape.workItemKey());
    payload.put("actor", actor);
    transitions.append(
        "workflow_run", escape.runId(), "run.escape.removed", escape.runId(), payload, time.now());
  }

  @Transactional(readOnly = true)
  List<EscapeView> ofRun(UUID runId) {
    requireRun(runId);
    return jdbc.sql(SELECT + " WHERE e.workflow_run_id = ?")
        .param(runId)
        .query(Escapes::view)
        .list();
  }

  /** Los escapes de los últimos {@code days} días, del más reciente al más antiguo. */
  @Transactional(readOnly = true)
  List<EscapeView> since(int days) {
    if (days < 1 || days > 365) {
      throw new InvalidRequestException("`days` va de 1 a 365");
    }
    return jdbc.sql(SELECT + " WHERE e.created_at >= ? ORDER BY e.created_at DESC, e.id")
        .param(Timestamp.from(time.now().minus(Duration.ofDays(days))))
        .query(Escapes::view)
        .list();
  }

  private void requireRun(UUID runId) {
    if (!jdbc.sql("SELECT EXISTS (SELECT 1 FROM workflow_run WHERE id = ?)")
        .param(runId)
        .query(Boolean.class)
        .single()) {
      throw new NotFoundException("Ejecución", runId);
    }
  }

  private EscapeView view(UUID id) {
    return jdbc.sql(SELECT + " WHERE e.id = ?")
        .param(id)
        .query(Escapes::view)
        .optional()
        .orElseThrow(() -> new NotFoundException("Escape", id));
  }

  private static final String SELECT =
      """
      SELECT e.id, e.workflow_run_id, w.key AS work_item_key, w.title AS work_item_title,
        e.repository_id, r.name AS repository_name, p.number, p.url, e.merged_sha, e.origin,
        e.seen_ref, e.seen_url, e.note, e.created_by, e.created_at
      FROM judge_escape e
      JOIN workflow_run run ON run.id = e.workflow_run_id
      JOIN work_item w ON w.id = run.work_item_id
      JOIN repository r ON r.id = e.repository_id
      JOIN github_pull_request p ON p.id = e.pull_request_id
      """;

  /** Un escape, como lo ve la web. */
  record EscapeView(
      UUID id,
      UUID runId,
      String workItemKey,
      String workItemTitle,
      UUID repositoryId,
      String repositoryName,
      Integer pullRequestNumber,
      String pullRequestUrl,
      String mergedSha,
      String origin,
      String seenRef,
      String seenUrl,
      String note,
      String createdBy,
      Instant createdAt) {}

  private static EscapeView view(ResultSet rs, int n) throws SQLException {
    return new EscapeView(
        rs.getObject("id", UUID.class),
        rs.getObject("workflow_run_id", UUID.class),
        rs.getString("work_item_key"),
        rs.getString("work_item_title"),
        rs.getObject("repository_id", UUID.class),
        rs.getString("repository_name"),
        (Integer) rs.getObject("number"),
        rs.getString("url"),
        rs.getString("merged_sha"),
        rs.getString("origin"),
        rs.getString("seen_ref"),
        rs.getString("seen_url"),
        rs.getString("note"),
        rs.getString("created_by"),
        rs.getTimestamp("created_at").toInstant());
  }
}
