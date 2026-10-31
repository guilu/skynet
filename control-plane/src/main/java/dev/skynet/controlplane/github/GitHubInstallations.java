package dev.skynet.controlplane.github;

import dev.skynet.controlplane.github.GitHubStatus.Access;
import dev.skynet.controlplane.github.GitHubStatus.App;
import dev.skynet.controlplane.github.GitHubStatus.Installation;
import dev.skynet.controlplane.github.GitHubStatus.InstallationRepository;
import dev.skynet.controlplane.github.GitHubStatus.RepositoryLink;
import dev.skynet.controlplane.shared.TimeSource;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Las instalaciones de la App y sus repositorios ({@code github_installation*}), y el enlace de
 * cada repositorio de Skynet con el suyo en GitHub por su URL remota. Se sincronizan a mano ({@code
 * POST /api/github/sync}) y al arrancar.
 */
@Service
public class GitHubInstallations {

  private static final Logger log = LoggerFactory.getLogger(GitHubInstallations.class);

  /** Lo que vale la comprobación de la App antes de repetirla. */
  static final Duration APP_CHECK_TTL = Duration.ofMinutes(1);

  private final GitHubProperties properties;
  private final GitHubApi api;
  private final GitHubTokens tokens;
  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;
  private final TimeSource time;
  private volatile AppCheck lastCheck;

  GitHubInstallations(
      GitHubProperties properties,
      GitHubApi api,
      GitHubTokens tokens,
      JdbcClient jdbc,
      TransactionTemplate transactions,
      TimeSource time) {
    this.properties = properties;
    this.api = api;
    this.tokens = tokens;
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.time = time;
  }

  /** Al arrancar, si la App está configurada, sincroniza. Un fallo solo se registra. */
  @EventListener(ApplicationReadyEvent.class)
  void syncOnStartup() {
    if (!properties.configured()) {
      log.info("GitHub App sin configurar: falta {}", String.join(", ", properties.missing()));
      return;
    }
    Thread.ofVirtual()
        .name("github-sync")
        .start(
            () -> {
              try {
                sync();
              } catch (GitHubException e) {
                log.warn("No se pudo sincronizar con GitHub al arrancar: {}", e.getMessage());
              }
            });
  }

  /** El estado de la App, sus instalaciones y el enlace de cada repositorio. */
  public GitHubStatus status() {
    AppCheck check = properties.configured() ? check(false) : null;
    List<Installation> installations = installations();
    return new GitHubStatus(
        properties.configured(),
        properties.missing(),
        properties.apiUrl(),
        properties.appId(),
        check == null ? null : check.app(),
        check == null ? null : check.error(),
        check == null ? null : check.checkedAt(),
        installations,
        links(installations));
  }

  /**
   * Vuelve a pedir a GitHub las instalaciones y sus repositorios y lo guarda: las que ya no están
   * se borran, y las suspendidas conservan los repositorios que tenían.
   *
   * @throws GitHubException si la App no está configurada o GitHub falla; entonces no se cambia
   *     nada
   */
  public synchronized GitHubStatus sync() {
    AppCheck check = check(true);
    if (check.app() == null) {
      throw new GitHubException(0, check.error());
    }
    List<Fetched> fetched = new ArrayList<>();
    for (JsonNode installation : api.installations()) {
      long id = installation.path("id").asLong();
      boolean suspended = timestamp(installation.path("suspended_at")) != null;
      List<JsonNode> repositories = null;
      if (suspended) {
        tokens.forget(id);
      } else {
        repositories = repositories(id);
      }
      fetched.add(new Fetched(installation, repositories));
    }
    Instant now = time.now();
    transactions.executeWithoutResult(status -> save(fetched, now));
    log.info("GitHub sincronizado: {} instalaciones", fetched.size());
    return status();
  }

  /** Los repositorios de una instalación. Con un 401 (token revocado), uno nuevo y otra vez. */
  private List<JsonNode> repositories(long installationId) {
    try {
      return api.installationRepositories(tokens.forInstallation(installationId));
    } catch (GitHubException e) {
      if (e.status() != 401) {
        throw e;
      }
      tokens.forget(installationId);
      return api.installationRepositories(tokens.forInstallation(installationId));
    }
  }

