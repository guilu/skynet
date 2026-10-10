package dev.skynet.controlplane.definition;

import dev.skynet.controlplane.definition.DefinitionParser.Expectations;
import dev.skynet.controlplane.definition.DefinitionParser.Parsed;
import dev.skynet.controlplane.event.EventDraft;
import dev.skynet.controlplane.event.EventStore;
import dev.skynet.controlplane.shared.ArchiveState;
import dev.skynet.controlplane.shared.Archived;
import dev.skynet.controlplane.shared.Archiving;
import dev.skynet.controlplane.shared.ConflictException;
import dev.skynet.controlplane.shared.NotFoundException;
import dev.skynet.controlplane.shared.TimeSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Workflows y sus versiones: crear, editar el borrador, publicar, archivar y leerlos.
 *
 * <p>Se registran como eventos la creación, la publicación, el descarte de un borrador y el
 * archivado; guardar un borrador no, porque solo cambia algo que aún no usa nadie.
 */
@Service
public class WorkflowDefinitions {

  /** Workflow implícito de la Fase 1, el que se lanza si no se elige otro. */
  public static final String ADHOC = "adhoc";

  private final JdbcClient jdbc;
  private final EventStore events;
  private final TimeSource time;

  WorkflowDefinitions(JdbcClient jdbc, EventStore events, TimeSource time) {
    this.jdbc = jdbc;
    this.events = events;
    this.time = time;
  }

