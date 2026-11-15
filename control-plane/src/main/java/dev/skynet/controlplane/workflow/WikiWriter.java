package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.artifact.ArtifactService;
import dev.skynet.controlplane.memory.MemoryKind;
import dev.skynet.controlplane.memory.MemoryObservation;
import dev.skynet.controlplane.memory.MemoryQuery;
import dev.skynet.controlplane.memory.MemoryService;
import dev.skynet.controlplane.memory.MemoryTrust;
import dev.skynet.controlplane.memory.WikiOrigin;
import dev.skynet.controlplane.memory.WikiPages;
import dev.skynet.controlplane.memory.WikiSource;
import dev.skynet.controlplane.shared.Archived;
import dev.skynet.controlplane.shared.NotFoundException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Las páginas de la wiki que Skynet genera sin modelo (MV-C, docs/memoria.md «La wiki»), a partir
 * de lo que ya está en la base de datos:
 *
 * <ul>
 *   <li><b>Lo hecho</b>: los trabajos entregados (con una PR fusionada), con su PR, la fecha, el
 *       repositorio y los ficheros que tocó.
 *   <li><b>Errores conocidos</b>: los hallazgos de los jueces que se corrigieron, las lecciones
 *       aplicadas, las trampas y bugs confirmados de la memoria y los escapes del juez.
 *   <li><b>Decisiones y restricciones</b>: las de la memoria, confirmadas.
 *   <li><b>Índice</b>: qué páginas hay y qué resume cada una.
 * </ul>
 *
 * <p>Se regeneran al terminar cada ejecución y al fusionarse una PR, después de confirmar la
 * memoria de la ejecución ({@link MemoryConfirmations}), en su propia transacción: si falla, la
 * ejecución queda igual y queda un aviso en el log. Lo que no cambia no crea versión.
 */
@Component
class WikiWriter {

  private static final Logger log = LoggerFactory.getLogger(WikiWriter.class);

  static final String ACTOR = "skynet";
  static final String INDEX = "indice";
  static final String DONE = "lo-hecho";
  static final String ERRORS = "errores-conocidos";
  static final String DECISIONS = "decisiones";

  static final int MAX_DONE = 100;
  static final int MAX_FILES = 8;
  static final int MAX_ITEMS = 30;
  static final int MAX_TEXT = 300;

  private static final DateTimeFormatter DAY =
      DateTimeFormatter.ofPattern("d/M/yyyy").withZone(ZoneOffset.UTC);

  private final JdbcClient jdbc;
  private final WikiPages pages;
  private final MemoryService memory;
  private final ArtifactService artifacts;
  private final JsonMapper json = JsonMapper.builder().build();