  private void save(List<Fetched> fetched, Instant now) {
    List<Long> ids = fetched.stream().map(f -> f.installation().path("id").asLong()).toList();
    if (ids.isEmpty()) {
      jdbc.sql("DELETE FROM github_installation").update();
    } else {
      jdbc.sql("DELETE FROM github_installation WHERE id NOT IN (:ids)").param("ids", ids).update();
    }
    for (Fetched f : fetched) {
      long id = upsertInstallation(f.installation(), now);
      if (f.repositories() == null) {
        continue;
      }
      jdbc.sql("DELETE FROM github_installation_repository WHERE installation_id = :id")
          .param("id", id)
          .update();
      f.repositories().forEach(repository -> addRepository(id, repository));
    }
  }

  /** Guarda una instalación tal como la da GitHub (sin sus repositorios). Devuelve su id. */
  long upsertInstallation(JsonNode installation, Instant now) {
    long id = installation.path("id").asLong();
    jdbc.sql(
            """
            INSERT INTO github_installation (id, account_login, account_type,
                repository_selection, suspended_at, synced_at, created_at)
            VALUES (:id, :account, :type, :selection, :suspendedAt, :now, :now)
            ON CONFLICT (id) DO UPDATE SET account_login = EXCLUDED.account_login,
                account_type = EXCLUDED.account_type,
                repository_selection = EXCLUDED.repository_selection,
                suspended_at = EXCLUDED.suspended_at, synced_at = EXCLUDED.synced_at
            """)
        .param("id", id)
        .param("account", installation.path("account").path("login").asString(""))
        .param("type", installation.path("account").path("type").asString("User"))
        .param("selection", installation.path("repository_selection").asString("selected"))
        .param("suspendedAt", timestamp(installation.path("suspended_at")))
        .param("now", Timestamp.from(now))
        .update();
    return id;
  }

  /** Añade a una instalación un repositorio de GitHub ({@code id}, {@code full_name}…). */
  void addRepository(long installationId, JsonNode repository) {
    jdbc.sql(
            """
            INSERT INTO github_installation_repository
                (installation_id, repository_id, full_name, private)
            VALUES (:installation, :repository, :name, :private)
            ON CONFLICT (installation_id, repository_id)
                DO UPDATE SET full_name = EXCLUDED.full_name, private = EXCLUDED.private
            """)
        .param("installation", installationId)
        .param("repository", repository.path("id").asLong())
        .param("name", repository.path("full_name").asString())
        .param("private", repository.path("private").asBoolean(false))
        .update();
  }

  void removeRepository(long installationId, long repositoryId) {
    jdbc.sql(
            "DELETE FROM github_installation_repository"
                + " WHERE installation_id = :installation AND repository_id = :repository")
        .param("installation", installationId)
        .param("repository", repositoryId)
        .update();
  }

  /** Borra una instalación (desinstalada) con sus repositorios y olvida sus tokens. */
  void removeInstallation(long installationId) {
    jdbc.sql("DELETE FROM github_installation WHERE id = :id").param("id", installationId).update();
    tokens.forget(installationId);
  }

  /** Marca una instalación como suspendida ({@code at}) o reactivada ({@code null}). */
  void suspend(long installationId, Instant at) {
    jdbc.sql("UPDATE github_installation SET suspended_at = :at WHERE id = :id")
        .param("at", at == null ? null : Timestamp.from(at))
        .param("id", installationId)
        .update();
    tokens.forget(installationId);
  }

  /**
   * Los repositorios de Skynet sin archivar cuya URL remota es {@code owner/nombre} en este GitHub,
   * sin distinguir mayúsculas.
   */
  List<UUID> repositoriesNamed(String fullName) {
    String host = properties.webHost().toLowerCase(Locale.ROOT);
    return jdbc
        .sql(
            "SELECT r.id, r.remote_url FROM repository r JOIN project p ON p.id = r.project_id"
                + " WHERE r.archived_at IS NULL AND p.archived_at IS NULL AND r.remote_url IS NOT NULL")
        .query(
            (rs, n) ->
                RemoteName.parse(rs.getString("remote_url"))
                        .filter(name -> name.host().equals(host))
                        .filter(name -> name.fullName().equalsIgnoreCase(fullName))
                        .isPresent()
                    ? rs.getObject("id", UUID.class)
                    : null)
        .list()
        .stream()
        .filter(java.util.Objects::nonNull)
        .toList();
  }

