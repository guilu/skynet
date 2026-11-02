package dev.skynet.controlplane.trigger;

import dev.skynet.controlplane.project.ProjectService;
import dev.skynet.controlplane.shared.ConflictException;
import dev.skynet.controlplane.shared.InvalidRequestException;
import dev.skynet.controlplane.shared.NotFoundException;
import dev.skynet.controlplane.shared.TimeSource;
import dev.skynet.controlplane.workitem.WorkItemType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Las reglas de los disparadores de cada repositorio: qué se puede lanzar desde fuera y con qué.
 */
@Service
class TriggerRules {

  static final String PREFIX = "skynet:";

  /** La etiqueta de los repositorios de órdenes. */
  static final String ORDER_LABEL = "skynet:run";

  /** Las etiquetas con las que Skynet cuenta cómo va: no lanzan nada. */
  static final String RUNNING = "skynet:en-curso";

  static final String DONE = "skynet:hecho";
  static final String FAILED = "skynet:fallido";

  static final Set<String> RESERVED = Set.of(ORDER_LABEL, RUNNING, DONE, FAILED);

  private static final Pattern WORKFLOW_KEY = Pattern.compile("[a-z0-9][a-z0-9-]{0,99}");
  private static final Pattern LABEL = Pattern.compile("skynet:[a-z0-9][a-z0-9:._-]{0,40}");

  enum Kind {
    ISSUE
  }

  /** Una regla, tal como la guarda y la enseña la API. */
  record Rule(
      UUID id,
      UUID repositoryId,
      Kind kind,
      String workflowKey,
      String label,
      WorkItemType workItemType,
      Map<String, Object> inputs,
      Map<String, InputPolicy.Rule> inputPolicy,
      boolean enabled,
      Instant createdAt,
      Instant updatedAt) {}

  /** Lo que llega para crear o cambiar una regla; lo que llegue vacío toma su valor por defecto. */
  record Draft(
      Kind kind,
      String workflowKey,
      String label,
      WorkItemType workItemType,
      Map<String, Object> inputs,
      Map<String, InputPolicy.Rule> inputPolicy,
      Boolean enabled) {}

  private static final TypeReference<LinkedHashMap<String, Object>> INPUTS =
      new TypeReference<>() {};
  private static final TypeReference<LinkedHashMap<String, InputPolicy.Rule>> POLICY =
      new TypeReference<>() {};

  private final JdbcClient jdbc;
  private final ObjectMapper json;
  private final TimeSource time;
  private final ProjectService projects;

  TriggerRules(JdbcClient jdbc, ObjectMapper json, TimeSource time, ProjectService projects) {
    this.jdbc = jdbc;
    this.json = json;
    this.time = time;
    this.projects = projects;
  }

  @Transactional(readOnly = true)
  List<Rule> ofRepository(UUID repositoryId) {
    projects.getRepository(repositoryId);
    return jdbc.sql("SELECT * FROM trigger_rule WHERE repository_id = ? ORDER BY created_at")
        .param(repositoryId)
        .query(this::map)
        .list();
  }

  @Transactional(readOnly = true)
  Rule get(UUID id) {
    return find(id).orElseThrow(() -> new NotFoundException("Disparador", id));
  }

  Optional<Rule> find(UUID id) {
    return jdbc.sql("SELECT * FROM trigger_rule WHERE id = ?")
        .param(id)
        .query(this::map)
        .optional();
  }

