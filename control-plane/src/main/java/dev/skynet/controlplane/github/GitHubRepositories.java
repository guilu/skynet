package dev.skynet.controlplane.github;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Lo que la entrega por PR (EN) necesita de GitHub para un repositorio de Skynet: si está enlazado
 * con acceso, la URL y el token para subir su rama, y abrir o actualizar su PR. Cada llamada pide
 * un token limitado a ese repositorio y a lo justo para hacerla.
 */
@Service
public class GitHubRepositories {

  /** Subir ramas: lo que recibe el runner para el push. */
  static final Map<String, String> CONTENTS_WRITE = Map.of("contents", "write");

  /** Leer el repositorio y abrir, actualizar y etiquetar su PR. */
  static final Map<String, String> PULL_REQUESTS_WRITE =
      Map.of("metadata", "read", "pull_requests", "write");

  private final GitHubProperties properties;
  private final GitHubApi api;
  private final GitHubTokens tokens;
  private final JdbcClient jdbc;

  GitHubRepositories(
      GitHubProperties properties, GitHubApi api, GitHubTokens tokens, JdbcClient jdbc) {
    this.properties = properties;
    this.api = api;
    this.tokens = tokens;
    this.jdbc = jdbc;
  }

  /**
   * Un repositorio de Skynet enlazado con su repositorio de GitHub a través de una instalación no
   * suspendida.
   *
   * @param fullName {@code owner/nombre} tal como lo da GitHub
   */
  public record Link(long installationId, long githubRepositoryId, String fullName) {

    public String owner() {
      return fullName.substring(0, fullName.indexOf('/'));
    }
  }

  /**
   * Una PR abierta o actualizada.
   *
   * @param created si la acaba de abrir (si no, ya existía y se ha actualizado)
   */
  public record PullRequest(
      long number, String url, String headSha, String base, String state, boolean created) {}

  /** Si la App está configurada. */
  public boolean configured() {
    return properties.configured();
  }

  /**
   * El enlace del repositorio {@code repositoryId} de Skynet, o vacío si su URL remota no es de
   * este GitHub o la App no está instalada en él.
   */
  public Optional<Link> link(UUID repositoryId) {
    if (!properties.configured()) {
      return Optional.empty();
    }
    String host = properties.webHost().toLowerCase(Locale.ROOT);
    Optional<RemoteName> remote =
        jdbc.sql("SELECT remote_url FROM repository WHERE id = ?")
            .param(repositoryId)
            .query(String.class)
            .optional()
            .flatMap(RemoteName::parse)
            .filter(name -> name.host().equals(host));
    if (remote.isEmpty()) {
      return Optional.empty();
    }
    return jdbc.sql(
            """
            SELECT r.installation_id, r.repository_id, r.full_name
            FROM github_installation_repository r
            JOIN github_installation i ON i.id = r.installation_id
            WHERE lower(r.full_name) = lower(:name) AND i.suspended_at IS NULL
            ORDER BY r.installation_id LIMIT 1
            """)
        .param("name", remote.get().fullName())
        .query(
            (rs, n) ->
                new Link(
                    rs.getLong("installation_id"),
                    rs.getLong("repository_id"),
                    rs.getString("full_name")))
        .optional();
  }

  /** La URL https a la que sube el runner: la del repositorio en este GitHub. */
  public String pushUrl(Link link) {
    return properties.webUrl() + "/" + link.fullName() + ".git";
  }

  /**
   * Un token para subir la rama: limitado al repositorio y a {@code contents: write}, de corta
   * vida. Nunca se guarda ni se escribe en un log o un evento.
   */
  public String pushToken(Link link) {
    return tokens.forRepository(link.installationId(), link.githubRepositoryId(), CONTENTS_WRITE);
  }

  /** La rama por defecto del repositorio en GitHub. */
  public String defaultBranch(Link link) {
    return api.repository(token(link), link.fullName()).path("default_branch").asString("main");
  }

  /**
   * Abre la PR de {@code branch} contra {@code base} o, si ya hay una abierta de esa rama, le
   * cambia el título y la descripción. Después le pone {@code labels}.
   */
  public PullRequest openOrUpdate(
      Link link,
      String branch,
      String base,
      String title,
      String body,
      boolean draft,
      List<String> labels) {
    String token = token(link);
    List<JsonNode> open = api.openPullRequests(token, link.fullName(), link.owner(), branch);
    JsonNode pr;
    boolean created;
    if (open.isEmpty()) {
      Map<String, Object> request = new LinkedHashMap<>();
      request.put("title", title);
      request.put("head", branch);
      request.put("base", base);
      request.put("body", body);
      request.put("draft", draft);
      pr = api.createPullRequest(token, link.fullName(), request);
      created = true;
    } else {
      Map<String, Object> request = new LinkedHashMap<>();
      request.put("title", title);
      request.put("body", body);
      pr =
          api.updatePullRequest(
              token, link.fullName(), open.getFirst().path("number").asLong(), request);
      created = false;
    }
    long number = pr.path("number").asLong();
    if (!labels.isEmpty()) {
      api.addLabels(token, link.fullName(), number, labels);
    }
    return new PullRequest(
        number,
        pr.path("html_url").asString(null),
        pr.path("head").path("sha").asString(null),
        pr.path("base").path("ref").asString(base),
        pr.path("state").asString("open"),
        created);
  }

  private String token(Link link) {
    return tokens.forRepository(
        link.installationId(), link.githubRepositoryId(), PULL_REQUESTS_WRITE);
  }
}
