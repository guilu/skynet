package dev.skynet.controlplane.memory;

import dev.skynet.controlplane.shared.ConflictException;
import dev.skynet.controlplane.shared.InvalidRequestException;
import dev.skynet.controlplane.shared.NotFoundException;
import dev.skynet.controlplane.shared.TimeSource;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * El curador de la memoria (MV-E, docs/memoria.md «El curador»). Una fase con {@code memory:
 * curate} lee la memoria como {@code read}; al terminar bien, su salida estructurada se aplica
 * aquí:
 *
 * <ul>
 *   <li>{@code pages}: páginas de la wiki de su repositorio (o del proyecto con {@code scope:
 *       project}), con origen {@code CURATOR}. No puede tocar las que genera Skynet ni las que
 *       escribió una persona.
 *   <li>{@code proposals}: propuestas de archivar entradas o de fusionarlas en una, que quedan
 *       pendientes hasta que una persona las acepta o las rechaza. Ninguna se aplica sola.
 *   <li>{@code summary}: el resumen de la curación.
 * </ul>
 *
 * <p>Lo que no vale se apunta en {@code rejected} con el motivo, sin hacer fallar la ejecución.
 */
@Service
public class MemoryCuration {

  /** Quien firma lo que escribe el curador. */
  public static final String ACTOR = "curador";

  /** Las páginas que genera Skynet sin modelo (MV-C): el curador no las toca. */
  static final Set<String> GENERATED =
      Set.of(WikiPages.INDEX, "lo-hecho", "errores-conocidos", "decisiones-y-restricciones");

  static final int MAX_PAGES = 10;
  static final int MAX_PROPOSALS = 30;
  static final int MAX_REASON = 500;
  static final int MAX_SUMMARY = 4_000;
  static final int MAX_INBOX = 40;

  private static final String PROJECT = "project";
  private static final String ACTION = "action";
  private static final String REPOSITORY = "repository";
  private static final String REASON = "reason";
  private static final String TITLE = "title";
  private static final String SUMMARY = "summary";
  private static final String PAGES = "pages";
  private static final String INTO = "into";
  private static final String PROJECT_ID = "project_id";
  private static final String CREATED_AT = "created_at";
  private static final String PROPOSALS_SQL =
      "SELECT id, curation_id, action, observation_ids, into_id, reason, status, decided_by,"
          + " decided_at, created_at FROM memory_proposal";

  private final JdbcClient jdbc;
  private final WikiPages wiki;
  private final MemoryService memory;
  private final TimeSource time;
  private final JsonMapper json = JsonMapper.builder().build();

  MemoryCuration(JdbcClient jdbc, WikiPages wiki, MemoryService memory, TimeSource time) {
    this.jdbc = jdbc;
    this.wiki = wiki;
    this.memory = memory;
    this.time = time;
  }

  /** Qué propone: archivar entradas, o fusionarlas en otra. */
  public enum Action {
    ARCHIVE,
    MERGE
  }

  /** En qué está una propuesta. */
  public enum Status {
    PENDING,
    ACCEPTED,
    REJECTED
  }

  /** Una página que escribió el curador; {@code changed} es falso si ya estaba igual. */
  public record WrittenPage(
      String slug, String title, String scope, int version, boolean changed) {}

  /** Algo de su salida que no valía, con el motivo. */
  public record Rejected(String item, String reason) {}

  /** Una propuesta de archivar o fusionar entradas. */
  public record Proposal(
      UUID id,
      UUID curationId,
      Action action,
      List<UUID> observationIds,
      UUID intoId,
      String reason,
      Status status,
      String decidedBy,
      Instant decidedAt,
      Instant createdAt) {}

  /** El informe de una curación. */
  public record Curation(
      UUID id,
      UUID projectId,
      UUID repositoryId,
      UUID workflowRunId,
      UUID agentRunId,
      String summary,
      List<WrittenPage> pages,
      List<Rejected> rejected,
      List<Proposal> proposals,
      Instant createdAt) {}

  /** Lo confirmado desde la última curación del proyecto, para el bloque del curador. */
  public record Inbox(List<MemoryObservation> items, int total, Instant since) {}

  // Aplicar la salida

  private record Run(UUID projectId, UUID repositoryId, UUID workflowRunId) {}

