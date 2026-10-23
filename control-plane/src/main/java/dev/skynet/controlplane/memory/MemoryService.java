package dev.skynet.controlplane.memory;

import dev.skynet.controlplane.event.EventDraft;
import dev.skynet.controlplane.event.EventStore;
import dev.skynet.controlplane.project.CodeRepository;
import dev.skynet.controlplane.project.Project;
import dev.skynet.controlplane.project.ProjectService;
import dev.skynet.controlplane.shared.ConflictException;
import dev.skynet.controlplane.shared.InvalidRequestException;
import dev.skynet.controlplane.shared.NotFoundException;
import dev.skynet.controlplane.shared.PayloadRedactor;
import dev.skynet.controlplane.shared.TimeSource;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Guarda, versiona, busca y revisa la memoria de los proyectos. */
@Service
public class MemoryService {

  static final int MAX_TITLE = 200;
  static final int MAX_TOPIC_KEY = 200;
  static final int MAX_FIELD = 4_000;
  static final int MAX_TAGS = 20;
  static final int MAX_TAG = 50;
  static final int MAX_QUERY = 200;
  public static final int MAX_PAGE = 100;

  /** Minúsculas, dígitos y {@code . _ / -}: {@code arquitectura/auth}, {@code ci.flaky-tests}. */
  private static final Pattern TOPIC_KEY = Pattern.compile("[a-z0-9][a-z0-9._/-]*");

