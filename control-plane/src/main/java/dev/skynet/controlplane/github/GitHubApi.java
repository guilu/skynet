package dev.skynet.controlplane.github;

import dev.skynet.controlplane.shared.TimeSource;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Las llamadas a la API REST de GitHub que hace Skynet. Las de la App van con su JWT y las de una
 * instalación con su token; ninguno de los dos sale de aquí en un mensaje de error.
 */
@Component
class GitHubApi {

  static final String API_VERSION = "2022-11-28";
  private static final int PAGE_SIZE = 100;
  private static final Duration TIMEOUT = Duration.ofSeconds(20);

  private final GitHubProperties properties;
  private final ObjectMapper json;
  private final TimeSource time;
  private final HttpClient http;
  private final HttpClient noRedirects;
  private PrivateKey key;

  GitHubApi(GitHubProperties properties, ObjectMapper json, TimeSource time) {
    this.properties = properties;
    this.json = json;
    this.time = time;
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    this.noRedirects =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  /** {@code GET /app}: la App de las credenciales. */
  JsonNode app() {
    return send("GET", "/app", appJwt(), null);
  }

  /** {@code GET /app/installations}: todas las cuentas donde está instalada. */
  List<JsonNode> installations() {
    List<JsonNode> all = new ArrayList<>();
    String jwt = appJwt();
    for (int page = 1; ; page++) {
      JsonNode items =
          send("GET", "/app/installations?per_page=" + PAGE_SIZE + "&page=" + page, jwt, null);
      items.forEach(all::add);
      if (items.size() < PAGE_SIZE) {
        return all;
      }
    }
  }

  /**
   * {@code POST /app/installations/{id}/access_tokens}: un token de la instalación, limitado a
   * {@code repositoryIds} (todos los suyos si está vacío) y a {@code permissions} (todos los de la
   * App si está vacío).
   */
  JsonNode createToken(
      long installationId, List<Long> repositoryIds, Map<String, String> permissions) {
    Map<String, Object> body = new java.util.LinkedHashMap<>();
    if (!repositoryIds.isEmpty()) {
      body.put("repository_ids", repositoryIds);
    }
    if (!permissions.isEmpty()) {
      body.put("permissions", permissions);
    }
    return send(
        "POST",
        "/app/installations/" + installationId + "/access_tokens",
        appJwt(),
        json.writeValueAsString(body));
  }

  /** {@code GET /installation/repositories}: los repositorios a los que llega un token. */
  List<JsonNode> installationRepositories(String installationToken) {
    List<JsonNode> all = new ArrayList<>();
    for (int page = 1; ; page++) {
      JsonNode items =
          send(
                  "GET",
                  "/installation/repositories?per_page=" + PAGE_SIZE + "&page=" + page,
                  installationToken,
                  null)
              .path("repositories");
      items.forEach(all::add);
      if (items.size() < PAGE_SIZE) {
        return all;
      }
    }
  }

  /** {@code GET /repos/{owner}/{repo}}: el repositorio, con su rama por defecto. */
  JsonNode repository(String token, String fullName) {
    return send("GET", "/repos/" + fullName, token, null);
  }

  /**
   * {@code GET /repos/{owner}/{repo}/pulls?head=owner:rama&state=open}: la PR abierta de la rama.
   */
  List<JsonNode> openPullRequests(String token, String fullName, String owner, String branch) {
    String head =
        java.net.URLEncoder.encode(owner + ":" + branch, java.nio.charset.StandardCharsets.UTF_8);
    List<JsonNode> all = new ArrayList<>();
    send("GET", "/repos/" + fullName + "/pulls?state=open&head=" + head, token, null)
        .forEach(all::add);
    return all;
  }

  /** {@code POST /repos/{owner}/{repo}/pulls}. */
  JsonNode createPullRequest(String token, String fullName, Map<String, Object> body) {
    return send("POST", "/repos/" + fullName + "/pulls", token, json.writeValueAsString(body));
  }

  /** {@code PATCH /repos/{owner}/{repo}/pulls/{n}}. */
  JsonNode updatePullRequest(String token, String fullName, long number, Map<String, Object> body) {
    return send(
        "PATCH", "/repos/" + fullName + "/pulls/" + number, token, json.writeValueAsString(body));
  }

  /** {@code POST /repos/{owner}/{repo}/issues/{n}/labels}: añade etiquetas a una PR. */
  void addLabels(String token, String fullName, long number, List<String> labels) {
    send(
        "POST",
        "/repos/" + fullName + "/issues/" + number + "/labels",
        token,
        json.writeValueAsString(Map.of("labels", labels)));
  }

  /**
   * {@code GET /repos/{owner}/{repo}/commits/{sha}/check-runs}: los check-runs del commit, el
   * último de cada nombre.
   */
  List<JsonNode> checkRuns(String token, String fullName, String sha) {
    List<JsonNode> all = new ArrayList<>();
    for (int page = 1; ; page++) {
      JsonNode items =
          send(
                  "GET",
                  "/repos/"
                      + fullName
                      + "/commits/"
                      + sha
                      + "/check-runs?filter=latest&per_page="
                      + PAGE_SIZE
                      + "&page="
                      + page,
                  token,
                  null)
              .path("check_runs");
      items.forEach(all::add);
      if (items.size() < PAGE_SIZE) {
        return all;
      }
    }
  }

  /** {@code GET /repos/{owner}/{repo}/commits/{sha}/status}: los statuses del commit. */
  List<JsonNode> statuses(String token, String fullName, String sha) {
    List<JsonNode> all = new ArrayList<>();
    send(
            "GET",
            "/repos/" + fullName + "/commits/" + sha + "/status?per_page=" + PAGE_SIZE,
            token,
            null)
        .path("statuses")
        .forEach(all::add);
    return all;
  }

  /** {@code GET /repos/{owner}/{repo}/check-runs/{id}/annotations}: las primeras 50. */
  List<JsonNode> annotations(String token, String fullName, long checkRunId) {
    List<JsonNode> all = new ArrayList<>();
    send(
            "GET",
            "/repos/" + fullName + "/check-runs/" + checkRunId + "/annotations?per_page=50",
            token,
            null)
        .forEach(all::add);
    return all;
  }

  /**
   * {@code GET /repos/{owner}/{repo}/actions/jobs/{id}/logs}: el log de un job de GitHub Actions.
   * GitHub redirige a una URL firmada, que se pide sin el token.
   */
  String jobLog(String token, String fullName, long jobId) {
    HttpRequest request =
        base("/repos/" + fullName + "/actions/jobs/" + jobId + "/logs", token).GET().build();
    HttpResponse<String> response = call(noRedirects, request, "GET", "/actions/jobs/logs");
    if (response.statusCode() / 100 == 3) {
      String location = response.headers().firstValue("Location").orElse(null);
      if (location == null) {
        throw new GitHubException(
            response.statusCode(), "GitHub no dio la URL del log del job " + jobId);
      }
      HttpRequest download =
          HttpRequest.newBuilder(URI.create(location)).timeout(TIMEOUT).GET().build();
      response = call(noRedirects, download, "GET", "log del job " + jobId);
    }
    if (response.statusCode() / 100 != 2) {
      throw new GitHubException(
          response.statusCode(),
          "GitHub respondió "
              + response.statusCode()
              + " al pedir el log del job "
              + jobId
              + message(response.body()));
    }
    return response.body();
  }

  /** {@code GET /repos/{owner}/{repo}/pulls/{n}}: la PR, con su estado y su commit. */
  JsonNode pullRequest(String token, String fullName, long number) {
    return send("GET", "/repos/" + fullName + "/pulls/" + number, token, null);
  }

  /**
   * {@code PUT /repos/{owner}/{repo}/pulls/{n}/merge}: fusiona solo si la PR sigue en {@code sha}.
   */
  JsonNode mergePullRequest(String token, String fullName, long number, String method, String sha) {
    return send(
        "PUT",
        "/repos/" + fullName + "/pulls/" + number + "/merge",
        token,
        json.writeValueAsString(Map.of("merge_method", method, "sha", sha)));
  }

  /** {@code DELETE /repos/{owner}/{repo}/git/refs/heads/{rama}}: borra una rama. */
  void deleteBranch(String token, String fullName, String branch) {
    send("DELETE", "/repos/" + fullName + "/git/refs/heads/" + branch, token, null);
  }

  /** {@code POST /repos/{owner}/{repo}/issues/{n}/comments}: comenta en una PR. */
  void comment(String token, String fullName, long number, String body) {
    send(
        "POST",
        "/repos/" + fullName + "/issues/" + number + "/comments",
        token,
        json.writeValueAsString(Map.of("body", body)));
  }

  /**
   * {@code GET /app/hook/deliveries}: las últimas entregas de webhooks de la App, con su código.
   */
  List<JsonNode> hookDeliveries() {
    List<JsonNode> all = new ArrayList<>();
    send("GET", "/app/hook/deliveries?per_page=" + PAGE_SIZE, appJwt(), null).forEach(all::add);
    return all;
  }

  /** {@code POST /app/hook/deliveries/{id}/attempts}: pide a GitHub que repita una entrega. */
  void redeliver(long hookDeliveryId) {
    send("POST", "/app/hook/deliveries/" + hookDeliveryId + "/attempts", appJwt(), null);
  }

  private JsonNode send(String method, String path, String bearer, String body) {
    HttpRequest.Builder request = base(path, bearer);
    if (body == null) {
      request.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      request
          .header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(body));
    }
    HttpResponse<String> response = call(http, request.build(), method, path);
    if (response.statusCode() / 100 != 2) {
      throw new GitHubException(
          response.statusCode(),
          "GitHub respondió "
              + response.statusCode()
              + " a "
              + method
              + " "
              + path.replaceAll("\\?.*", "")
              + message(response.body()));
    }
    return response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
  }

