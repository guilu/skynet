package dev.skynet.controlplane.project;

import dev.skynet.controlplane.shared.InvalidRequestException;
import dev.skynet.controlplane.shared.TimeSource;
import dev.skynet.protocol.SafeText;
import dev.skynet.protocol.runner.DiscoveredRepository;
import dev.skynet.protocol.runner.RepositoryCatalog;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Catálogo de los repositorios git que los runners encontraron en sus carpetas raíz (IP-A). Es la
 * base del asistente «Importar proyecto»: cada entrada dice si su ruta ya está registrada.
 */
@Service
public class RepositoryCatalogs {

  static final int MAX_REPOSITORIES = 500;
  static final int MAX_ROOTS = 20;
  static final int MAX_TEXT = 2_000;
  static final int MAX_LIST = 50;

  private final JdbcClient jdbc;
  private final ObjectMapper json;
  private final TimeSource time;
  private final RepositoryCatalogRefresh refresh;

  RepositoryCatalogs(
      JdbcClient jdbc, ObjectMapper json, TimeSource time, RepositoryCatalogRefresh refresh) {
    this.jdbc = jdbc;
    this.json = json;
    this.time = time;
    this.refresh = refresh;
  }

  /** Repositorio ya registrado en la ruta de una entrada del catálogo. */
  public record Registered(
      UUID projectId, String projectKey, String projectName, UUID repositoryId) {}

  /** Una entrada del catálogo, con su registro si ya existe. */
  public record Entry(DiscoveredRepository repository, Registered registered) {}

  /** El catálogo de un runner. */
  public record RunnerCatalog(
      UUID runnerId,
      String runnerName,
      Instant lastHeartbeatAt,
      Instant scannedAt,
      Instant receivedAt,
      List<String> roots,
      boolean truncated,
      List<Entry> repositories) {}

  /** Guarda la foto del runner, que sustituye a la anterior. */
  @Transactional
  public void record(UUID runnerId, RepositoryCatalog catalog) {
    check(catalog);
    List<DiscoveredRepository> repositories =
        catalog.repositories().stream().map(RepositoryCatalogs::sanitize).toList();
    List<String> roots = catalog.roots().stream().map(SafeText::sanitize).toList();
    jdbc.sql(
            "INSERT INTO runner_repository_catalog"
                + " (runner_id, scanned_at, received_at, roots, repositories, truncated)"
                + " VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?)"
                + " ON CONFLICT (runner_id) DO UPDATE SET scanned_at = EXCLUDED.scanned_at,"
                + " received_at = EXCLUDED.received_at, roots = EXCLUDED.roots,"
                + " repositories = EXCLUDED.repositories, truncated = EXCLUDED.truncated")
        .params(
            runnerId,
            catalog.scannedAt().atOffset(java.time.ZoneOffset.UTC),
            time.now().atOffset(java.time.ZoneOffset.UTC),
            json.writeValueAsString(roots),
            json.writeValueAsString(repositories),
            catalog.truncated())
        .update();
  }

  /**
   * Los catálogos de todos los runners, con cada entrada cruzada con los repositorios registrados.
   */
  @Transactional(readOnly = true)
  public List<RunnerCatalog> list() {
    Map<String, Registered> registered = new HashMap<>();
    jdbc.sql(
            "SELECT r.local_path, r.id AS repository_id, p.id AS project_id, p.key, p.name"
                + " FROM repository r JOIN project p ON p.id = r.project_id")
        .query(
            (rs, n) ->
                registered.putIfAbsent(
                    normalize(rs.getString("local_path")),
                    new Registered(
                        rs.getObject("project_id", UUID.class),
                        rs.getString("key"),
                        rs.getString("name"),
                        rs.getObject("repository_id", UUID.class))))
        .list();
    return jdbc.sql(
            "SELECT c.runner_id, u.name, u.last_heartbeat_at, c.scanned_at, c.received_at,"
                + " c.roots::text AS roots, c.repositories::text AS repositories, c.truncated"
                + " FROM runner_repository_catalog c JOIN runner u ON u.id = c.runner_id"
                + " ORDER BY u.name")
        .query(
            (rs, n) -> {
              List<DiscoveredRepository> repositories =
                  json.readValue(
                      rs.getString("repositories"),
                      new TypeReference<List<DiscoveredRepository>>() {});
              List<Entry> entries = new ArrayList<>();
              for (DiscoveredRepository repository : repositories) {
                entries.add(new Entry(repository, registered.get(normalize(repository.path()))));
              }
              OffsetDateTime heartbeat = rs.getObject("last_heartbeat_at", OffsetDateTime.class);
              return new RunnerCatalog(
                  rs.getObject("runner_id", UUID.class),
                  rs.getString("name"),
                  heartbeat == null ? null : heartbeat.toInstant(),
                  rs.getObject("scanned_at", OffsetDateTime.class).toInstant(),
                  rs.getObject("received_at", OffsetDateTime.class).toInstant(),
                  json.readValue(rs.getString("roots"), new TypeReference<List<String>>() {}),
                  rs.getBoolean("truncated"),
                  entries);
            })
        .list();
  }