  private static final Pattern TAG = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}._/-]*");
  private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");
  private static final Pattern CONTROL_BUT_LINES = Pattern.compile("[\\p{Cntrl}&&[^\\n\\t]]");

  private static final String COLUMNS =
      "id, project_id, repository_id, kind, title, topic_key, what, why, location, learned, tags,"
          + " origin, source_workflow_run_id, source_agent_run_id, pinned, redacted, version,"
          + " created_at, updated_at, updated_by, archived_at";

  private final JdbcClient jdbc;
  private final ProjectService projects;
  private final EventStore events;
  private final PayloadRedactor redactor;
  private final TimeSource time;

  MemoryService(
      JdbcClient jdbc,
      ProjectService projects,
      EventStore events,
      PayloadRedactor redactor,
      TimeSource time) {
    this.jdbc = jdbc;
    this.projects = projects;
    this.events = events;
    this.redactor = redactor;
    this.time = time;
  }

  /**
   * Guarda una observación en el proyecto o en uno de sus repositorios. Si ya hay una viva con el
   * mismo {@code topicKey} en ese ámbito, se guarda como versión nueva de ella.
   */
  @Transactional
  public MemorySaved save(
      UUID projectId, UUID repositoryId, MemoryContent content, MemorySource source) {
    Objects.requireNonNull(source, "source");
    requireWritableScope(projectId, repositoryId);
    if (content != null
        && content.kind() == MemoryKind.RUN_LOG
        && source.origin() != MemoryOrigin.SYSTEM) {
      throw new InvalidRequestException("Las observaciones RUN_LOG solo las escribe Skynet");
    }
    Clean clean = clean(content);
    Instant now = time.now();
    if (clean.topicKey() != null) {
      lockTopic(projectId, repositoryId, clean.topicKey());
      Optional<MemoryObservation> existing = liveTopic(projectId, repositoryId, clean.topicKey());
      if (existing.isPresent()) {
        MemoryObservation current = existing.get();
        if (clean.sameAs(current)) {
          return new MemorySaved(current, false);
        }
        return new MemorySaved(newVersion(current, clean, source, now), false);
      }
    }
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO memory_observation (id, project_id, repository_id, kind, title, topic_key,"
                + " what, why, location, learned, tags, origin, source_workflow_run_id,"
                + " source_agent_run_id, redacted, version, created_at, updated_at, updated_by,"
                + " search) VALUES (:id, :project, :repository, :kind, :title, :topic, :what,"
                + " :why, :location, :learned, :tags, :origin, :workflowRun, :agentRun,"
                + " :redacted, 1, :now, :now, :actor, to_tsvector('simple', :document))")
        .param("id", id)
        .param("project", projectId)
        .param("repository", repositoryId)
        .param("kind", clean.kind().name())
        .param("title", clean.title())
        .param("topic", clean.topicKey())
        .param("what", clean.what())
        .param("why", clean.why())
        .param("location", clean.where())
        .param("learned", clean.learned())
        .param("tags", clean.tags().toArray(String[]::new))
        .param("origin", source.origin().name())
        .param("workflowRun", source.workflowRunId())
        .param("agentRun", source.agentRunId())
        .param("redacted", clean.redacted())
        .param("now", java.sql.Timestamp.from(now))
        .param("actor", source.actor())
        .param("document", clean.document())
        .update();
    MemoryObservation saved = get(id);
    recordRevision(saved, source.actor(), now);
    append(saved, "memory.created", source, now);
    return new MemorySaved(saved, true);
  }

  /** Cambia el contenido desde la web: crea una versión nueva, escrita por una persona. */
  @Transactional
  public MemoryObservation edit(UUID id, MemoryContent content, String actor) {
    MemoryObservation current = get(id);
    requireWritableScope(current.projectId(), current.repositoryId());
    Clean clean = clean(content);
    if (clean.kind() == MemoryKind.RUN_LOG && current.kind() != MemoryKind.RUN_LOG) {
      throw new InvalidRequestException("Las observaciones RUN_LOG solo las escribe Skynet");
    }
    if (clean.sameAs(current)) {
      return current;
    }
    if (clean.topicKey() != null && !clean.topicKey().equals(current.topicKey())) {
      lockTopic(current.projectId(), current.repositoryId(), clean.topicKey());
      requireFreeTopic(current, clean.topicKey());
    }
    // Una persona corrige la entrada: la procedencia (de qué ejecución salió) se conserva.
    MemorySource source =
        new MemorySource(
            MemoryOrigin.HUMAN, actor, current.sourceWorkflowRunId(), current.sourceAgentRunId());
    return newVersion(current, clean, source, time.now());
  }

  /** Archiva la observación: no se vuelve a devolver en las búsquedas ni a dar a los agentes. */
  @Transactional
  public MemoryObservation archive(UUID id, String actor) {
    MemoryObservation current = get(id);
    if (current.archivedAt() != null) {
      return current;
    }
    requireWritableScope(current.projectId(), current.repositoryId());
    Instant now = time.now();
    jdbc.sql(
            "UPDATE memory_observation SET archived_at = :now, pinned = false, updated_by = :actor"
                + " WHERE id = :id")
        .param("now", java.sql.Timestamp.from(now))
        .param("actor", actor)
        .param("id", id)
        .update();
    MemoryObservation archived = get(id);
    append(archived, "memory.archived", MemorySource.human(actor), now);
    return archived;
  }

  /** Restaura una observación archivada, si su tema no lo ocupa ya otra viva. */
  @Transactional
  public MemoryObservation restore(UUID id, String actor) {
    MemoryObservation current = get(id);
    if (current.archivedAt() == null) {
      return current;
    }
    requireWritableScope(current.projectId(), current.repositoryId());
    if (current.topicKey() != null) {
      lockTopic(current.projectId(), current.repositoryId(), current.topicKey());
      requireFreeTopic(current, current.topicKey());
    }
    Instant now = time.now();
    jdbc.sql("UPDATE memory_observation SET archived_at = NULL, updated_by = :actor WHERE id = :id")
        .param("actor", actor)
        .param("id", id)
        .update();
    MemoryObservation restored = get(id);
    append(restored, "memory.restored", MemorySource.human(actor), now);
    return restored;
  }

  /** Fija (o deja de fijar) la observación: las fijadas van siempre primero. */
  @Transactional
  public MemoryObservation pin(UUID id, boolean pinned, String actor) {
    MemoryObservation current = get(id);
    if (current.pinned() == pinned) {
      return current;
    }
    requireWritableScope(current.projectId(), current.repositoryId());
    if (pinned && current.archivedAt() != null) {
      throw new ConflictException("La observación está archivada: restáurala para fijarla");
    }
    Instant now = time.now();
    jdbc.sql("UPDATE memory_observation SET pinned = :pinned, updated_by = :actor WHERE id = :id")
        .param("pinned", pinned)
        .param("actor", actor)
        .param("id", id)
        .update();
    MemoryObservation changed = get(id);
    append(changed, pinned ? "memory.pinned" : "memory.unpinned", MemorySource.human(actor), now);
    return changed;
  }

  @Transactional(readOnly = true)
  public MemoryObservation get(UUID id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM memory_observation WHERE id = ?")
        .param(id)
        .query(MemoryService::observation)
        .optional()
        .orElseThrow(() -> new NotFoundException("Observación", id));
  }

  /** Todas las versiones, de la más reciente a la más antigua. */
  @Transactional(readOnly = true)
  public List<MemoryRevision> history(UUID id) {
    get(id);
    return jdbc.sql(
            "SELECT observation_id, version, kind, title, topic_key, what, why, location, learned,"
                + " tags, origin, source_agent_run_id, changed_by, changed_at"
                + " FROM memory_observation_revision WHERE observation_id = ?"
                + " ORDER BY version DESC")
        .param(id)
        .query(
            (rs, n) ->
                new MemoryRevision(
                    rs.getObject("observation_id", UUID.class),
                    rs.getInt("version"),
                    MemoryKind.valueOf(rs.getString("kind")),
                    rs.getString("title"),
                    rs.getString("topic_key"),
                    rs.getString("what"),
                    rs.getString("why"),
                    rs.getString("location"),
                    rs.getString("learned"),
                    strings(rs.getArray("tags")),
                    MemoryOrigin.valueOf(rs.getString("origin")),
                    rs.getObject("source_agent_run_id", UUID.class),
                    rs.getString("changed_by"),
                    rs.getTimestamp("changed_at").toInstant()))
        .list();
  }

  /**
   * Busca en el proyecto. Con texto, primero lo más pertinente; sin él, las fijadas y después lo
   * más reciente.
   */
  @Transactional(readOnly = true)
  public MemoryPage search(UUID projectId, MemoryQuery query) {
    projects.get(projectId);
    StringBuilder where = new StringBuilder(" WHERE project_id = :project");
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("project", projectId);
    switch (query.scope()) {
      case ALL -> {}
      case PROJECT -> where.append(" AND repository_id IS NULL");
      case REPOSITORY -> {
        where.append(" AND repository_id = :repository");
        params.put("repository", requireRepository(query));
      }
      case REPOSITORY_AND_PROJECT -> {
        where.append(" AND (repository_id = :repository OR repository_id IS NULL)");
        params.put("repository", requireRepository(query));
      }
    }
    if (!query.kinds().isEmpty()) {
      where.append(" AND kind IN (:kinds)");
      params.put("kinds", query.kinds().stream().map(Enum::name).toList());
    }
    if (query.origin() != null) {
      where.append(" AND origin = :origin");
      params.put("origin", query.origin().name());
    }
    if (query.pinned() != null) {
      where.append(" AND pinned = :pinned");
      params.put("pinned", query.pinned());
    }
    where.append(query.archived().sql("archived_at"));
    String text = query.text() == null ? null : query.text().strip();
    String order = " ORDER BY pinned DESC, updated_at DESC, id";
    if (text != null && !text.isEmpty()) {
      if (text.length() > MAX_QUERY) {
        throw new InvalidRequestException("La búsqueda admite hasta " + MAX_QUERY + " caracteres");
      }
      // La búsqueda de texto completo encuentra palabras enteras; el ILIKE, trozos del título o
      // del tema («auth» encuentra «arquitectura/auth-model»).
      where.append(
          " AND (search @@ websearch_to_tsquery('simple', :text)"
              + " OR title ILIKE :like ESCAPE '\\' OR topic_key ILIKE :like ESCAPE '\\')");
      params.put("text", text);
      params.put("like", "%" + escapeLike(text) + "%");
      order =
          " ORDER BY ts_rank(search, websearch_to_tsquery('simple', :text)) DESC, pinned DESC,"
              + " updated_at DESC, id";
    }
    int size = Math.clamp(query.size(), 1, MAX_PAGE);
    int page = Math.max(0, query.page());
    long total =
        jdbc.sql("SELECT count(*) FROM memory_observation" + where)
            .params(params)
            .query(Long.class)
            .single();
    List<MemoryObservation> items =
        jdbc.sql(
                "SELECT "
                    + COLUMNS
                    + " FROM memory_observation"
                    + where
                    + order
                    + " LIMIT :limit OFFSET :offset")
            .params(params)
            .param("limit", size)
            .param("offset", (long) page * size)
            .query(MemoryService::observation)
            .list();
    return new MemoryPage(items, page, size, total);
  }

  private static UUID requireRepository(MemoryQuery query) {
    if (query.repositoryId() == null) {
      throw new InvalidRequestException("Falta el repositorio de la búsqueda");
    }
    return query.repositoryId();
  }

  private MemoryObservation newVersion(
      MemoryObservation current, Clean clean, MemorySource source, Instant now) {
    int version = current.version() + 1;
    jdbc.sql(
            "UPDATE memory_observation SET kind = :kind, title = :title, topic_key = :topic,"
                + " what = :what, why = :why, location = :location, learned = :learned,"
                + " tags = :tags, origin = :origin, source_workflow_run_id = :workflowRun,"
                + " source_agent_run_id = :agentRun, redacted = :redacted, version = :version,"
                + " updated_at = :now, updated_by = :actor,"
                + " search = to_tsvector('simple', :document) WHERE id = :id")
        .param("kind", clean.kind().name())
        .param("title", clean.title())
        .param("topic", clean.topicKey())
        .param("what", clean.what())
        .param("why", clean.why())
        .param("location", clean.where())
        .param("learned", clean.learned())
        .param("tags", clean.tags().toArray(String[]::new))
        .param("origin", source.origin().name())
        .param("workflowRun", source.workflowRunId())
        .param("agentRun", source.agentRunId())
        .param("redacted", clean.redacted())
        .param("version", version)
        .param("now", java.sql.Timestamp.from(now))
        .param("actor", source.actor())
        .param("document", clean.document())
        .param("id", current.id())
        .update();
    MemoryObservation saved = get(current.id());
    recordRevision(saved, source.actor(), now);
    append(saved, "memory.updated", source, now);
    return saved;
  }

  private void recordRevision(MemoryObservation o, String actor, Instant now) {
    jdbc.sql(
            "INSERT INTO memory_observation_revision (observation_id, version, kind, title,"
                + " topic_key, what, why, location, learned, tags, origin, source_agent_run_id,"
                + " changed_by, changed_at) VALUES (:id, :version, :kind, :title, :topic, :what,"
                + " :why, :location, :learned, :tags, :origin, :agentRun, :actor, :now)")
        .param("id", o.id())
        .param("version", o.version())
        .param("kind", o.kind().name())
        .param("title", o.title())
        .param("topic", o.topicKey())
        .param("what", o.what())
        .param("why", o.why())
        .param("location", o.where())
        .param("learned", o.learned())
        .param("tags", o.tags().toArray(String[]::new))
        .param("origin", o.origin().name())
        .param("agentRun", o.sourceAgentRunId())
        .param("actor", actor)
        .param("now", java.sql.Timestamp.from(now))
        .update();
  }

  private void append(MemoryObservation o, String type, MemorySource source, Instant now) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("projectId", o.projectId());
    if (o.repositoryId() != null) {
      payload.put("repositoryId", o.repositoryId());
    }
    payload.put("kind", o.kind().name());
    payload.put("title", o.title());
    if (o.topicKey() != null) {
      payload.put("topicKey", o.topicKey());
    }
    payload.put("version", o.version());
    payload.put("origin", source.origin().name());
    if (source.actor() != null) {
      payload.put("actor", source.actor());
    }
    if (source.agentRunId() != null) {
      payload.put("agentRunId", source.agentRunId());
    }
    events.append(
        EventDraft.of("memory_observation", o.id(), type, source.workflowRunId(), payload, now));
  }

  /** El proyecto (y el repositorio, si lo hay) existen, casan y no están archivados. */
  private void requireWritableScope(UUID projectId, UUID repositoryId) {
    Project project = projects.get(projectId);
    projects.requireActive(project);
    if (repositoryId != null) {
      CodeRepository repository = projects.getRepository(repositoryId);
      if (!repository.getProjectId().equals(projectId)) {
        throw new InvalidRequestException(
            "El repositorio " + repository.getName() + " no es del proyecto " + project.getKey());
      }
      projects.requireActive(repository);
    }
  }

  /** Serializa los guardados del mismo tema hasta el commit. */
  private void lockTopic(UUID projectId, UUID repositoryId, String topicKey) {
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0)) IS NULL")
        .param("key", "memory:" + projectId + ":" + repositoryId + ":" + topicKey)
        .query(Boolean.class)
        .single();
  }

  private Optional<MemoryObservation> liveTopic(
      UUID projectId, UUID repositoryId, String topicKey) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM memory_observation WHERE project_id = :project AND topic_key = :topic"
                + " AND repository_id IS NOT DISTINCT FROM :repository AND archived_at IS NULL")
        .param("project", projectId)
        .param("topic", topicKey)
        .param("repository", repositoryId)
        .query(MemoryService::observation)
        .optional();
  }

  private void requireFreeTopic(MemoryObservation observation, String topicKey) {
    liveTopic(observation.projectId(), observation.repositoryId(), topicKey)
        .filter(other -> !other.id().equals(observation.id()))
        .ifPresent(
            other -> {
              throw new ConflictException(
                  "Ya hay otra observación viva con el tema " + topicKey + ": " + other.title());
            });
  }

  /** Contenido validado, normalizado y con los secretos enmascarados. */
  private record Clean(
      MemoryKind kind,
      String title,
      String topicKey,
      String what,
      String why,
      String where,
      String learned,
      List<String> tags,
      boolean redacted) {

    boolean sameAs(MemoryObservation o) {
      return kind == o.kind()
          && title.equals(o.title())
          && Objects.equals(topicKey, o.topicKey())
          && what.equals(o.what())
          && Objects.equals(why, o.why())
          && Objects.equals(where, o.where())
          && Objects.equals(learned, o.learned())
          && tags.equals(o.tags());
    }

    /** El texto que se indexa para la búsqueda; el tema, partido por sus separadores. */
    String document() {
      return Stream.of(
              title,
              topicKey == null ? null : topicKey.replaceAll("[._/-]", " "),
              what,
              why,
              where,
              learned,
              String.join(" ", tags))
          .filter(Objects::nonNull)
          .collect(Collectors.joining(" "));
    }
  }

  private Clean clean(MemoryContent content) {
    if (content == null) {
      throw new InvalidRequestException("Falta el contenido de la observación");
    }
    if (content.kind() == null) {
      throw new InvalidRequestException("Falta el tipo de la observación");
    }
    String title = text(content.title(), "title", MAX_TITLE, false);
    if (title == null) {
      throw new InvalidRequestException("Falta el título de la observación");
    }
    String what = text(content.what(), "what", MAX_FIELD, true);
    if (what == null) {
      throw new InvalidRequestException("Falta el qué (what) de la observación");
    }
    String why = text(content.why(), "why", MAX_FIELD, true);
    String where = text(content.where(), "where", MAX_FIELD, true);
    String learned = text(content.learned(), "learned", MAX_FIELD, true);

    String topicKey = blankToNull(content.topicKey());
    if (topicKey != null) {
      topicKey = topicKey.toLowerCase(Locale.ROOT);
      if (topicKey.length() > MAX_TOPIC_KEY || !TOPIC_KEY.matcher(topicKey).matches()) {
        throw new InvalidRequestException(
            "topicKey no válido: hasta "
                + MAX_TOPIC_KEY
                + " caracteres en minúscula, dígitos y . _ / -, como arquitectura/auth");
      }
    }

    if (content.tags().size() > MAX_TAGS) {
      throw new InvalidRequestException("Una observación admite hasta " + MAX_TAGS + " etiquetas");
    }
    LinkedHashSet<String> tags = new LinkedHashSet<>();
    for (String tag : content.tags()) {
      String t = blankToNull(tag);
      if (t == null) {
        continue;
      }
      t = t.toLowerCase(Locale.ROOT);
      if (t.length() > MAX_TAG || !TAG.matcher(t).matches()) {
        throw new InvalidRequestException(
            "Etiqueta no válida: "
                + abbreviate(t)
                + " (hasta "
                + MAX_TAG
                + " caracteres, sin"
                + " espacios)");
      }
      tags.add(t);
    }

    String[] raw = {title, what, why, where, learned};
    String[] masked = Arrays.stream(raw).map(redactor::redact).toArray(String[]::new);
    boolean redacted = !Arrays.equals(raw, masked);
    return new Clean(
        content.kind(),
        masked[0],
        topicKey,
        masked[1],
        masked[2],
        masked[3],
        masked[4],
        List.copyOf(tags),
        redacted);
  }

  /** Texto recortado; {@code null} si viene vacío. Los campos largos admiten saltos de línea. */
  private static String text(String value, String field, int max, boolean multiline) {
    String v = blankToNull(value);
    if (v == null) {
      return null;
    }
    if (v.length() > max) {
      throw new InvalidRequestException(field + " admite hasta " + max + " caracteres");
    }
    if ((multiline ? CONTROL_BUT_LINES : CONTROL).matcher(v).find()) {
      throw new InvalidRequestException(field + " no admite caracteres de control");
    }
    return v;
  }

  private static String blankToNull(String value) {
    if (value == null) {
      return null;
    }
    String v = value.strip();
    return v.isEmpty() ? null : v;
  }

  private static String abbreviate(String value) {
    return value.length() <= 60 ? value : value.substring(0, 60) + "…";
  }

  private static String escapeLike(String text) {
    return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private static MemoryObservation observation(ResultSet rs, int row) throws SQLException {
    java.sql.Timestamp archived = rs.getTimestamp("archived_at");
    return new MemoryObservation(
        rs.getObject("id", UUID.class),
        rs.getObject("project_id", UUID.class),
        rs.getObject("repository_id", UUID.class),
        MemoryKind.valueOf(rs.getString("kind")),
        rs.getString("title"),
        rs.getString("topic_key"),
        rs.getString("what"),
        rs.getString("why"),
        rs.getString("location"),
        rs.getString("learned"),
        strings(rs.getArray("tags")),
        MemoryOrigin.valueOf(rs.getString("origin")),
        rs.getObject("source_workflow_run_id", UUID.class),
        rs.getObject("source_agent_run_id", UUID.class),
        rs.getBoolean("pinned"),
        rs.getBoolean("redacted"),
        rs.getInt("version"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(),
        rs.getString("updated_by"),
        archived == null ? null : archived.toInstant());
  }

  private static List<String> strings(Array array) throws SQLException {
    if (array == null) {
      return List.of();
    }
    List<String> result = new ArrayList<>();
    for (Object value : (Object[]) array.getArray()) {
      result.add((String) value);
    }
    return List.copyOf(result);
  }
}
