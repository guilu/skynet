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

  /** Fusionar la PR y borrar su rama. */
  static final Map<String, String> MERGE_WRITE =
      Map.of("metadata", "read", "pull_requests", "write", "contents", "write");

  /** Leer el repositorio y abrir, actualizar y etiquetar su PR. */
  static final Map<String, String> PULL_REQUESTS_WRITE =
      Map.of("metadata", "read", "pull_requests", "write");

  /** Leer la CI de un commit: check-runs y statuses. */
  static final Map<String, String> CHECKS_READ =
      Map.of("metadata", "read", "checks", "read", "statuses", "read");

  /** Leer los logs de los jobs de GitHub Actions (EN-B): el permiso que se añade en EN. */
  static final Map<String, String> ACTIONS_READ = Map.of("metadata", "read", "actions", "read");

  /** El {@code slug} de la App de GitHub Actions: sus check-runs son jobs con log. */
  static final String ACTIONS_APP = "github-actions";

  /** Líneas del final del log de un job que se dan al agente. */
  public static final int LOG_LINES = 200;

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
    return properties.gitUrl() + "/" + link.fullName() + ".git";
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

  /**
   * Una comprobación de la CI de un commit: un check-run o un status.
   *
   * @param kind {@code check} o {@code status}
   * @param state {@code pending}, {@code success} o {@code failure}, ya normalizado
   * @param conclusion lo que dijo GitHub: la conclusión del check-run o el estado del status
   * @param summary el título o la descripción, si los tiene
   * @param checkRunId el id del check-run, para sus anotaciones; {@code null} en un status
   * @param jobId el job de GitHub Actions del check-run, para su log; {@code null} si no es uno
   */
  public record Check(
      String name,
      String kind,
      String state,
      String conclusion,
      String summary,
      String url,
      Long checkRunId,
      Long jobId) {

    public static final String PENDING = "pending";
    public static final String SUCCESS = "success";
    public static final String FAILURE = "failure";
  }

  /** La CI del commit {@code sha}: sus check-runs (el último de cada nombre) y sus statuses. */
  public List<Check> checks(Link link, String sha) {
    String token =
        tokens.forRepository(link.installationId(), link.githubRepositoryId(), CHECKS_READ);
    List<Check> checks = new java.util.ArrayList<>();
    for (JsonNode run : api.checkRuns(token, link.fullName(), sha)) {
      String conclusion = run.path("conclusion").asString(null);
      String state =
          !"completed".equals(run.path("status").asString(""))
              ? Check.PENDING
              : switch (conclusion == null ? "" : conclusion) {
                case "success", "neutral", "skipped" -> Check.SUCCESS;
                default -> Check.FAILURE;
              };
      String title = run.path("output").path("title").asString(null);
      boolean actions = ACTIONS_APP.equals(run.path("app").path("slug").asString(""));
      checks.add(
          new Check(
              run.path("name").asString("?"),
              "check",
              state,
              conclusion,
              title,
              run.path("html_url").asString(null),
              run.path("id").asLong(),
              actions ? run.path("id").asLong() : null));
    }
    for (JsonNode status : api.statuses(token, link.fullName(), sha)) {
      String value = status.path("state").asString("pending");
      String state =
          switch (value) {
            case "success" -> Check.SUCCESS;
            case "failure", "error" -> Check.FAILURE;
            default -> Check.PENDING;
          };
      checks.add(
          new Check(
              status.path("context").asString("?"),
              "status",
              state,
              value,
              status.path("description").asString(null),
              status.path("target_url").asString(null),
              null,
              null));
    }
    return checks;
  }

  /**
   * Lo que se sabe de por qué fallaron {@code failed}: sus anotaciones (fichero, línea y mensaje)
   * y, en los jobs de GitHub Actions, las últimas {@value #LOG_LINES} líneas de su log. Sin el
   * permiso Actions: lectura, solo las anotaciones, y lo dice.
   */
  public String failureReport(Link link, List<Check> failed) {
    String checksToken =
        tokens.forRepository(link.installationId(), link.githubRepositoryId(), CHECKS_READ);
    String actionsToken = null;
    String actionsError = null;
    StringBuilder report = new StringBuilder();
    for (Check check : failed) {
      report.append("### ").append(check.name());
      if (check.conclusion() != null) {
        report.append(" (").append(check.conclusion()).append(')');
      }
      report.append('\n');
      if (check.summary() != null && !check.summary().isBlank()) {
        report.append(check.summary().strip()).append('\n');
      }
      if (check.checkRunId() != null) {
        try {
          List<JsonNode> annotations =
              api.annotations(checksToken, link.fullName(), check.checkRunId());
          if (!annotations.isEmpty()) {
            report.append("\nAnotaciones:\n");
            for (JsonNode a : annotations) {
              report
                  .append("- ")
                  .append(a.path("path").asString("?"))
                  .append(':')
                  .append(a.path("start_line").asInt(0))
                  .append(" [")
                  .append(a.path("annotation_level").asString("?"))
                  .append("] ")
                  .append(a.path("message").asString("").strip().replace("\n", " "))
                  .append('\n');
            }
          }
        } catch (GitHubException e) {
          report
              .append("\n(No se pudieron leer sus anotaciones: ")
              .append(e.getMessage())
              .append(")\n");
        }
      }
      if (check.jobId() != null && actionsError == null) {
        try {
          if (actionsToken == null) {
            actionsToken =
                tokens.forRepository(
                    link.installationId(), link.githubRepositoryId(), ACTIONS_READ);
          }
          String log = api.jobLog(actionsToken, link.fullName(), check.jobId());
          report
              .append("\nÚltimas líneas del log:\n```\n")
              .append(tail(log, LOG_LINES))
              .append("\n```\n");
        } catch (GitHubException e) {
          actionsError =
              "Sin los logs de los jobs: la App necesita el permiso Actions: lectura ("
                  + e.getMessage()
                  + ")";
        }
      }
      report.append('\n');
    }
    if (actionsError != null) {
      report.append("_").append(actionsError).append("_\n");
    }
    return report.toString().strip();
  }

  /** Comenta en la PR {@code number}. */
  public void comment(Link link, long number, String body) {
    api.comment(token(link), link.fullName(), number, body);
  }

  /**
   * El estado de una PR en GitHub.
   *
   * @param state {@code open} o {@code closed}
   * @param merged si está fusionada
   * @param headSha el último commit de su rama
   * @param mergeSha el commit de la fusión, si está fusionada
   */
  public record PullRequestState(String state, boolean merged, String headSha, String mergeSha) {}

  /** El estado de la PR {@code number}. */
  public PullRequestState pullRequestState(Link link, long number) {
    JsonNode pr = api.pullRequest(token(link), link.fullName(), number);
    return new PullRequestState(
        pr.path("state").asString("open"),
        pr.path("merged").asBoolean(false),
        pr.path("head").path("sha").asString(null),
        pr.path("merge_commit_sha").asString(null));
  }

  /**
   * Fusiona la PR {@code number} con {@code method}, solo si su último commit sigue siendo {@code
   * sha}: si alguien ha subido algo después, GitHub responde 409 y no la fusiona.
   *
   * @return el commit de la fusión
   */
  public String merge(Link link, long number, String method, String sha) {
    String token =
        tokens.forRepository(link.installationId(), link.githubRepositoryId(), MERGE_WRITE);
    return api.mergePullRequest(token, link.fullName(), number, method, sha)
        .path("sha")
        .asString(null);
  }

  /** Borra la rama {@code branch} del repositorio. */
  public void deleteBranch(Link link, String branch) {
    String token =
        tokens.forRepository(link.installationId(), link.githubRepositoryId(), MERGE_WRITE);
    api.deleteBranch(token, link.fullName(), branch);
  }

  /** Las últimas {@code lines} líneas de {@code text}, sin la marca de tiempo de Actions. */
  static String tail(String text, int lines) {
    String[] all = text.strip().split("\\R", -1);
    int from = Math.max(0, all.length - lines);
    StringBuilder out = new StringBuilder();
    for (int i = from; i < all.length; i++) {
      out.append(all[i].replaceFirst("^\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z ", ""));
      if (i < all.length - 1) {
        out.append('\n');
      }
    }
    return out.toString();
  }

  private String token(Link link) {
    return tokens.forRepository(
        link.installationId(), link.githubRepositoryId(), PULL_REQUESTS_WRITE);
  }
}