  /** Pide a todos los runners que vuelvan a buscar en su siguiente latido. */
  public void requestRefresh() {
    refresh.request();
  }

  /** Una ruta registrada a mano puede acabar en «/»; la del catálogo nunca. */
  static String normalize(String path) {
    if (path == null) {
      return null;
    }
    String p = path.strip();
    while (p.length() > 1 && p.endsWith("/")) {
      p = p.substring(0, p.length() - 1);
    }
    return p;
  }

  private static void check(RepositoryCatalog catalog) {
    if (catalog.repositories().size() > MAX_REPOSITORIES) {
      throw new InvalidRequestException(
          "Un catálogo lleva como mucho " + MAX_REPOSITORIES + " repositorios");
    }
    if (catalog.roots().size() > MAX_ROOTS) {
      throw new InvalidRequestException("Un catálogo lleva como mucho " + MAX_ROOTS + " carpetas");
    }
    for (String root : catalog.roots()) {
      text("roots", root);
    }
    for (DiscoveredRepository r : catalog.repositories()) {
      if (!r.path().startsWith("/")) {
        throw new InvalidRequestException(
            "La ruta de un repositorio debe ser absoluta: " + r.path());
      }
      for (String value :
          new String[] {
            r.path(),
            r.name(),
            r.projectName(),
            r.projectNameSource(),
            r.description(),
            r.descriptionSource(),
            r.remoteUrl(),
            r.defaultBranch(),
            r.defaultBranchSource(),
            r.tool(),
            r.validationCommand(),
            r.lastCommit(),
            r.lastCommitSubject()
          }) {
        text("repositories", value);
      }
      for (List<String> list : List.of(r.testReportPaths(), r.protectedPaths())) {
        if (list.size() > MAX_LIST) {
          throw new InvalidRequestException("Demasiadas rutas en " + r.path());
        }
        list.forEach(v -> text("repositories", v));
      }
    }
  }

  private static void text(String field, String value) {
    if (value != null && value.length() > MAX_TEXT) {
      throw new InvalidRequestException(
          "`" + field + "` lleva un texto de más de " + MAX_TEXT + " caracteres");
    }
  }

  private static DiscoveredRepository sanitize(DiscoveredRepository r) {
    return new DiscoveredRepository(
        SafeText.sanitize(r.path()),
        SafeText.sanitize(r.name()),
        s(r.projectName()),
        s(r.projectNameSource()),
        s(r.description()),
        s(r.descriptionSource()),
        s(r.remoteUrl()),
        s(r.defaultBranch()),
        s(r.defaultBranchSource()),
        s(r.tool()),
        s(r.validationCommand()),
        r.testReportPaths().stream().map(SafeText::sanitize).toList(),
        r.protectedPaths().stream().map(SafeText::sanitize).toList(),
        s(r.lastCommit()),
        s(r.lastCommitSubject()),
        r.lastCommitAt(),
        r.dirty(),
        r.agentInstructions());
  }

  private static String s(String value) {
    return value == null ? null : SafeText.sanitize(value);
  }
}