  private List<Installation> installations() {
    Map<Long, List<InstallationRepository>> repositories = new HashMap<>();
    jdbc.sql(
            "SELECT installation_id, repository_id, full_name, private"
                + " FROM github_installation_repository ORDER BY lower(full_name)")
        .query(
            (rs, n) ->
                repositories
                    .computeIfAbsent(rs.getLong("installation_id"), k -> new ArrayList<>())
                    .add(
                        new InstallationRepository(
                            rs.getLong("repository_id"),
                            rs.getString("full_name"),
                            rs.getBoolean("private"))))
        .list();
    return jdbc.sql(
            "SELECT id, account_login, account_type, repository_selection, suspended_at,"
                + " synced_at FROM github_installation ORDER BY lower(account_login), id")
        .query(
            (rs, n) ->
                new Installation(
                    rs.getLong("id"),
                    rs.getString("account_login"),
                    rs.getString("account_type"),
                    rs.getString("repository_selection"),
                    instant(rs.getTimestamp("suspended_at")),
                    instant(rs.getTimestamp("synced_at")),
                    repositories.getOrDefault(rs.getLong("id"), List.of())))
        .list();
  }

  /**
   * Cada repositorio de Skynet sin archivar con su repositorio en GitHub: por {@code owner/nombre}
   * sin distinguir mayúsculas, y preferiblemente a través de una instalación no suspendida.
   */
  private List<RepositoryLink> links(List<Installation> installations) {
    Map<String, Match> byName = new LinkedHashMap<>();
    for (Installation installation : installations) {
      for (InstallationRepository repository : installation.repositories()) {
        String key = repository.fullName().toLowerCase(Locale.ROOT);
        Match current = byName.get(key);
        if (current == null
            || (current.installation().suspendedAt() != null
                && installation.suspendedAt() == null)) {
          byName.put(key, new Match(installation, repository));
        }
      }
    }
    String host = properties.webHost().toLowerCase(Locale.ROOT);
    return jdbc.sql(
            "SELECT r.id, r.project_id, p.key, r.name, r.remote_url FROM repository r"
                + " JOIN project p ON p.id = r.project_id"
                + " WHERE r.archived_at IS NULL AND p.archived_at IS NULL"
                + " ORDER BY p.key, r.name")
        .query(
            (rs, n) -> {
              String remoteUrl = rs.getString("remote_url");
              Optional<RemoteName> remote =
                  RemoteName.parse(remoteUrl).filter(name -> name.host().equals(host));
              String fullName = remote.map(RemoteName::fullName).orElse(null);
              Match match = fullName == null ? null : byName.get(fullName.toLowerCase(Locale.ROOT));
              Access access =
                  fullName == null
                      ? Access.NOT_GITHUB
                      : match == null || match.installation().suspendedAt() != null
                          ? Access.NOT_INSTALLED
                          : Access.CONNECTED;
              return new RepositoryLink(
                  rs.getObject("id", UUID.class),
                  rs.getObject("project_id", UUID.class),
                  rs.getString("key"),
                  rs.getString("name"),
                  remoteUrl,
                  fullName,
                  access,
                  match == null ? null : match.installation().id(),
                  match == null ? null : match.installation().account(),
                  match == null ? null : match.repository().id());
            })
        .list();
  }

  private AppCheck check(boolean force) {
    AppCheck current = lastCheck;
    Instant now = time.now();
    if (!force && current != null && current.checkedAt().plus(APP_CHECK_TTL).isAfter(now)) {
      return current;
    }
    AppCheck check;
    try {
      JsonNode app = api.app();
      check =
          new AppCheck(
              new App(
                  app.path("slug").asString(null),
                  app.path("name").asString(null),
                  app.path("owner").path("login").asString(null),
                  app.path("html_url").asString(null)),
              null,
              now);
    } catch (GitHubException e) {
      check = new AppCheck(null, e.getMessage(), now);
    }
    lastCheck = check;
    return check;
  }

  static Timestamp timestamp(JsonNode node) {
    return node == null || node.isNull() || node.isMissingNode()
        ? null
        : Timestamp.from(Instant.parse(node.asString()));
  }

  private static Instant instant(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }

  private record AppCheck(App app, String error, Instant checkedAt) {}

  private record Fetched(JsonNode installation, List<JsonNode> repositories) {}

  private record Match(Installation installation, InstallationRepository repository) {}
}
