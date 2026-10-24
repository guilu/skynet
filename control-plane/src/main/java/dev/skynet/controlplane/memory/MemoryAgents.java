package dev.skynet.controlplane.memory;

import dev.skynet.controlplane.shared.Archived;
import dev.skynet.controlplane.shared.ConflictException;
import dev.skynet.controlplane.shared.InvalidRequestException;
import dev.skynet.controlplane.shared.NotFoundException;
import dev.skynet.controlplane.shared.TimeSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La memoria vista desde un agente (ME-B): el token de cada invocación y lo que puede hacer con él.
 *
 * <p>El runner pide el token justo antes de lanzar el agente y se lo da al puente MCP de memoria.
 * El token solo vale mientras la invocación no ha terminado, y solo para el proyecto de la
 * invocación y su repositorio: el ámbito sale de la invocación, nunca de lo que pida el agente.
 */
@Service
public class MemoryAgents {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Set<String> FINISHED = Set.of("COMPLETED", "FAILED", "CANCELLED");
  private static final String PROVIDER_COMMAND = "command";
  private static final int PREVIEW = 280;
  private static final int MAX_RESULTS = 20;
  private static final int MAX_GOAL = 180;

  /** Acceso de una invocación a la memoria. */
  public enum Mode {
    READ,
    WRITE
  }

  /** Token recién emitido; solo existe en claro en esta respuesta. */
  public record IssuedToken(String token, Mode mode) {}

  /** La invocación dueña de un token, con su ámbito. */
  record Caller(
      UUID agentRunId, UUID workflowRunId, UUID projectId, UUID repositoryId, Mode mode) {}

  /** Una entrada de una búsqueda, recortada. */
  record Preview(
      UUID id,
      MemoryKind kind,
      String title,
      String topicKey,
      String scope,
      String preview,
      boolean pinned,
      int version,
      MemoryOrigin origin,
      Instant updatedAt) {}

  record SearchResult(List<Preview> results, long total) {}

  record Saved(UUID id, int version, boolean created) {}

  record Context(String text, List<Ref> observations, int omitted) {}

  /** Una observación con la versión que recibió o leyó una invocación. */
  public record Ref(UUID id, int version) {}

  /**
   * El bloque de memoria al principio del prompt de una invocación nueva (ME-C).
   *
   * @param text el bloque, ya delimitado, con las instrucciones de uso; va antes del prompt
   * @param observations las observaciones que incluye, con su versión
   * @param omitted las que no caben en el tope
   */
  public record PromptBlock(String text, List<Ref> observations, int omitted) {}

  static final String BLOCK_START = "<memoria-del-proyecto>";
  static final String BLOCK_END = "</memoria-del-proyecto>";

  static final String TOOLS_WRITE =
      "Tienes las herramientas de memoria `skynet_memoria`. Antes de decidir algo que pueda estar"
          + " ya resuelto, busca con `mem_search` y lee la entrada entera con `mem_get`. Guarda con"
          + " `mem_save` las decisiones, los bugs resueltos, los descubrimientos, los patrones, la"
          + " configuración, las trampas y las restricciones que convenga recordar, con un"
          + " `topic_key` estable si actualiza un tema; no guardes salidas de herramientas, cada"
          + " paso ni secretos. Antes de terminar, llama a `mem_session_summary`.";

  static final String TOOLS_READ =
      "Tienes las herramientas de memoria `skynet_memoria` en modo de solo lectura. Antes de"
          + " decidir algo que pueda estar ya resuelto, busca con `mem_search` y lee la entrada"
          + " entera con `mem_get`. Esta fase no guarda en la memoria.";

  /**
   * Lo que el agente pide guardar. Sin {@code scope}, o con {@code repository}, va al repositorio.
   */
  record SaveRequest(
      MemoryKind kind,
      String title,
      String topicKey,
      String what,
      String why,
      String where,
      String learned,
      List<String> tags,
      String scope) {}

  /** El resumen de la sesión, al estilo de {@code mem_session_summary} de engram. */
  record SessionSummary(
      String goal, String accomplished, String discoveries, String nextSteps, List<String> files) {}

  private final JdbcClient jdbc;
  private final MemoryService memory;
  private final MemoryContext context;
  private final MemoryProperties properties;
  private final TimeSource time;