  WikiWriter(JdbcClient jdbc, WikiPages pages, MemoryService memory, ArtifactService artifacts) {
    this.jdbc = jdbc;
    this.pages = pages;
    this.memory = memory;
    this.artifacts = artifacts;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  @Order(Ordered.LOWEST_PRECEDENCE)
  public void on(WorkflowRunFinished finished) {
    refreshQuietly(finished.workflowRunId());
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  @Order(Ordered.LOWEST_PRECEDENCE)
  public void on(PullRequestMerged merged) {
    refreshQuietly(merged.workflowRunId());
  }

  private void refreshQuietly(UUID runId) {
    try {
      jdbc.sql(
              "SELECT w.project_id FROM workflow_run r JOIN work_item w ON w.id = r.work_item_id"
                  + " WHERE r.id = ?")
          .param(runId)
          .query(UUID.class)
          .optional()
          .ifPresent(this::refresh);
    } catch (RuntimeException e) {
      log.warn("No se pudo actualizar la wiki tras la ejecución {}", runId, e);
    }
  }

  /** Regenera las páginas del proyecto; devuelve cuántas cambiaron. */
  @Transactional
  public int refresh(UUID projectId) {
    String project =
        jdbc.sql("SELECT name FROM project WHERE id = ?")
            .param(projectId)
            .query(String.class)
            .optional()
            .orElseThrow(() -> new NotFoundException("Proyecto", projectId));
    Map<UUID, String> repositories = new HashMap<>();
    jdbc.sql("SELECT id, name FROM repository WHERE project_id = ?")
        .param(projectId)
        .query((rs, n) -> Map.entry(rs.getObject("id", UUID.class), rs.getString("name")))
        .list()
        .forEach(e -> repositories.put(e.getKey(), e.getValue()));

    Generated done = done(projectId);
    Generated errors = errors(projectId, repositories);
    Generated decisions = decisions(projectId, repositories);
    int changed = 0;
    changed += write(projectId, DONE, "Lo hecho", done);
    changed += write(projectId, ERRORS, "Errores conocidos", errors);
    changed += write(projectId, DECISIONS, "Decisiones y restricciones", decisions);
    changed +=
        write(projectId, INDEX, "Índice", index(projectId, project, done, errors, decisions));
    return changed;
  }

  /** Una página generada: su markdown, sus fuentes y una línea para el índice. */
  record Generated(String body, List<WikiSource> sources, String summary) {}

  private int write(UUID projectId, String slug, String title, Generated page) {
    return pages
            .write(
                projectId,
                null,
                slug,
                title,
                page.body(),
                page.sources(),
                WikiOrigin.GENERATED,
                ACTOR)
            .changed()
        ? 1
        : 0;
  }

  // Lo hecho

  private record Delivered(
      String key,
      String title,
      UUID pullRequestId,
      Integer number,
      String url,
      Instant at,
      String repository,
      UUID runId) {}

  private Generated done(UUID projectId) {
    List<Delivered> delivered =
        jdbc.sql(
                """
                SELECT * FROM (
                  SELECT DISTINCT ON (w.id) w.key, w.title, p.id AS pr, p.number, p.url,
                         p.updated_at, rp.name AS repository, p.workflow_run_id
                  FROM work_item w
                  JOIN workflow_run r ON r.work_item_id = w.id
                  JOIN github_pull_request p ON p.workflow_run_id = r.id
                  JOIN repository rp ON rp.id = p.repository_id
                  WHERE w.project_id = ? AND p.status = 'MERGED'
                  ORDER BY w.id, p.updated_at DESC) d
                ORDER BY updated_at DESC, key LIMIT ?
                """)
            .params(projectId, MAX_DONE)
            .query(
                (rs, n) ->
                    new Delivered(
                        rs.getString("key"),
                        rs.getString("title"),
                        rs.getObject("pr", UUID.class),
                        (Integer) rs.getObject("number"),
                        rs.getString("url"),
                        rs.getTimestamp("updated_at").toInstant(),
                        rs.getString("repository"),
                        rs.getObject("workflow_run_id", UUID.class)))
            .list();
    StringBuilder body =
        new StringBuilder(
            "Los trabajos de este proyecto que ya se entregaron, es decir, con una PR fusionada,"
                + " del más reciente al más antiguo. Antes de empezar algo, mira si ya está aquí.\n");
    List<WikiSource> sources = new ArrayList<>();
    if (delivered.isEmpty()) {
      body.append("\nTodavía no se ha entregado nada.\n");
      return new Generated(body.toString(), sources, "todavía no se ha entregado nada");
    }
    body.append('\n');
    for (Delivered d : delivered) {
      String pr = d.number() == null ? "su PR" : "PR #" + d.number();
      body.append("- **")
          .append(d.key())
          .append("** «")
          .append(oneLine(d.title()))
          .append("»: ")
          .append(d.url() == null ? pr : "[" + pr + "](" + d.url() + ")")
          .append(", ")
          .append(DAY.format(d.at()))
          .append(", en ")
          .append(d.repository())
          .append('.');
      List<String> files = files(d.runId());
      if (!files.isEmpty()) {
        body.append(" Ficheros: ");
        List<String> shown = files.subList(0, Math.min(MAX_FILES, files.size()));
        body.append(String.join(", ", shown.stream().map(f -> "`" + f + "`").toList()));
        if (files.size() > shown.size()) {
          body.append(" y ").append(files.size() - shown.size()).append(" más");
        }
        body.append('.');
      }
      body.append('\n');
      sources.add(new WikiSource("pull_request", d.pullRequestId(), d.key() + " · " + pr, d.url()));
    }
    Delivered last = delivered.getFirst();
    String summary =
        count(delivered.size(), "trabajo entregado", "trabajos entregados")
            + (delivered.size() >= MAX_DONE ? " (los últimos)" : "")
            + "; el último, "
            + last.key()
            + " el "
            + DAY.format(last.at());
    return new Generated(body.toString(), sources, summary);
  }

  /**
   * Los ficheros del último {@code GIT_CHANGES} de la ejecución, o ninguno si no se pueden leer.
   */
  private List<String> files(UUID runId) {
    Optional<UUID> artifact =
        jdbc.sql(
                "SELECT a.id FROM artifact a JOIN agent_run g ON g.id = a.agent_run_id"
                    + " JOIN stage_run s ON s.id = g.stage_run_id WHERE s.workflow_run_id = ?"
                    + " AND a.type = 'GIT_CHANGES' AND a.verification_run_id IS NULL"
                    + " ORDER BY a.created_at DESC LIMIT 1")
            .param(runId)
            .query(UUID.class)
            .optional();
    if (artifact.isEmpty()) {
      return List.of();
    }
    try {
      Map<String, Object> changes =
          json.readValue(
              artifacts.bytes(artifacts.download(artifact.get())),
              new TypeReference<Map<String, Object>>() {});
      List<String> files = new ArrayList<>();
      if (changes.get("files") instanceof List<?> list) {
        for (Object file : list) {
          if (file instanceof Map<?, ?> map && map.get("path") instanceof String path) {
            files.add(path);
          }
        }
      }
      return files;
    } catch (RuntimeException e) {
      log.debug("No se pudieron leer los ficheros de la ejecución {}", runId, e);
      return List.of();
    }
  }

  // Errores conocidos

  private Generated errors(UUID projectId, Map<UUID, String> repositories) {
    StringBuilder body =
        new StringBuilder(
            "Lo que ya falló alguna vez en este proyecto y cómo se evitó. Si tu cambio toca lo mismo,"
                + " no lo repitas.\n");
    List<WikiSource> sources = new ArrayList<>();

    record Fixed(UUID id, String repository, String file, String severity, String text, int runs) {}
    List<Fixed> fixed =
        jdbc.sql(
                """
                SELECT (array_agg(f.id ORDER BY f.updated_at DESC))[1] AS id, rp.name,
                       (array_agg(f.file ORDER BY f.updated_at DESC))[1] AS file,
                       (array_agg(f.severity ORDER BY f.updated_at DESC))[1] AS severity,
                       (array_agg(f.text ORDER BY f.updated_at DESC))[1] AS text,
                       count(DISTINCT f.workflow_run_id) AS runs, max(f.updated_at) AS last
                FROM finding f JOIN repository rp ON rp.id = f.repository_id
                WHERE rp.project_id = ? AND f.outcome = 'corregido'
                GROUP BY COALESCE(f.first_seen_id, f.id), rp.name
                ORDER BY runs DESC, last DESC, id LIMIT ?
                """)
            .params(projectId, MAX_ITEMS)
            .query(
                (rs, n) ->
                    new Fixed(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("file"),
                        rs.getString("severity"),
                        rs.getString("text"),
                        rs.getInt("runs")))
            .list();
    body.append("\n## Fallos que señaló un juez y se corrigieron\n\n");
    if (fixed.isEmpty()) {
      body.append("Ninguno todavía.\n");
    }
    for (Fixed f : fixed) {
      body.append("- ")
          .append(f.file() == null ? f.repository() : "`" + f.file() + "` (" + f.repository() + ")")
          .append(", ")
          .append(f.severity())
          .append(": ")
          .append(clip(oneLine(f.text())));
      if (f.runs() > 1) {
        body.append(" (en ").append(f.runs()).append(" ejecuciones)");
      }
      body.append('\n');
      sources.add(new WikiSource("finding", f.id(), clip(oneLine(f.text())), null));
    }

    record Lesson(UUID id, String repository, String category, String text, int runs) {}
    List<Lesson> lessons =
        jdbc.sql(
                "SELECT l.id, rp.name, l.category, COALESCE(l.applied_text, l.suggested_text)"
                    + " AS text, l.runs FROM lesson l JOIN repository rp ON rp.id = l.repository_id"
                    + " WHERE l.project_id = ? AND l.status = 'aplicada' AND l.kind = 'prevenir'"
                    + " ORDER BY l.updated_at DESC, l.id LIMIT ?")
            .params(projectId, MAX_ITEMS)
            .query(
                (rs, n) ->
                    new Lesson(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("category"),
                        rs.getString("text"),
                        rs.getInt("runs")))
            .list();
    body.append("\n## Lecciones aplicadas\n\n");
    if (lessons.isEmpty()) {
      body.append("Ninguna todavía.\n");
    }
    for (Lesson l : lessons) {
      body.append("- ")
          .append(l.repository())
          .append(", ")
          .append(l.category())
          .append(": ")
          .append(clip(oneLine(l.text())))
          .append(" (salió en ")
          .append(l.runs())
          .append(" ejecuciones)\n");
      sources.add(new WikiSource("lesson", l.id(), l.repository() + " · " + l.category(), null));
    }

    List<MemoryObservation> pitfalls =
        confirmed(projectId, Set.of(MemoryKind.PITFALL, MemoryKind.BUGFIX));
    body.append("\n## Trampas y bugs de la memoria\n\n");
    if (pitfalls.isEmpty()) {
      body.append("Ninguno confirmado todavía.\n");
    }
    for (MemoryObservation o : pitfalls) {
      observation(body, sources, o, repositories, o.learned() != null ? o.learned() : o.what());
    }

    record Escape(
        UUID id,
        String key,
        String title,
        Integer number,
        String url,
        String repository,
        String note,
        Instant at) {}
    List<Escape> escapes =
        jdbc.sql(
                """
                SELECT e.id, w.key, w.title, p.number, p.url, rp.name, e.note, e.created_at
                FROM judge_escape e
                JOIN workflow_run r ON r.id = e.workflow_run_id
                JOIN work_item w ON w.id = r.work_item_id
                JOIN github_pull_request p ON p.id = e.pull_request_id
                JOIN repository rp ON rp.id = e.repository_id
                WHERE rp.project_id = ? ORDER BY e.created_at DESC, e.id LIMIT ?
                """)
            .params(projectId, MAX_ITEMS)
            .query(
                (rs, n) ->
                    new Escape(
                        rs.getObject("id", UUID.class),
                        rs.getString("key"),
                        rs.getString("title"),
                        (Integer) rs.getObject("number"),
                        rs.getString("url"),
                        rs.getString("name"),
                        rs.getString("note"),
                        rs.getTimestamp("created_at").toInstant()))
            .list();
    body.append("\n## Lo que se le escapó al juez\n\n");
    if (escapes.isEmpty()) {
      body.append("Nada todavía.\n");
    }
    for (Escape e : escapes) {
      String pr = e.number() == null ? "su PR" : "PR #" + e.number();
      body.append("- **")
          .append(e.key())
          .append("** «")
          .append(oneLine(e.title()))
          .append("» (")
          .append(e.url() == null ? pr : "[" + pr + "](" + e.url() + ")")
          .append(", ")
          .append(e.repository())
          .append(") se revirtió o falló tras fusionarse el ")
          .append(DAY.format(e.at()));
      if (e.note() != null && !e.note().isBlank()) {
        body.append(": ").append(clip(oneLine(e.note())));
      }
      body.append('\n');
      sources.add(new WikiSource("escape", e.id(), e.key() + " · " + pr, e.url()));
    }
    String summary =
        String.join(
            ", ",
            count(fixed.size(), "fallo corregido", "fallos corregidos"),
            count(lessons.size(), "lección", "lecciones"),
            count(pitfalls.size(), "trampa o bug", "trampas o bugs"),
            count(escapes.size(), "escape", "escapes"));
    return new Generated(body.toString(), sources, summary);
  }

  // Decisiones y restricciones

  private Generated decisions(UUID projectId, Map<UUID, String> repositories) {
    StringBuilder body =
        new StringBuilder(
            "Lo que se decidió en este proyecto y lo que no se puede hacer, según la memoria"
                + " confirmada. Las fijadas van primero.\n");
    List<WikiSource> sources = new ArrayList<>();
    List<MemoryObservation> constraints = confirmed(projectId, Set.of(MemoryKind.CONSTRAINT));
    body.append("\n## Restricciones\n\n");
    if (constraints.isEmpty()) {
      body.append("Ninguna confirmada todavía.\n");
    }
    for (MemoryObservation o : constraints) {
      observation(body, sources, o, repositories, o.what());
    }
    List<MemoryObservation> decisions = confirmed(projectId, Set.of(MemoryKind.DECISION));
    body.append("\n## Decisiones\n\n");
    if (decisions.isEmpty()) {
      body.append("Ninguna confirmada todavía.\n");
    }
    for (MemoryObservation o : decisions) {
      observation(body, sources, o, repositories, o.why() != null ? o.why() : o.what());
    }
    String summary =
        count(constraints.size(), "restricción", "restricciones")
            + ", "
            + count(decisions.size(), "decisión", "decisiones");
    return new Generated(body.toString(), sources, summary);
  }

  private List<MemoryObservation> confirmed(UUID projectId, Set<MemoryKind> kinds) {
    return memory
        .search(
            projectId,
            new MemoryQuery(
                MemoryQuery.Scope.ALL,
                null,
                kinds,
                null,
                null,
                Archived.EXCLUDE,
                null,
                0,
                MAX_ITEMS,
                Set.of(MemoryTrust.CONFIRMED)))
        .items();
  }

  private static void observation(
      StringBuilder body,
      List<WikiSource> sources,
      MemoryObservation o,
      Map<UUID, String> repositories,
      String detail) {
    body.append("- **").append(oneLine(o.title())).append("**");
    if (o.repositoryId() != null) {
      body.append(" (")
          .append(repositories.getOrDefault(o.repositoryId(), "repositorio"))
          .append(')');
    }
    if (o.pinned()) {
      body.append(", fijada");
    }
    if (detail != null && !detail.isBlank()) {
      body.append(": ").append(clip(oneLine(detail)));
    }
    body.append('\n');
    sources.add(new WikiSource("memory", o.id(), o.title(), null));
  }

  // Índice

  private Generated index(
      UUID projectId, String project, Generated done, Generated errors, Generated decisions) {
    StringBuilder body =
        new StringBuilder("La wiki de ")
            .append(project)
            .append(
                ": lo que Skynet sabe del proyecto. Estas páginas las genera Skynet sin modelo, a"
                    + " partir de lo que pasó, y se actualizan al terminar cada ejecución.\n\n");
    body.append("- [Lo hecho](")
        .append(DONE)
        .append("): ")
        .append(done.summary())
        .append(".\n- [Errores conocidos](")
        .append(ERRORS)
        .append("): ")
        .append(errors.summary())
        .append(".\n- [Decisiones y restricciones](")
        .append(DECISIONS)
        .append("): ")
        .append(decisions.summary())
        .append(".\n");
    // Las demás páginas (del curador o de una persona), sin las generadas.
    Set<String> generated = Set.of(INDEX, DONE, ERRORS, DECISIONS);
    List<WikiPages.Summary> others =
        pages.list(projectId).stream()
            .filter(p -> p.repositoryId() != null || !generated.contains(p.slug()))
            .toList();
    if (!others.isEmpty()) {
      body.append("\nOtras páginas:\n\n");
      for (WikiPages.Summary p : others) {
        body.append("- [").append(p.title()).append("](").append(p.slug()).append(")\n");
      }
    }
    return new Generated(body.toString(), List.of(), null);
  }

  private static String count(int n, String one, String many) {
    return n + " " + (n == 1 ? one : many);
  }

  private static String oneLine(String text) {
    return text == null ? "" : text.strip().replaceAll("\\s+", " ");
  }

  private static String clip(String text) {
    return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT - 1) + "…";
  }
}