  /**
   * Aplica la salida estructurada de una invocación del curador. Una invocación se aplica una sola
   * vez: si ya está, devuelve la que hay.
   */
  @Transactional
  public Optional<Curation> apply(UUID agentRunId, String output) {
    Optional<UUID> existing =
        jdbc.sql("SELECT id FROM memory_curation WHERE agent_run_id = ?")
            .param(agentRunId)
            .query(UUID.class)
            .optional();
    if (existing.isPresent()) {
      return Optional.of(load(existing.get()));
    }
    Optional<Run> run =
        jdbc.sql(
                "SELECT w.project_id, a.repository_id, s.workflow_run_id FROM agent_run a"
                    + " JOIN stage_run s ON s.id = a.stage_run_id"
                    + " JOIN workflow_run r ON r.id = s.workflow_run_id"
                    + " JOIN work_item w ON w.id = r.work_item_id WHERE a.id = ?")
            .param(agentRunId)
            .query(
                (rs, n) ->
                    new Run(
                        rs.getObject(PROJECT_ID, UUID.class),
                        rs.getObject("repository_id", UUID.class),
                        rs.getObject("workflow_run_id", UUID.class)))
            .optional();
    if (run.isEmpty()) {
      return Optional.empty();
    }
    JsonNode root = parse(output);
    Instant now = time.now();
    UUID id = UUID.randomUUID();
    List<Rejected> rejected = new ArrayList<>();
    List<WrittenPage> pages = pages(run.get(), root.path(PAGES), rejected);
    List<Proposal> proposals = proposals(run.get(), id, root.path("proposals"), rejected, now);
    String summary = text(root.path(SUMMARY));
    if (summary.isEmpty()) {
      summary = root.isMissingNode() ? "El curador no devolvió nada." : "Sin resumen.";
    }
    jdbc.sql(
            "INSERT INTO memory_curation (id, project_id, repository_id, workflow_run_id,"
                + " agent_run_id, summary, pages, rejected, created_at) VALUES (:id, :project,"
                + " :repository, :run, :agent, :summary, :pages::jsonb, :rejected::jsonb, :now)")
        .param("id", id)
        .param(PROJECT, run.get().projectId())
        .param(REPOSITORY, run.get().repositoryId())
        .param("run", run.get().workflowRunId())
        .param("agent", agentRunId)
        .param(SUMMARY, clip(summary, MAX_SUMMARY))
        .param(PAGES, json.writeValueAsString(pages))
        .param("rejected", json.writeValueAsString(rejected))
        .param("now", Timestamp.from(now))
        .update();
    for (int i = 0; i < proposals.size(); i++) {
      Proposal p = proposals.get(i);
      jdbc.sql(
              "INSERT INTO memory_proposal (id, curation_id, position, project_id, action,"
                  + " observation_ids, into_id, reason, status, created_at) VALUES (:id, :curation,"
                  + " :position, :project, :action, :ids, :into, :reason, 'PENDING', :now)")
          .param("id", p.id())
          .param("position", i)
          .param("curation", id)
          .param(PROJECT, run.get().projectId())
          .param(ACTION, p.action().name())
          .param("ids", p.observationIds().toArray(UUID[]::new))
          .param(INTO, p.intoId())
          .param(REASON, p.reason())
          .param("now", Timestamp.from(now))
          .update();
    }
    return Optional.of(load(id));
  }

  private JsonNode parse(String output) {
    if (output == null || output.isBlank()) {
      return json.missingNode();
    }
    try {
      JsonNode node = json.readTree(output);
      return node.isObject() ? node : json.missingNode();
    } catch (JacksonException e) {
      return json.missingNode();
    }
  }

  private List<WrittenPage> pages(Run run, JsonNode items, List<Rejected> rejected) {
    List<WrittenPage> written = new ArrayList<>();
    if (!items.isArray()) {
      return written;
    }
    for (JsonNode item : items) {
      String slug = text(item.path("slug")).toLowerCase(Locale.ROOT);
      if (written.size() >= MAX_PAGES) {
        rejected.add(new Rejected(slug, "Solo se escriben " + MAX_PAGES + " páginas por curación"));
        continue;
      }
      String problem = pageProblem(run, item, slug);
      if (problem != null) {
        rejected.add(new Rejected(slug.isEmpty() ? "(sin slug)" : slug, problem));
        continue;
      }
      boolean project = PROJECT.equals(text(item.path("scope")));
      UUID repositoryId = project ? null : run.repositoryId();
      // Ya validada: write no rechaza nada (una excepción de un @Transactional marcaría la
      // transacción para deshacerse aunque se capturase).
      WikiPages.Written w =
          wiki.write(
              run.projectId(),
              repositoryId,
              slug,
              text(item.path(TITLE)),
              item.path("body").asString(),
              sources(run.projectId(), item.path("sources")),
              WikiOrigin.CURATOR,
              ACTOR);
      written.add(
          new WrittenPage(
              slug,
              w.page().title(),
              project ? PROJECT : REPOSITORY,
              w.page().version(),
              w.changed()));
    }
    return written;
  }