  MemoryAgents(
      JdbcClient jdbc,
      MemoryService memory,
      MemoryContext context,
      MemoryProperties properties,
      TimeSource time) {
    this.jdbc = jdbc;
    this.memory = memory;
    this.context = context;
    this.properties = properties;
    this.time = time;
  }

  /**
   * Emite el token de memoria de una invocación de ese runner. Pedirlo otra vez (el runner relanza
   * la invocación) invalida el anterior.
   */
  @Transactional
  public IssuedToken issue(UUID runnerId, UUID agentRunId) {
    record Run(UUID runnerId, String status, String provider, String memory) {}
    Run run =
        jdbc.sql("SELECT runner_id, status, provider, memory FROM agent_run WHERE id = ?")
            .param(agentRunId)
            .query(
                (rs, n) ->
                    new Run(
                        rs.getObject("runner_id", UUID.class),
                        rs.getString("status"),
                        rs.getString("provider"),
                        rs.getString("memory")))
            .optional()
            .filter(r -> runnerId.equals(r.runnerId()))
            .orElseThrow(() -> new NotFoundException("Invocación", agentRunId));
    if (FINISHED.contains(run.status())) {
      throw new ConflictException("La invocación ya terminó: no tiene acceso a la memoria");
    }
    if (PROVIDER_COMMAND.equals(run.provider())) {
      throw new ConflictException("Un comando no usa la memoria");
    }
    if (run.memory() == null) {
      throw new ConflictException("Esta invocación no usa la memoria (`memory: off`)");
    }
    Mode mode = Mode.valueOf(run.memory());
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    String token = "skm_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    jdbc.sql(
            "INSERT INTO memory_token (agent_run_id, token_hash, mode, created_at)"
                + " VALUES (:id, :hash, :mode, :now) ON CONFLICT (agent_run_id) DO UPDATE"
                + " SET token_hash = EXCLUDED.token_hash, mode = EXCLUDED.mode")
        .param("id", agentRunId)
        .param("hash", hash(token))
        .param("mode", mode.name())
        .param("now", Timestamp.from(time.now()))
        .update();
    return new IssuedToken(token, mode);
  }

  /**
   * La invocación de la cabecera {@code Authorization: Bearer <token>}, si sigue en marcha. Cuenta
   * la llamada para el límite de la invocación.
   */
  @Transactional
  Caller authenticate(String authorization) {
    if (authorization == null || !authorization.startsWith("Bearer ")) {
      throw new MemoryTokenException("Falta el token de memoria");
    }
    String hash = hash(authorization.substring("Bearer ".length()).trim());
    record Row(Caller caller, String status, int calls) {}
    Row row =
        jdbc.sql(
                "SELECT t.agent_run_id, t.mode, t.calls, a.status, a.repository_id,"
                    + " s.workflow_run_id, w.project_id FROM memory_token t"
                    + " JOIN agent_run a ON a.id = t.agent_run_id"
                    + " JOIN stage_run s ON s.id = a.stage_run_id"
                    + " JOIN workflow_run r ON r.id = s.workflow_run_id"
                    + " JOIN work_item w ON w.id = r.work_item_id"
                    + " WHERE t.token_hash = ?")
            .param(hash)
            .query(
                (rs, n) ->
                    new Row(
                        new Caller(
                            rs.getObject("agent_run_id", UUID.class),
                            rs.getObject("workflow_run_id", UUID.class),
                            rs.getObject("project_id", UUID.class),
                            rs.getObject("repository_id", UUID.class),
                            Mode.valueOf(rs.getString("mode"))),
                        rs.getString("status"),
                        rs.getInt("calls")))
            .optional()
            .orElseThrow(() -> new MemoryTokenException("Token de memoria inválido"));
    if (FINISHED.contains(row.status())) {
      throw new MemoryTokenException("La invocación ya terminó: el token de memoria ha caducado");
    }
    int updated =
        jdbc.sql("UPDATE memory_token SET calls = calls + 1 WHERE agent_run_id = ? AND calls < ?")
            .params(row.caller().agentRunId(), properties.maxCalls())
            .update();
    if (updated == 0) {
      throw new MemoryLimitException(
          "La invocación ya ha hecho "
              + properties.maxCalls()
              + " llamadas a la memoria, el máximo por invocación");
    }
    return row.caller();
  }

  /**
   * El bloque de memoria de una invocación nueva de {@code mode}, que va al principio de su prompt:
   * el contexto de su repositorio y su proyecto y, si su CLI tiene las herramientas de memoria
   * ({@code tools}), cómo usarlas. Deja en {@code memory_access} lo que recibe ({@code PROMPT}).
   */
  @Transactional
  public PromptBlock promptBlock(
      UUID agentRunId, UUID projectId, UUID repositoryId, Mode mode, boolean tools) {
    MemoryContext.Rendered rendered =
        context.render(projectId, repositoryId, properties.contextMaxChars());
    Instant now = time.now();
    rendered.included().forEach(o -> record(agentRunId, o, "PROMPT", now));
    StringBuilder text = new StringBuilder(BLOCK_START).append('\n').append(rendered.text());
    if (tools) {
      text.append("\n\n").append(mode == Mode.WRITE ? TOOLS_WRITE : TOOLS_READ);
    }
    text.append('\n').append(BLOCK_END);
    return new PromptBlock(
        text.toString(),
        rendered.included().stream().map(o -> new Ref(o.id(), o.version())).toList(),
        rendered.omitted());
  }

  /** El contexto con el que empieza el agente. */
  @Transactional
  Context context(Caller caller) {
    MemoryContext.Rendered rendered =
        context.render(caller.projectId(), caller.repositoryId(), properties.contextMaxChars());
    rendered.included().forEach(o -> record(caller, o, "CONTEXT"));
    return new Context(
        rendered.text(),
        rendered.included().stream().map(o -> new Ref(o.id(), o.version())).toList(),
        rendered.omitted());
  }

  /** Busca en su repositorio y en todo su proyecto; devuelve vistas previas. */
  @Transactional
  SearchResult search(Caller caller, String query, Set<MemoryKind> kinds, Integer limit) {
    MemoryPage page =
        memory.search(
            caller.projectId(),
            new MemoryQuery(
                MemoryQuery.Scope.REPOSITORY_AND_PROJECT,
                caller.repositoryId(),
                kinds,
                null,
                null,
                Archived.EXCLUDE,
                query,
                0,
                limit == null ? 10 : Math.clamp(limit, 1, MAX_RESULTS)));
    page.items().forEach(o -> record(caller, o, "SEARCH"));
    return new SearchResult(
        page.items().stream().map(MemoryAgents::preview).toList(), page.total());
  }

  /** Una observación entera, si es de su ámbito y no está archivada. */
  @Transactional
  MemoryObservation get(Caller caller, UUID id) {
    MemoryObservation o;
    try {
      o = memory.get(id);
    } catch (NotFoundException e) {
      throw new NotFoundException("Observación", id);
    }
    boolean inScope =
        o.projectId().equals(caller.projectId())
            && (o.repositoryId() == null || o.repositoryId().equals(caller.repositoryId()));
    if (!inScope || o.archivedAt() != null) {
      throw new NotFoundException("Observación", id);
    }
    record(caller, o, "GET");
    return o;
  }

  /** Guarda una observación del agente; con un tema que ya existe, una versión nueva de él. */
  @Transactional
  Saved save(Caller caller, SaveRequest request) {
    requireWrite(caller);
    if (request == null) {
      throw new InvalidRequestException("Falta la observación");
    }
    if (request.kind() == MemoryKind.SESSION_SUMMARY || request.kind() == MemoryKind.RUN_LOG) {
      throw new InvalidRequestException(
          "El tipo "
              + request.kind()
              + " no se guarda con mem_save: el resumen de la sesión va con mem_session_summary");
    }
    UUID repositoryId = repositoryFor(caller, request.scope());
    countSave(caller);
    MemorySaved saved =
        memory.save(
            caller.projectId(),
            repositoryId,
            new MemoryContent(
                request.kind(),
                request.title(),
                request.topicKey(),
                request.what(),
                request.why(),
                request.where(),
                request.learned(),
                request.tags()),
            MemorySource.agent(caller.workflowRunId(), caller.agentRunId()));
    record(caller, saved.observation(), "SAVE");
    return new Saved(saved.observation().id(), saved.observation().version(), saved.created());
  }

  /** El resumen de la sesión: uno por invocación, que se actualiza si se manda otra vez. */
  @Transactional
  Saved sessionSummary(Caller caller, SessionSummary summary) {
    requireWrite(caller);
    if (summary == null || blank(summary.goal()) || blank(summary.accomplished())) {
      throw new InvalidRequestException("El resumen necesita al menos goal y accomplished");
    }
    String goal = summary.goal().strip().replaceAll("\\s+", " ");
    String title =
        "Sesión: " + (goal.length() > MAX_GOAL ? goal.substring(0, MAX_GOAL) + "…" : goal);
    StringBuilder what = new StringBuilder("Objetivo: ").append(summary.goal().strip());
    what.append("\n\nHecho: ").append(summary.accomplished().strip());
    StringBuilder learned = new StringBuilder();
    if (!blank(summary.discoveries())) {
      learned.append("Descubrimientos: ").append(summary.discoveries().strip());
    }
    if (!blank(summary.nextSteps())) {
      if (!learned.isEmpty()) {
        learned.append("\n\n");
      }
      learned.append("Siguientes pasos: ").append(summary.nextSteps().strip());
    }
    String where =
        summary.files() == null || summary.files().isEmpty()
            ? null
            : String.join("\n", summary.files());
    countSave(caller);
    MemorySaved saved =
        memory.save(
            caller.projectId(),
            caller.repositoryId(),
            new MemoryContent(
                MemoryKind.SESSION_SUMMARY,
                title,
                "sesion/" + caller.agentRunId(),
                what.toString(),
                null,
                where,
                learned.isEmpty() ? null : learned.toString(),
                List.of()),
            MemorySource.agent(caller.workflowRunId(), caller.agentRunId()));
    record(caller, saved.observation(), "SAVE");
    return new Saved(saved.observation().id(), saved.observation().version(), saved.created());
  }

  private static UUID repositoryFor(Caller caller, String scope) {
    if (scope == null || scope.isBlank() || scope.strip().equals("repository")) {
      return caller.repositoryId();
    }
    if (scope.strip().equals("project")) {
      return null;
    }
    throw new InvalidRequestException("scope debe ser repository o project: " + scope);
  }

  private static void requireWrite(Caller caller) {
    if (caller.mode() != Mode.WRITE) {
      throw new MemoryReadOnlyException(
          "Esta fase solo puede leer la memoria: no guarda observaciones");
    }
  }

  private void countSave(Caller caller) {
    int updated =
        jdbc.sql("UPDATE memory_token SET saves = saves + 1 WHERE agent_run_id = ? AND saves < ?")
            .params(caller.agentRunId(), properties.maxSaves())
            .update();
    if (updated == 0) {
      throw new MemoryLimitException(
          "La invocación ya ha guardado "
              + properties.maxSaves()
              + " observaciones, el máximo por invocación");
    }
  }

  private void record(Caller caller, MemoryObservation o, String via) {
    record(caller.agentRunId(), o, via, time.now());
  }

  private void record(UUID agentRunId, MemoryObservation o, String via, Instant now) {
    jdbc.sql(
            "INSERT INTO memory_access (agent_run_id, observation_id, version, via, accessed_at)"
                + " VALUES (?, ?, ?, ?, ?)")
        .params(agentRunId, o.id(), o.version(), via, Timestamp.from(now))
        .update();
  }

  private static Preview preview(MemoryObservation o) {
    String text = o.what();
    if (text.length() > PREVIEW) {
      text = text.substring(0, PREVIEW) + "…";
    }
    return new Preview(
        o.id(),
        o.kind(),
        o.title(),
        o.topicKey(),
        o.repositoryId() == null ? "project" : "repository",
        text,
        o.pinned(),
        o.version(),
        o.origin(),
        o.updatedAt());
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  static String hash(String token) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Falta el token o no vale: 401. */
  static class MemoryTokenException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    MemoryTokenException(String message) {
      super(message);
    }
  }

  /** La invocación pasó su límite: 429. */
  static class MemoryLimitException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    MemoryLimitException(String message) {
      super(message);
    }
  }

  /** La invocación solo puede leer: 403. */
  static class MemoryReadOnlyException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    MemoryReadOnlyException(String message) {
      super(message);
    }
  }
}