  /** Las reglas activas de {@code label} en esos repositorios. */
  List<Rule> forLabel(Collection<UUID> repositoryIds, String label) {
    if (repositoryIds.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(
            "SELECT * FROM trigger_rule WHERE kind = 'ISSUE' AND enabled"
                + " AND repository_id IN (:ids) AND lower(label) = lower(:label)"
                + " ORDER BY created_at")
        .param("ids", repositoryIds)
        .param("label", label)
        .query(this::map)
        .list();
  }

  /** La regla activa del repositorio para lanzar {@code workflowKey} (repositorio de órdenes). */
  Optional<Rule> forWorkflow(UUID repositoryId, String workflowKey) {
    return jdbc.sql(
            "SELECT * FROM trigger_rule WHERE kind = 'ISSUE' AND enabled AND repository_id = ?"
                + " AND workflow_key = ? ORDER BY created_at LIMIT 1")
        .params(repositoryId, workflowKey)
        .query(this::map)
        .optional();
  }

  @Transactional
  Rule create(UUID repositoryId, Draft draft) {
    projects.requireActive(projects.getRepository(repositoryId));
    Rule rule = normalized(UUID.randomUUID(), repositoryId, draft, null);
    Instant now = time.now();
    try {
      jdbc.sql(
              "INSERT INTO trigger_rule (id, repository_id, kind, workflow_key, label,"
                  + " work_item_type, inputs, input_policy, enabled, created_at, updated_at)"
                  + " VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)")
          .params(
              rule.id(),
              repositoryId,
              rule.kind().name(),
              rule.workflowKey(),
              rule.label(),
              rule.workItemType().name(),
              json.writeValueAsString(rule.inputs()),
              json.writeValueAsString(rule.inputPolicy()),
              rule.enabled(),
              Timestamp.from(now),
              Timestamp.from(now))
          .update();
    } catch (DuplicateKeyException e) {
      throw duplicate(rule.label());
    }
    return get(rule.id());
  }

  @Transactional
  Rule update(UUID id, Draft draft) {
    Rule current = get(id);
    Rule rule = normalized(id, current.repositoryId(), draft, current);
    try {
      jdbc.sql(
              "UPDATE trigger_rule SET workflow_key = ?, label = ?, work_item_type = ?,"
                  + " inputs = ?::jsonb, input_policy = ?::jsonb, enabled = ?, updated_at = ?"
                  + " WHERE id = ?")
          .params(
              rule.workflowKey(),
              rule.label(),
              rule.workItemType().name(),
              json.writeValueAsString(rule.inputs()),
              json.writeValueAsString(rule.inputPolicy()),
              rule.enabled(),
              Timestamp.from(time.now()),
              id)
          .update();
    } catch (DuplicateKeyException e) {
      throw duplicate(rule.label());
    }
    return get(id);
  }

  @Transactional
  void delete(UUID id) {
    get(id);
    jdbc.sql("DELETE FROM trigger_rule WHERE id = ?").param(id).update();
  }

  private Rule normalized(UUID id, UUID repositoryId, Draft draft, Rule current) {
    Kind kind = current != null ? current.kind() : draft.kind() == null ? Kind.ISSUE : draft.kind();
    if (current != null && draft.kind() != null && draft.kind() != current.kind()) {
      throw new InvalidRequestException("No se puede cambiar el tipo de un disparador");
    }
    String workflowKey =
        draft.workflowKey() != null
            ? draft.workflowKey().strip()
            : current == null ? null : current.workflowKey();
    if (workflowKey == null || !WORKFLOW_KEY.matcher(workflowKey).matches()) {
      throw new InvalidRequestException("Falta el workflow (su id, p. ej. implementar-tarea)");
    }
    String label =
        draft.label() != null && !draft.label().isBlank()
            ? draft.label().strip().toLowerCase(Locale.ROOT)
            : current != null && draft.workflowKey() == null
                ? current.label()
                : PREFIX + workflowKey;
    if (!LABEL.matcher(label).matches()) {
      throw new InvalidRequestException(
          "La etiqueta tiene que empezar por skynet: y llevar solo minúsculas, números y - . _ :");
    }
    if (RESERVED.contains(label)) {
      throw new InvalidRequestException("La etiqueta " + label + " la usa Skynet: elige otra");
    }
    WorkItemType type =
        draft.workItemType() != null
            ? draft.workItemType()
            : current == null ? WorkItemType.FEATURE : current.workItemType();
    Map<String, Object> inputs =
        draft.inputs() != null ? draft.inputs() : current == null ? Map.of() : current.inputs();
    Map<String, InputPolicy.Rule> policy =
        draft.inputPolicy() != null
            ? draft.inputPolicy()
            : current == null ? Map.of() : current.inputPolicy();
    InputPolicy.validate(policy);
    for (String fixed : inputs.keySet()) {
      if (policy.containsKey(fixed)) {
        throw new InvalidRequestException(
            "El dato `" + fixed + "` es fijo: no puede estar también entre los que da la issue");
      }
    }
    boolean enabled =
        draft.enabled() != null ? draft.enabled() : current == null || current.enabled();
    return new Rule(
        id, repositoryId, kind, workflowKey, label, type, inputs, policy, enabled, null, null);
  }

  private static ConflictException duplicate(String label) {
    return new ConflictException(
        "Este repositorio ya tiene un disparador con la etiqueta " + label);
  }

  private Rule map(ResultSet rs, int row) throws SQLException {
    return new Rule(
        rs.getObject("id", UUID.class),
        rs.getObject("repository_id", UUID.class),
        Kind.valueOf(rs.getString("kind")),
        rs.getString("workflow_key"),
        rs.getString("label"),
        WorkItemType.valueOf(rs.getString("work_item_type")),
        json.readValue(rs.getString("inputs"), INPUTS),
        json.readValue(rs.getString("input_policy"), POLICY),
        rs.getBoolean("enabled"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }
}