  /** Por qué no se puede escribir esa página, o {@code null} si se puede. */
  private String pageProblem(Run run, JsonNode item, String slug) {
    if (slug.isEmpty()) {
      return "Falta el slug de la página";
    }
    if (!WikiPages.validSlug(slug)) {
      return "El slug solo admite minúsculas y cifras separadas por guiones";
    }
    if (GENERATED.contains(slug)) {
      return "Esta página la genera Skynet: el curador no la escribe";
    }
    if (text(item.path(TITLE)).isEmpty() || text(item.path("body")).isEmpty()) {
      return "La página necesita título y contenido";
    }
    UUID repositoryId = PROJECT.equals(text(item.path("scope"))) ? null : run.repositoryId();
    String origin =
        jdbc.sql(
                "SELECT origin FROM wiki_page WHERE project_id = ? AND repository_id IS NOT"
                    + " DISTINCT FROM ? AND slug = ?")
            .params(run.projectId(), repositoryId, slug)
            .query(String.class)
            .optional()
            .orElse(null);
    if (WikiOrigin.HUMAN.name().equals(origin)) {
      return "Esta página la escribió una persona: el curador no la cambia";
    }
    return null;
  }

  /** Las fuentes de una página: ids de entradas del proyecto (sueltos o en {@code {id}}). */
  private List<WikiSource> sources(UUID projectId, JsonNode items) {
    if (!items.isArray()) {
      return List.of();
    }
    Set<UUID> ids = new LinkedHashSet<>();
    for (JsonNode item : items) {
      uuid(item.isObject() ? item.path("id") : item).ifPresent(ids::add);
    }
    Map<UUID, String> titles = titles(projectId, ids);
    List<WikiSource> sources = new ArrayList<>();
    for (UUID id : ids) {
      if (titles.containsKey(id)) {
        sources.add(new WikiSource("memory", id, titles.get(id), null));
      }
    }
    return sources;
  }

  private List<Proposal> proposals(
      Run run, UUID curationId, JsonNode items, List<Rejected> rejected, Instant now) {
    List<Proposal> proposals = new ArrayList<>();
    if (!items.isArray()) {
      return proposals;
    }
    for (JsonNode item : items) {
      String label = text(item.path(ACTION)) + " " + text(item.path(REASON));
      if (proposals.size() >= MAX_PROPOSALS) {
        rejected.add(new Rejected(label, "Solo se guardan " + MAX_PROPOSALS + " propuestas"));
        continue;
      }
      try {
        Proposal p = proposal(run.projectId(), curationId, item, now);
        if (pending(run.projectId(), p)) {
          rejected.add(new Rejected(label, "Ya hay una propuesta igual pendiente"));
        } else {
          proposals.add(p);
        }
      } catch (InvalidRequestException e) {
        rejected.add(new Rejected(label.strip(), e.getMessage()));
      }
    }
    return proposals;
  }

