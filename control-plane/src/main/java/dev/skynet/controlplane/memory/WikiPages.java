package dev.skynet.controlplane.memory;

import dev.skynet.controlplane.project.ProjectService;
import dev.skynet.controlplane.shared.InvalidRequestException;
import dev.skynet.controlplane.shared.NotFoundException;
import dev.skynet.controlplane.shared.TimeSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Las páginas de la wiki del proyecto (MV-C, docs/memoria.md «La wiki»): markdown con sus fuentes,
 * de todo el proyecto o de un repositorio, con versiones. Las genera Skynet sin modelo ({@link
 * WikiOrigin#GENERATED}); más adelante, también el curador y las personas.
 *
 * <p>Escribir lo mismo que ya hay no crea otra versión: así, regenerar las páginas tras cada
 * ejecución solo deja historial cuando algo cambia.
 */
@Service
public class WikiPages {

  /** El tope de una página, en caracteres. */
  public static final int MAX_BODY = 60_000;

  private static final Pattern SLUG = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
  private static final String COLUMNS =
      "id, project_id, repository_id, slug, title, body, CAST(sources AS text) AS sources, origin,"
          + " version, created_at, updated_at, updated_by";

  /** Una página, en su versión actual. */
  public record Page(
      UUID id,
      UUID projectId,
      UUID repositoryId,
      String slug,
      String title,
      String body,
      List<WikiSource> sources,
      WikiOrigin origin,
      int version,
      Instant createdAt,
      Instant updatedAt,
      String updatedBy) {}

  /** Una página sin su contenido, para el índice. */
  public record Summary(
      UUID id,
      UUID repositoryId,
      String slug,
      String title,
      WikiOrigin origin,
      int version,
      int sources,
      Instant updatedAt,
      String updatedBy) {}

  /** Una versión de una página. */
  public record Revision(
      int version,
      String title,
      String body,
      List<WikiSource> sources,
      WikiOrigin origin,
      Instant createdAt,
      String createdBy) {}

  private final JdbcClient jdbc;
  private final ProjectService projects;
  private final TimeSource time;
  private final JsonMapper json = JsonMapper.builder().build();

  WikiPages(JdbcClient jdbc, ProjectService projects, TimeSource time) {
    this.jdbc = jdbc;
    this.projects = projects;
    this.time = time;
  }

  /**
   * Escribe la página {@code slug} del proyecto (o de {@code repositoryId}). Si el título y el
   * contenido son los de ahora, no hace nada.
   *
   * @return la página, y si se escribió una versión nueva
   */
  @Transactional
  public Written write(
      UUID projectId,
      UUID repositoryId,
      String slug,
      String title,
      String body,
      List<WikiSource> sources,
      WikiOrigin origin,
      String actor) {
    if (slug == null || !SLUG.matcher(slug).matches()) {
      throw new InvalidRequestException("El nombre de la página no vale: " + slug);
    }
    if (title == null || title.isBlank() || body == null) {
      throw new InvalidRequestException("La página necesita título y contenido");
    }
    String text = body.length() <= MAX_BODY ? body : body.substring(0, MAX_BODY - 1) + "…";
    String sourcesJson = json.writeValueAsString(sources == null ? List.of() : sources);
    // Las páginas de un proyecto se escriben de una en una: dos ejecuciones que terminan a la vez
    // no se pisan la versión.
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(?))")
        .param("wiki:" + projectId)
        .query((rs, n) -> 1)
        .list();
    Instant now = time.now();
    Optional<Page> current = find(projectId, repositoryId, slug);
    if (current.isPresent()
        && current.get().title().equals(title)
        && current.get().body().equals(text)) {
      return new Written(current.get(), false);
    }
    UUID id;
    int version;
    if (current.isEmpty()) {
      id = UUID.randomUUID();
      version = 1;
      jdbc.sql(
              "INSERT INTO wiki_page (id, project_id, repository_id, slug, title, body, sources,"
                  + " origin, version, created_at, updated_at, updated_by)"
                  + " VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)")
          .params(
              id,
              projectId,
              repositoryId,
              slug,
              title,
              text,
              sourcesJson,
              origin.name(),
              version,
              Timestamp.from(now),
              Timestamp.from(now),
              actor)
          .update();
    } else {
      id = current.get().id();
      version = current.get().version() + 1;
      jdbc.sql(
              "UPDATE wiki_page SET title = ?, body = ?, sources = ?::jsonb, origin = ?,"
                  + " version = ?, updated_at = ?, updated_by = ? WHERE id = ?")
          .params(title, text, sourcesJson, origin.name(), version, Timestamp.from(now), actor, id)
          .update();
    }
    jdbc.sql(
            "INSERT INTO wiki_page_revision (id, page_id, version, title, body, sources, origin,"
                + " created_at, created_by) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)")
        .params(
            UUID.randomUUID(),
            id,
            version,
            title,
            text,
            sourcesJson,
            origin.name(),
            Timestamp.from(now),
            actor)
        .update();
    return new Written(find(projectId, repositoryId, slug).orElseThrow(), true);
  }

  /** Lo que dejó {@link #write}: la página y si cambió. */
  public record Written(Page page, boolean changed) {}

  /** Las páginas del proyecto, primero las de todo el proyecto y luego las de cada repositorio. */
  @Transactional(readOnly = true)
  public List<Summary> list(UUID projectId) {
    projects.get(projectId);
    return jdbc.sql(
            "SELECT id, repository_id, slug, title, origin, version,"
                + " jsonb_array_length(sources) AS sources, updated_at, updated_by FROM wiki_page"
                + " WHERE project_id = ? ORDER BY repository_id NULLS FIRST, slug")
        .param(projectId)
        .query(
            (rs, n) ->
                new Summary(
                    rs.getObject("id", UUID.class),
                    rs.getObject("repository_id", UUID.class),
                    rs.getString("slug"),
                    rs.getString("title"),
                    WikiOrigin.valueOf(rs.getString("origin")),
                    rs.getInt("version"),
                    rs.getInt("sources"),
                    rs.getTimestamp("updated_at").toInstant(),
                    rs.getString("updated_by")))
        .list();
  }

  /** La página {@code slug} del proyecto (o de {@code repositoryId}). */
  @Transactional(readOnly = true)
  public Page get(UUID projectId, UUID repositoryId, String slug) {
    projects.get(projectId);
    return find(projectId, repositoryId, slug)
        .orElseThrow(() -> new NotFoundException("Página de la wiki", slug));
  }

  /** Las versiones de una página, de la más reciente a la más antigua. */
  @Transactional(readOnly = true)
  public List<Revision> history(UUID projectId, UUID repositoryId, String slug) {
    Page page = get(projectId, repositoryId, slug);
    return jdbc.sql(
            "SELECT version, title, body, CAST(sources AS text) AS sources, origin, created_at,"
                + " created_by FROM wiki_page_revision WHERE page_id = ? ORDER BY version DESC")
        .param(page.id())
        .query(this::revision)
        .list();
  }

  /** La versión {@code version} de una página. */
  @Transactional(readOnly = true)
  public Revision revision(UUID projectId, UUID repositoryId, String slug, int version) {
    Page page = get(projectId, repositoryId, slug);
    return jdbc.sql(
            "SELECT version, title, body, CAST(sources AS text) AS sources, origin, created_at,"
                + " created_by FROM wiki_page_revision WHERE page_id = ? AND version = ?")
        .params(page.id(), version)
        .query(this::revision)
        .optional()
        .orElseThrow(() -> new NotFoundException("Versión de la página " + slug, version));
  }

  private Optional<Page> find(UUID projectId, UUID repositoryId, String slug) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM wiki_page WHERE project_id = ? AND repository_id IS NOT DISTINCT FROM ?"
                + " AND slug = ?")
        .params(projectId, repositoryId, slug)
        .query(this::page)
        .optional();
  }

  private Page page(ResultSet rs, int n) throws SQLException {
    return new Page(
        rs.getObject("id", UUID.class),
        rs.getObject("project_id", UUID.class),
        rs.getObject("repository_id", UUID.class),
        rs.getString("slug"),
        rs.getString("title"),
        rs.getString("body"),
        sources(rs.getString("sources")),
        WikiOrigin.valueOf(rs.getString("origin")),
        rs.getInt("version"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(),
        rs.getString("updated_by"));
  }

  private Revision revision(ResultSet rs, int n) throws SQLException {
    return new Revision(
        rs.getInt("version"),
        rs.getString("title"),
        rs.getString("body"),
        sources(rs.getString("sources")),
        WikiOrigin.valueOf(rs.getString("origin")),
        rs.getTimestamp("created_at").toInstant(),
        rs.getString("created_by"));
  }

  private List<WikiSource> sources(String text) {
    return Objects.requireNonNullElse(
        json.readValue(text, new TypeReference<List<WikiSource>>() {}), List.of());
  }
}