  // ---- Lectura --------------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public List<WorkflowSummary> list(Archived archived) {
    return jdbc
        .sql("SELECT * FROM workflow WHERE true" + archived.sql("archived_at") + " ORDER BY key")
        .query(this::mapWorkflow)
        .list()
        .stream()
        .map(this::summary)
        .toList();
  }

  @Transactional(readOnly = true)
  public WorkflowView workflow(String key) {
    WorkflowRow workflow = workflowRow(key);
    return new WorkflowView(summary(workflow), versions(key));
  }

  @Transactional(readOnly = true)
  public DefinitionDetail definition(UUID id) {
    DefinitionRow row = definitionRow(id);
    Parsed parsed = parse(row);
    return detail(row, parsed);
  }

  /**
   * Valida un YAML sin guardarlo. Con {@code key} y {@code version}, como si se guardara en esa
   * versión de ese workflow (el editor de un borrador): avisa si el id cambia o la versión no es la
   * suya.
   */
  public ValidationView validate(String sourceYaml, String key, Integer version) {
    Parsed parsed = DefinitionParser.parse(sourceYaml, new Expectations(key, version));
    return new ValidationView(parsed.key(), parsed.validation(), parsed.definition());
  }

  // ---- Cambios --------------------------------------------------------------------------------

  /** Crea un workflow nuevo con su YAML como borrador de la versión 1. */
  @Transactional
  public DefinitionDetail create(String sourceYaml) {
    Parsed parsed = DefinitionParser.parse(sourceYaml, new Expectations(null, 1));
    if (parsed.key() == null) {
      throw new DefinitionRejectedException(
          "No se puede crear el workflow sin un `id` válido",
          parsed.validation().problems(),
          false);
    }
    boolean exists =
        jdbc.sql("SELECT count(*) FROM workflow WHERE key = ?")
                .param(parsed.key())
                .query(Integer.class)
                .single()
            > 0;
    if (exists) {
      throw new ConflictException(
          "Ya existe el workflow "
              + parsed.key()
              + ": edita su borrador o crea uno nuevo desde él");
    }
    Instant now = time.now();
    UUID workflowId = UUID.randomUUID();
    jdbc.sql("INSERT INTO workflow (id, key, created_at) VALUES (?, ?, ?)")
        .params(workflowId, parsed.key(), Timestamp.from(now))
        .update();
    UUID id = insertDraft(parsed.key(), 1, sourceYaml, parsed, now);
    events.append(
        EventDraft.of(
            "workflow_definition",
            id,
            "workflow.definition.created",
            null,
            Map.of("key", parsed.key(), "version", 1),
            now));
    return definition(id);
  }

  /**
   * Abre el borrador de la versión siguiente con el YAML de la última publicada. Si ya hay un
   * borrador, devuelve ese.
   */
  @Transactional
  public DefinitionDetail draft(String key) {
    WorkflowRow workflow = workflowRow(key);
    requireActive(workflow);
    List<VersionView> versions = versions(key);
    VersionView existing =
        versions.stream()
            .filter(v -> v.status() != DefinitionStatus.PUBLISHED)
            .findFirst()
            .orElse(null);
    if (existing != null) {
      return definition(existing.id());
    }
    VersionView latest = versions.getFirst();
    DefinitionRow published = definitionRow(latest.id());
    int version = latest.version() + 1;
    String source = DefinitionParser.withVersion(published.sourceYaml(), version);
    Parsed parsed = DefinitionParser.parse(source, new Expectations(key, version));
    Instant now = time.now();
    UUID id = insertDraft(key, version, source, parsed, now);
    events.append(
        EventDraft.of(
            "workflow_definition",
            id,
            "workflow.definition.created",
            null,
            Map.of("key", key, "version", version, "from", latest.id()),
            now));
    return definition(id);
  }

  /** Guarda el YAML de un borrador, aunque tenga errores. */
  @Transactional
  public DefinitionDetail save(UUID id, String sourceYaml, long revision) {
    DefinitionRow row = draftRow(id, revision);
    requireActive(workflowRow(row.key()));
    Parsed parsed = DefinitionParser.parse(sourceYaml, new Expectations(row.key(), row.version()));
    if (sourceYaml.length() > DefinitionParser.MAX_SOURCE) {
      throw new DefinitionRejectedException(
          "El YAML es demasiado grande", parsed.validation().problems(), false);
    }
    WorkflowDefinition definition = parsed.definition();
    jdbc.sql(
            "UPDATE workflow_definition SET source_yaml = ?, status = ?, name = ?, description = ?,"
                + " updated_at = ?, revision = revision + 1 WHERE id = ? AND revision = ?")
        .params(
            sourceYaml,
            DefinitionStatus.ofDraft(parsed.validation()).name(),
            definition == null ? null : definition.name(),
            definition == null ? null : definition.description(),
            Timestamp.from(time.now()),
            id,
            revision)
        .update();
    return definition(id);
  }

  /** Publica un borrador: queda congelado como la versión que se lanza a partir de ahora. */
  @Transactional
  public DefinitionDetail publish(UUID id, long revision) {
    DefinitionRow row = draftRow(id, revision);
    requireActive(workflowRow(row.key()));
    Parsed parsed = parse(row);
    if (!parsed.validation().publishable()) {
      throw new DefinitionRejectedException(
          parsed.validation().valid()
              ? "Todavía no se puede publicar: usa algo que el motor aún no ejecuta"
              : "No se puede publicar un borrador con errores",
          parsed.validation().problems(),
          true);
    }
    Instant now = time.now();
    jdbc.sql(
            "UPDATE workflow_definition SET status = 'PUBLISHED', published_at = ?, updated_at = ?,"
                + " revision = revision + 1 WHERE id = ?")
        .params(Timestamp.from(now), Timestamp.from(now), id)
        .update();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("key", row.key());
    payload.put("version", row.version());
    payload.put("sha256", sha256(row.sourceYaml()));
    events.append(
        EventDraft.of(
            "workflow_definition", id, "workflow.definition.published", null, payload, now));
    return definition(id);
  }

  /**
   * Descarta un borrador. Si era la única versión, el workflow desaparece con él: nunca llegó a
   * publicarse.
   */
  @Transactional
  public void discard(UUID id) {
    DefinitionRow row = definitionRow(id);
    if (row.status() == DefinitionStatus.PUBLISHED) {
      throw new ConflictException(
          "La versión " + row.version() + " de " + row.key() + " está publicada y no se borra");
    }
    jdbc.sql("DELETE FROM workflow_definition WHERE id = ?").param(id).update();
    int remaining =
        jdbc.sql("SELECT count(*) FROM workflow_definition WHERE key = ?")
            .param(row.key())
            .query(Integer.class)
            .single();
    if (remaining == 0) {
      jdbc.sql("DELETE FROM workflow WHERE key = ?").param(row.key()).update();
    }
    events.append(
        EventDraft.of(
            "workflow_definition",
            id,
            "workflow.definition.discarded",
            null,
            Map.of("key", row.key(), "version", row.version()),
            time.now()));
  }

  /** Archiva un workflow: sale del lanzador y de la lista, y no se edita hasta restaurarlo. */
  @Transactional
  public ArchiveState archive(String key) {
    WorkflowRow workflow = workflowRow(key);
    if (ADHOC.equals(key)) {
      throw new ConflictException("adhoc es el workflow por defecto y no se archiva");
    }
    if (workflow.archivedAt() != null) {
      return new ArchiveState(workflow.id(), workflow.archivedAt());
    }
    Instant now = time.now();
    Archiving.set(jdbc, "workflow", workflow.id(), now);
    events.append(
        EventDraft.of(
            "workflow", workflow.id(), "workflow.archived", null, Map.of("key", key), now));
    return new ArchiveState(workflow.id(), now);
  }

  @Transactional
  public ArchiveState restore(String key) {
    WorkflowRow workflow = workflowRow(key);
    if (workflow.archivedAt() == null) {
      return new ArchiveState(workflow.id(), null);
    }
    Archiving.set(jdbc, "workflow", workflow.id(), null);
    events.append(
        EventDraft.of(
            "workflow", workflow.id(), "workflow.restored", null, Map.of("key", key), time.now()));
    return new ArchiveState(workflow.id(), null);
  }

  // ---- Apoyo ----------------------------------------------------------------------------------

  private UUID insertDraft(String key, int version, String source, Parsed parsed, Instant now) {
    UUID id = UUID.randomUUID();
    WorkflowDefinition definition = parsed.definition();
    jdbc.sql(
            "INSERT INTO workflow_definition (id, key, version, source_yaml, status, name,"
                + " description, created_at, updated_at, revision)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)")
        .params(
            id,
            key,
            version,
            source,
            DefinitionStatus.ofDraft(parsed.validation()).name(),
            definition == null ? null : definition.name(),
            definition == null ? null : definition.description(),
            Timestamp.from(now),
            Timestamp.from(now))
        .update();
    return id;
  }

  private DefinitionRow draftRow(UUID id, long revision) {
    DefinitionRow row = definitionRow(id);
    if (row.status() == DefinitionStatus.PUBLISHED) {
      throw new ConflictException(
          "La versión "
              + row.version()
              + " de "
              + row.key()
              + " ya está publicada: abre un borrador nuevo para cambiarla");
    }
    if (row.revision() != revision) {
      throw new ConflictException(
          "El borrador ha cambiado desde que lo abriste; recárgalo antes de guardar");
    }
    return row;
  }

  private static void requireActive(WorkflowRow workflow) {
    if (workflow.archivedAt() != null) {
      throw new ConflictException(
          "El workflow " + workflow.key() + " está archivado: restáuralo para cambiarlo");
    }
  }

  private static Parsed parse(DefinitionRow row) {
    return DefinitionParser.parse(
        row.sourceYaml(),
        new Expectations(
            row.key(), row.status() == DefinitionStatus.PUBLISHED ? null : row.version()));
  }

  private DefinitionDetail detail(DefinitionRow row, Parsed parsed) {
    WorkflowRow workflow = workflowRow(row.key());
    return new DefinitionDetail(
        row.id(),
        workflow.id(),
        row.key(),
        row.version(),
        row.status(),
        row.sourceYaml(),
        row.revision(),
        row.createdAt(),
        row.updatedAt(),
        row.publishedAt(),
        workflow.archivedAt(),
        parsed.validation(),
        parsed.definition());
  }

  private WorkflowSummary summary(WorkflowRow workflow) {
    List<VersionView> versions = versions(workflow.key());
    VersionView published =
        versions.stream()
            .filter(v -> v.status() == DefinitionStatus.PUBLISHED)
            .findFirst()
            .orElse(null);
    VersionView draft =
        versions.stream()
            .filter(v -> v.status() != DefinitionStatus.PUBLISHED)
            .findFirst()
            .orElse(null);
    VersionView shown = published != null ? published : draft;
    Map<String, String> texts =
        shown == null
            ? Map.of()
            : jdbc.sql("SELECT name, description FROM workflow_definition WHERE id = ?")
                .param(shown.id())
                .query(
                    (rs, n) -> {
                      Map<String, String> m = new java.util.HashMap<>();
                      m.put("name", rs.getString("name"));
                      m.put("description", rs.getString("description"));
                      return m;
                    })
                .single();
    return new WorkflowSummary(
        workflow.id(),
        workflow.key(),
        texts.get("name"),
        texts.get("description"),
        published,
        draft,
        workflow.createdAt(),
        workflow.archivedAt());
  }

  private List<VersionView> versions(String key) {
    return jdbc.sql(
            "SELECT id, version, status, created_at, updated_at, published_at"
                + " FROM workflow_definition WHERE key = ? ORDER BY version DESC")
        .param(key)
        .query(
            (rs, n) ->
                new VersionView(
                    rs.getObject("id", UUID.class),
                    rs.getInt("version"),
                    DefinitionStatus.valueOf(rs.getString("status")),
                    instant(rs, "created_at"),
                    instant(rs, "updated_at"),
                    instant(rs, "published_at")))
        .list();
  }

  private record WorkflowRow(UUID id, String key, Instant createdAt, Instant archivedAt) {}

  private WorkflowRow mapWorkflow(ResultSet rs, int row) throws SQLException {
    return new WorkflowRow(
        rs.getObject("id", UUID.class),
        rs.getString("key"),
        instant(rs, "created_at"),
        instant(rs, "archived_at"));
  }

  private WorkflowRow workflowRow(String key) {
    return jdbc.sql("SELECT * FROM workflow WHERE key = ?")
        .param(key)
        .query(this::mapWorkflow)
        .optional()
        .orElseThrow(() -> new NotFoundException("Workflow", key));
  }

  private record DefinitionRow(
      UUID id,
      String key,
      int version,
      DefinitionStatus status,
      String sourceYaml,
      long revision,
      Instant createdAt,
      Instant updatedAt,
      Instant publishedAt) {}

  private DefinitionRow definitionRow(UUID id) {
    return jdbc.sql(
            "SELECT id, key, version, status, source_yaml, revision, created_at, updated_at,"
                + " published_at FROM workflow_definition WHERE id = ?")
        .param(id)
        .query(
            (rs, n) ->
                new DefinitionRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("key"),
                    rs.getInt("version"),
                    DefinitionStatus.valueOf(rs.getString("status")),
                    rs.getString("source_yaml"),
                    rs.getLong("revision"),
                    instant(rs, "created_at"),
                    instant(rs, "updated_at"),
                    instant(rs, "published_at")))
        .optional()
        .orElseThrow(() -> new NotFoundException("Versión de workflow", id));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static String sha256(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