  private HttpRequest.Builder base(String path, String bearer) {
    return HttpRequest.newBuilder(URI.create(properties.apiUrl() + path))
        .timeout(TIMEOUT)
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", API_VERSION)
        .header("User-Agent", "skynet")
        .header("Authorization", "Bearer " + bearer);
  }

  private HttpResponse<String> call(
      HttpClient client, HttpRequest request, String method, String path) {
    try {
      return client.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new GitHubException("No se puede conectar con " + properties.apiUrl(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new GitHubException("Interrumpido al llamar a GitHub: " + method + " " + path, e);
    }
  }

  private String message(String body) {
    try {
      String message = json.readTree(body).path("message").asString("");
      return message.isBlank() ? "" : ": " + message;
    } catch (RuntimeException e) {
      return "";
    }
  }

  private String appJwt() {
    if (!properties.configured()) {
      throw new GitHubException(
          0, "La GitHub App no está configurada: falta " + String.join(", ", properties.missing()));
    }
    return AppJwt.sign(properties.appId(), privateKey(), time.now());
  }

  /** La clave se lee una vez; si cambia el fichero, hay que reiniciar el control plane. */
  private synchronized PrivateKey privateKey() {
    if (key == null) {
      String file = properties.privateKeyFile();
      try {
        key = AppJwt.readPrivateKey(Files.readString(Path.of(file)));
      } catch (IOException e) {
        throw new GitHubException(0, "No se puede leer la clave privada de la App (" + file + ")");
      } catch (IllegalArgumentException e) {
        throw new GitHubException(0, "La clave privada de la App (" + file + ") " + e.getMessage());
      }
    }
    return key;
  }
}