  private Proposal proposal(UUID projectId, UUID curationId, JsonNode item, Instant now) {
    Action action;
    try {
      action = Action.valueOf(text(item.path(ACTION)).toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new InvalidRequestException("La acción tiene que ser archive o merge");
    }
    String reason = text(item.path(REASON));
    if (reason.isEmpty()) {
      throw new InvalidRequestException("Falta el motivo");
    }
    UUID into = null;
    if (action == Action.MERGE) {
      into =
          uuid(item.path(INTO))
              .orElseThrow(() -> new InvalidRequestException("Fusionar pide la entrada `into`"));
    }
    Set<UUID> ids = new LinkedHashSet<>();
    for (JsonNode id : item.path("observations")) {
      uuid(id).ifPresent(ids::add);
    }
    if (into != null) {
      ids.remove(into);
    }
    if (ids.isEmpty()) {
      throw new InvalidRequestException("No nombra ninguna entrada en `observations`");
    }
    Set<UUID> all = new LinkedHashSet<>(ids);
    if (into != null) {
      all.add(into);
    }
    Map<UUID, String> live = titles(projectId, all);
    for (UUID id : all) {
      if (!live.containsKey(id)) {
        throw new InvalidRequestException(
            "La entrada " + id + " no es del proyecto o está archivada");
      }
    }
    return new Proposal(
        UUID.randomUUID(),
        curationId,
        action,
        List.copyOf(ids),
        into,
        clip(reason, MAX_REASON),
        Status.PENDING,
        null,
        null,
        now);
  }

  /** Si ya hay una propuesta pendiente con la misma acción y las mismas entradas. */
  private boolean pending(UUID projectId, Proposal p) {
    return jdbc.sql(
                "SELECT count(*) FROM memory_proposal WHERE project_id = :project AND status ="
                    + " 'PENDING' AND action = :action AND into_id IS NOT DISTINCT FROM :into"
                    + " AND observation_ids @> :ids AND observation_ids <@ :ids")
            .param(PROJECT, projectId)
            .param(ACTION, p.action().name())
            .param(INTO, p.intoId())
            .param("ids", p.observationIds().toArray(UUID[]::new))
            .query(Long.class)
            .single()
        > 0;
  }

  /** Los títulos de esas entradas, si son del proyecto y no están archivadas. */
  private Map<UUID, String> titles(UUID projectId, Set<UUID> ids) {
    Map<UUID, String> titles = new HashMap<>();
    if (ids.isEmpty()) {
      return titles;
    }
    jdbc.sql(
            "SELECT id, title FROM memory_observation WHERE project_id = :project"
                + " AND archived_at IS NULL AND id = ANY(:ids)")
        .param(PROJECT, projectId)
        .param("ids", ids.toArray(UUID[]::new))
        .query((rs, n) -> Map.entry(rs.getObject("id", UUID.class), rs.getString(TITLE)))
        .list()
        .forEach(e -> titles.put(e.getKey(), e.getValue()));
    return titles;
  }

  // Leer

  /** Las curaciones del proyecto, de la más reciente a la más antigua. */
  @Transactional(readOnly = true)
  public List<Curation> curations(UUID projectId, int limit) {
    return jdbc
        .sql("SELECT id FROM memory_curation WHERE project_id = ? ORDER BY created_at DESC LIMIT ?")
        .params(projectId, Math.clamp(limit, 1, 50))
        .query(UUID.class)
        .list()
        .stream()
        .map(this::load)
        .toList();
  }

  /**
   * Las propuestas del proyecto, de la más reciente a la más antigua; con {@code status}, solo
   * esas.
   */
  @Transactional(readOnly = true)
  public List<Proposal> proposals(UUID projectId, Status status) {
    return jdbc.sql(
            PROPOSALS_SQL
                + " WHERE project_id = :project AND (CAST(:status AS text) IS NULL OR status ="
                + " :status) ORDER BY created_at DESC, position LIMIT 200")
        .param(PROJECT, projectId)
        .param("status", status == null ? null : status.name())
        .query((rs, n) -> proposal(rs))
        .list();
  }

  private Curation load(UUID id) {
    List<Proposal> proposals =
        jdbc.sql(PROPOSALS_SQL + " WHERE curation_id = ? ORDER BY position")
            .param(id)
            .query((rs, n) -> proposal(rs))
            .list();
    return jdbc.sql(
            "SELECT id, project_id, repository_id, workflow_run_id, agent_run_id, summary,"
                + " pages, rejected, created_at FROM memory_curation WHERE id = ?")
        .param(id)
        .query(
            (rs, n) ->
                new Curation(
                    rs.getObject("id", UUID.class),
                    rs.getObject(PROJECT_ID, UUID.class),
                    rs.getObject("repository_id", UUID.class),
                    rs.getObject("workflow_run_id", UUID.class),
                    rs.getObject("agent_run_id", UUID.class),
                    rs.getString(SUMMARY),
                    List.of(json.readValue(rs.getString(PAGES), WrittenPage[].class)),
                    List.of(json.readValue(rs.getString("rejected"), Rejected[].class)),
                    proposals,
                    rs.getTimestamp(CREATED_AT).toInstant()))
        .single();
  }

  private static Proposal proposal(ResultSet rs) throws SQLException {
    Array ids = rs.getArray("observation_ids");
    Timestamp decided = rs.getTimestamp("decided_at");
    return new Proposal(
        rs.getObject("id", UUID.class),
        rs.getObject("curation_id", UUID.class),
        Action.valueOf(rs.getString(ACTION)),
        Arrays.asList((UUID[]) ids.getArray()),
        rs.getObject("into_id", UUID.class),
        rs.getString(REASON),
        Status.valueOf(rs.getString("status")),
        rs.getString("decided_by"),
        decided == null ? null : decided.toInstant(),
        rs.getTimestamp(CREATED_AT).toInstant());
  }

  /**
   * Lo confirmado y sin archivar del repositorio y del proyecto que cambió desde la última curación
   * del proyecto (o todo, si no ha habido ninguna), de lo más reciente a lo más antiguo. Sin la
   * bitácora: lo hecho ya lo cuenta la wiki, y la de la propia curación solo sería ruido.
   */
  @Transactional(readOnly = true)
  public Inbox inbox(UUID projectId, UUID repositoryId) {
    Instant since =
        jdbc.sql("SELECT max(created_at) FROM memory_curation WHERE project_id = ?")
            .param(projectId)
            .query(Timestamp.class)
            .optional()
            .map(Timestamp::toInstant)
            .orElse(null);
    String where =
        " FROM memory_observation WHERE project_id = :project AND archived_at IS NULL"
            + " AND trust = 'CONFIRMED' AND kind <> 'RUN_LOG' AND (repository_id IS NULL OR repository_id ="
            + " :repository) AND (CAST(:since AS timestamptz) IS NULL OR updated_at > :since)";
    int total =
        jdbc.sql("SELECT count(*)" + where)
            .param(PROJECT, projectId)
            .param(REPOSITORY, repositoryId)
            .param("since", since == null ? null : Timestamp.from(since))
            .query(Integer.class)
            .single();
    List<UUID> ids =
        jdbc.sql("SELECT id" + where + " ORDER BY updated_at DESC LIMIT :limit")
            .param(PROJECT, projectId)
            .param(REPOSITORY, repositoryId)
            .param("since", since == null ? null : Timestamp.from(since))
            .param("limit", MAX_INBOX)
            .query(UUID.class)
            .list();
    return new Inbox(ids.stream().map(memory::get).toList(), total, since);
  }

  // Decidir

  /**
   * Acepta una propuesta: archiva sus entradas (al fusionar, las que se quedan dentro de {@code
   * intoId}, que sigue igual).
   */
  @Transactional
  public Proposal accept(UUID id, String actor) {
    Proposal p = pendingProposal(id);
    // Las que ya no están no se archivan: get lanzaría y desharía la transacción.
    Set<UUID> present =
        new HashSet<>(
            jdbc.sql("SELECT id FROM memory_observation WHERE id = ANY(?)")
                .param(p.observationIds().toArray(UUID[]::new))
                .query(UUID.class)
                .list());
    for (UUID observation : p.observationIds()) {
      if (present.contains(observation)) {
        memory.archive(observation, actor);
      }
    }
    return decide(p, Status.ACCEPTED, actor);
  }

  /** Rechaza una propuesta: no cambia nada. */
  @Transactional
  public Proposal reject(UUID id, String actor) {
    return decide(pendingProposal(id), Status.REJECTED, actor);
  }

  private Proposal pendingProposal(UUID id) {
    Proposal p =
        jdbc.sql(PROPOSALS_SQL + " WHERE id = ? FOR UPDATE")
            .param(id)
            .query((rs, n) -> proposal(rs))
            .optional()
            .orElseThrow(() -> new NotFoundException("Propuesta", id));
    if (p.status() != Status.PENDING) {
      throw new ConflictException("La propuesta ya está decidida: " + p.status());
    }
    return p;
  }

  private Proposal decide(Proposal p, Status status, String actor) {
    Instant now = time.now();
    jdbc.sql("UPDATE memory_proposal SET status = ?, decided_by = ?, decided_at = ? WHERE id = ?")
        .params(status.name(), actor, Timestamp.from(now), p.id())
        .update();
    return new Proposal(
        p.id(),
        p.curationId(),
        p.action(),
        p.observationIds(),
        p.intoId(),
        p.reason(),
        status,
        actor,
        now,
        p.createdAt());
  }

  private static Optional<UUID> uuid(JsonNode node) {
    try {
      return node.isString()
          ? Optional.of(UUID.fromString(node.asString().strip()))
          : Optional.empty();
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private static String text(JsonNode node) {
    return node.isString() ? node.asString().strip() : "";
  }

  private static String clip(String text, int max) {
    return text.length() <= max ? text : text.substring(0, max - 1) + "…";
  }
}
