package dev.skynet.controlplane.github;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Un GitHub falso para los tests, con el {@code HttpServer} del JDK: la App, sus instalaciones, los
 * tokens de instalación y los repositorios de cada una. Comprueba la firma del JWT de la App con su
 * clave pública y que cada token sea uno que dio él.
 */
public final class FakeGitHub implements AutoCloseable {

  static final KeyPair KEYS = generateKeys();
  static final String APP_ID = "424242";

  private static final ObjectMapper JSON = JsonMapper.builder().build();
  private static final Pattern REDELIVER_PATH =
      Pattern.compile("/app/hook/deliveries/(\\d+)/attempts");
  private static final Pattern TOKEN_PATH =
      Pattern.compile("/app/installations/(\\d+)/access_tokens");

  private final HttpServer server;
  private final Map<Long, Installation> installations = new ConcurrentHashMap<>();
  private final Map<String, Long> issuedTokens = new ConcurrentHashMap<>();

  /** Los cuerpos de las peticiones de token, en orden. */
  final List<JsonNode> tokenRequests = new CopyOnWriteArrayList<>();

  /** Lo que devuelve {@code GET /app/hook/deliveries}: los intentos de entrega de la App. */
  final List<Map<String, Object>> hookDeliveries = new CopyOnWriteArrayList<>();

  /** Los ids de intento que se han pedido repetir ({@code POST …/attempts}), en orden. */
  final List<Long> redelivered = new CopyOnWriteArrayList<>();

  /** Si no es 0, {@code GET /app} responde con este código (p. ej. 401: credenciales malas). */
  volatile int appStatus;

  /** Las PR de cada repositorio ({@code owner/nombre}), por número (EN-A). */
  final Map<String, Map<Long, Map<String, Object>>> pulls = new ConcurrentHashMap<>();

  /** Las peticiones a {@code /repos/…}: método, ruta y cuerpo, en orden. */
  final List<String> repoRequests = new CopyOnWriteArrayList<>();

  /** Si no es 0, abrir una PR responde con este código (p. ej. 422 o 502). */
  volatile int createPullStatus;

  /** Si no es 0, fusionar una PR responde con este código (p. ej. 405: no se puede fusionar). */
  volatile int mergeStatus;

  /** Si no es 0, borrar una rama responde con este código. */
  volatile int deleteBranchStatus;

  /** Las ramas borradas ({@code DELETE …/git/refs/heads/…}), en orden (EN-C). */
  final List<String> deletedBranches = new CopyOnWriteArrayList<>();

  /** El commit de cada rama, para la cabeza de sus PR; si no está, uno de ceros. */
  final Map<String, String> branchShas = new ConcurrentHashMap<>();

  public FakeGitHub() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    server.createContext("/", this::handle);
    server.start();
  }

  public String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  /** Instala la App en una cuenta con esos repositorios ({@code owner/nombre}). */
  void install(long id, String account, String... repositories) {
    installations.put(id, new Installation(id, account, null, List.of(repositories)));
  }

  void suspend(long id) {
    installations.computeIfPresent(
        id, (k, i) -> new Installation(i.id(), i.account(), Instant.now(), i.repositories()));
  }

  void uninstall(long id) {
    installations.remove(id);
  }

  void reset() {
    installations.clear();
    issuedTokens.clear();
    tokenRequests.clear();
    hookDeliveries.clear();
    redelivered.clear();
    appStatus = 0;
    pulls.clear();
    repoRequests.clear();
    createPullStatus = 0;
    branchShas.clear();
    mergeStatus = 0;
    deleteBranchStatus = 0;
    deletedBranches.clear();
    checkRuns.clear();
    statuses.clear();
    annotations.clear();
    jobLogs.clear();
    comments.clear();
    deniedPermissions.clear();
    logDownloadAuthorized = false;
  }

  /** Los check-runs de cada commit, por SHA (EN-B). */
  final Map<String, List<Map<String, Object>>> checkRuns = new ConcurrentHashMap<>();

  /** Los statuses de cada commit, por SHA (EN-B). */
  final Map<String, List<Map<String, Object>>> statuses = new ConcurrentHashMap<>();

  /** Las anotaciones de cada check-run, por id. */
  final Map<Long, List<Map<String, Object>>> annotations = new ConcurrentHashMap<>();

  /** El log de cada job de Actions, por id; se descarga de una URL firmada sin token. */
  final Map<Long, String> jobLogs = new ConcurrentHashMap<>();

  /** Los comentarios en las PR: {@code owner/nombre#número: texto}. */
  final List<String> comments = new CopyOnWriteArrayList<>();

  /** Permisos que la instalación no ha aceptado: pedir un token con ellos responde 422. */
  final java.util.Set<String> deniedPermissions = ConcurrentHashMap.newKeySet();

  /** Si alguna descarga de un log llevó la cabecera Authorization. */
  volatile boolean logDownloadAuthorized;

  /** Añade un check-run al commit {@code sha} y devuelve su id. */
  long checkRun(String sha, String name, String app, String status, String conclusion) {
    long id = 9000 + checkRuns.values().stream().mapToInt(List::size).sum();
    Map<String, Object> run = new java.util.LinkedHashMap<>();
    run.put("id", id);
    run.put("name", name);
    run.put("status", status);
    run.put("conclusion", conclusion);
    run.put("html_url", "https://github.com/runs/" + id);
    run.put("app", Map.of("slug", app));
    run.put("output", Map.of("title", name + ": " + (conclusion == null ? status : conclusion)));
    checkRuns.computeIfAbsent(sha, k -> new CopyOnWriteArrayList<>()).add(run);
    return id;
  }

  /** Cambia el estado de un check-run del commit. */
  void finishCheckRun(String sha, long id, String conclusion) {
    for (Map<String, Object> run : checkRuns.getOrDefault(sha, List.of())) {
      if (((Number) run.get("id")).longValue() == id) {
        run.put("status", "completed");
        run.put("conclusion", conclusion);
      }
    }
  }

  /** Añade un status al commit {@code sha}. */
  void status(String sha, String context, String state) {
    statuses
        .computeIfAbsent(sha, k -> new CopyOnWriteArrayList<>())
        .add(Map.of("context", context, "state", state, "description", context + " " + state));
  }

  /** Una PR del repositorio {@code fullName}, o {@code null}. */
  Map<String, Object> pull(String fullName, long number) {
    return pulls.getOrDefault(fullName, Map.of()).get(number);
  }

  /** Un intento de entrega de la App, como lo lista {@code GET /app/hook/deliveries}. */
  void hookDelivery(long id, String guid, int statusCode) {
    hookDeliveries.add(
        Map.of("id", id, "guid", guid, "status_code", statusCode, "event", "pull_request"));
  }

  /** El id que da este GitHub a un repositorio: estable a partir de su nombre. */
  static long repositoryId(String fullName) {
    return 1000L + Math.floorMod(fullName.toLowerCase().hashCode(), 1_000_000);
  }

  @Override
  public void close() {
    server.stop(0);
  }

  private void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      String path = exchange.getRequestURI().getPath();
      String method = exchange.getRequestMethod();
      String bearer =
          String.valueOf(exchange.getRequestHeaders().getFirst("Authorization"))
              .replaceFirst("^Bearer ", "");
      Matcher token = TOKEN_PATH.matcher(path);
      if (path.startsWith("/blob/logs/")) {
        logDownloadAuthorized |= exchange.getRequestHeaders().containsKey("Authorization");
        String log = jobLogs.get(Long.parseLong(path.substring("/blob/logs/".length())));
        byte[] bytes = String.valueOf(log).getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(log == null ? 404 : 200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
        return;
      }
      if (path.startsWith("/repos/")) {
        repos(exchange, method, path, bearer);
        return;
      }
      if (path.equals("/installation/repositories") && method.equals("GET")) {
        Long installation = issuedTokens.get(bearer);
        if (installation == null || !installations.containsKey(installation)) {
          send(exchange, 401, Map.of("message", "Bad credentials"));
          return;
        }
        List<Map<String, Object>> repositories = new ArrayList<>();
        for (String name : installations.get(installation).repositories()) {
          repositories.add(
              Map.of(
                  "id", repositoryId(name), "full_name", name, "private", name.endsWith("-priv")));
        }
        send(
            exchange,
            200,
            Map.of("total_count", repositories.size(), "repositories", repositories));
        return;
      }
      if (!validJwt(bearer)) {
        send(exchange, 401, Map.of("message", "A JSON web token could not be decoded"));
        return;
      }
      if (path.equals("/app") && method.equals("GET")) {
        if (appStatus != 0) {
          send(exchange, appStatus, Map.of("message", "Bad credentials"));
          return;
        }
        send(
            exchange,
            200,
            Map.of(
                "id",
                Long.parseLong(APP_ID),
                "slug",
                "skynet-de-prueba",
                "name",
                "Skynet de prueba",
                "owner",
                Map.of("login", "guilu"),
                "html_url",
                "https://github.com/apps/skynet-de-prueba"));
      } else if (path.equals("/app/installations") && method.equals("GET")) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Installation i : installations.values()) {
          Map<String, Object> item = new java.util.LinkedHashMap<>();
          item.put("id", i.id());
          item.put("account", Map.of("login", i.account(), "type", "User"));
          item.put("repository_selection", "selected");
          item.put("suspended_at", i.suspendedAt() == null ? null : i.suspendedAt().toString());
          list.add(item);
        }
        send(exchange, 200, list);
      } else if (path.equals("/app/hook/deliveries") && method.equals("GET")) {
        send(exchange, 200, hookDeliveries);
      } else if (REDELIVER_PATH.matcher(path).matches() && method.equals("POST")) {
        Matcher redeliver = REDELIVER_PATH.matcher(path);
        redeliver.matches();
        redelivered.add(Long.parseLong(redeliver.group(1)));
        send(exchange, 202, Map.of());
      } else if (token.matches() && method.equals("POST")) {
        long id = Long.parseLong(token.group(1));
        JsonNode body = JSON.readTree(exchange.getRequestBody().readAllBytes());
        tokenRequests.add(body);
        Installation installation = installations.get(id);
        boolean denied = false;
        for (String permission : deniedPermissions) {
          denied |= body.path("permissions").has(permission);
        }
        if (denied) {
          send(
              exchange,
              422,
              Map.of("message", "The permissions requested are not granted to this installation."));
        } else if (installation == null) {
          send(exchange, 404, Map.of("message", "Not Found"));
        } else if (installation.suspendedAt() != null) {
          send(exchange, 403, Map.of("message", "This installation has been suspended"));
        } else {
          String value = "ghs_" + "f".repeat(20) + issuedTokens.size() + "x".repeat(16);
          issuedTokens.put(value, id);
          send(
              exchange,
              201,
              Map.of(
                  "token",
                  value,
                  "expires_at",
                  Instant.now()
                      .plus(1, ChronoUnit.HOURS)
                      .truncatedTo(ChronoUnit.SECONDS)
                      .toString()));
        }
      } else {
        send(exchange, 404, Map.of("message", "Not Found"));
      }
    }
  }

  private static final Pattern REPO_PATH =
      Pattern.compile(
          "/repos/([^/]+/[^/]+)(/pulls(?:/(\\d+)(/merge)?)?|/issues/(\\d+)/labels"
              + "|/git/refs/heads/(.+))?");

  /** La API de un repositorio con un token de instalación: el repositorio y sus PR (EN-A). */
  private void repos(HttpExchange exchange, String method, String path, String bearer)
      throws IOException {
    Long installation = issuedTokens.get(bearer);
    if (installation != null && ci(exchange, method, path)) {
      return;
    }
    Matcher m = REPO_PATH.matcher(path);
    if (installation == null || !m.matches()) {
      send(exchange, installation == null ? 401 : 404, Map.of("message", "Bad credentials"));
      return;
    }
    String fullName = m.group(1);
    byte[] body = exchange.getRequestBody().readAllBytes();
    repoRequests.add(
        method
            + " "
            + path
            + (body.length == 0 ? "" : " " + new String(body, StandardCharsets.UTF_8)));
    Map<Long, Map<String, Object>> prs =
        pulls.computeIfAbsent(fullName, k -> new ConcurrentHashMap<>());
    if (m.group(2) == null && method.equals("GET")) {
      send(exchange, 200, Map.of("full_name", fullName, "default_branch", "main"));
    } else if ("/pulls".equals(m.group(2)) && method.equals("GET")) {
      String query = String.valueOf(exchange.getRequestURI().getQuery());
      String head = query.replaceAll(".*head=([^&]*).*", "$1");
      List<Map<String, Object>> open = new ArrayList<>();
      for (Map<String, Object> pr : prs.values()) {
        String owner = fullName.substring(0, fullName.indexOf('/'));
        if ("open".equals(pr.get("state"))
            && head.equals(owner + ":" + ((Map<?, ?>) pr.get("head")).get("ref"))) {
          open.add(pr);
        }
      }
      send(exchange, 200, open);
    } else if ("/pulls".equals(m.group(2)) && method.equals("POST")) {
      if (createPullStatus != 0) {
        send(exchange, createPullStatus, Map.of("message", "Validation Failed"));
        return;
      }
      JsonNode request = JSON.readTree(body);
      long number = 12 + prs.size();
      String branch = request.path("head").asString();
      Map<String, Object> pr = new java.util.LinkedHashMap<>();
      pr.put("number", number);
      pr.put("html_url", "https://github.com/" + fullName + "/pull/" + number);
      pr.put("state", "open");
      pr.put("title", request.path("title").asString());
      pr.put("body", request.path("body").asString());
      pr.put("draft", request.path("draft").asBoolean());
      pr.put("labels", new ArrayList<String>());
      pr.put("head", Map.of("ref", branch, "sha", branchShas.getOrDefault(branch, "0".repeat(40))));
      pr.put("base", Map.of("ref", request.path("base").asString()));
      prs.put(number, pr);
      send(exchange, 201, pr);
    } else if (m.group(3) != null && m.group(4) == null && method.equals("GET")) {
      Map<String, Object> pr = prs.get(Long.parseLong(m.group(3)));
      send(exchange, pr == null ? 404 : 200, pr == null ? Map.of("message", "Not Found") : pr);
    } else if (m.group(4) != null && method.equals("PUT")) {
      Map<String, Object> pr = prs.get(Long.parseLong(m.group(3)));
      JsonNode request = JSON.readTree(body);
      String head = String.valueOf(((Map<?, ?>) pr.get("head")).get("sha"));
      if (mergeStatus != 0) {
        send(exchange, mergeStatus, Map.of("message", "Pull Request is not mergeable"));
      } else if (!head.equals(request.path("sha").asString())) {
        send(exchange, 409, Map.of("message", "Head branch was modified. Review and try again."));
      } else {
        String sha = "e".repeat(40);
        pr.put("state", "closed");
        pr.put("merged", true);
        pr.put("merge_commit_sha", sha);
        pr.put("merge_method", request.path("merge_method").asString());
        send(exchange, 200, Map.of("sha", sha, "merged", true, "message", "Pull Request merged"));
      }
    } else if (m.group(6) != null && method.equals("DELETE")) {
      deletedBranches.add(m.group(6));
      exchange.sendResponseHeaders(deleteBranchStatus == 0 ? 204 : deleteBranchStatus, -1);
    } else if (m.group(3) != null && method.equals("PATCH")) {
      Map<String, Object> pr = prs.get(Long.parseLong(m.group(3)));
      if (pr == null) {
        send(exchange, 404, Map.of("message", "Not Found"));
        return;
      }
      JsonNode request = JSON.readTree(body);
      pr.put("title", request.path("title").asString());
      pr.put("body", request.path("body").asString());
      send(exchange, 200, pr);
    } else if (m.group(5) != null && method.equals("POST")) {
      Map<String, Object> pr = prs.get(Long.parseLong(m.group(5)));
      @SuppressWarnings("unchecked")
      List<String> labels = (List<String>) pr.get("labels");
      JSON.readTree(body).path("labels").forEach(l -> labels.add(l.asString()));
      send(exchange, 200, labels);
    } else {
      send(exchange, 404, Map.of("message", "Not Found"));
    }
  }

  private static final Pattern CI_PATH =
      Pattern.compile(
          "/repos/([^/]+/[^/]+)/(?:commits/([0-9a-f]+)/(check-runs|status)"
              + "|check-runs/(\\d+)/annotations|actions/jobs/(\\d+)/logs|issues/(\\d+)/comments)");

  /** La CI de un commit y los comentarios en las PR (EN-B). Si no es una de esas rutas, false. */
  private boolean ci(HttpExchange exchange, String method, String path) throws IOException {
    Matcher m = CI_PATH.matcher(path);
    if (!m.matches()) {
      return false;
    }
    byte[] body = exchange.getRequestBody().readAllBytes();
    repoRequests.add(
        method
            + " "
            + path
            + (body.length == 0 ? "" : " " + new String(body, StandardCharsets.UTF_8)));
    if ("check-runs".equals(m.group(3))) {
      List<Map<String, Object>> runs = checkRuns.getOrDefault(m.group(2), List.of());
      send(exchange, 200, Map.of("total_count", runs.size(), "check_runs", runs));
    } else if ("status".equals(m.group(3))) {
      send(exchange, 200, Map.of("statuses", statuses.getOrDefault(m.group(2), List.of())));
    } else if (m.group(4) != null) {
      send(exchange, 200, annotations.getOrDefault(Long.parseLong(m.group(4)), List.of()));
    } else if (m.group(5) != null) {
      exchange.getResponseHeaders().add("Location", url() + "/blob/logs/" + m.group(5));
      exchange.sendResponseHeaders(302, -1);
      exchange.close();
    } else {
      comments.add(
          m.group(1) + "#" + m.group(6) + ": " + JSON.readTree(body).path("body").asString());
      send(exchange, 201, Map.of("id", comments.size()));
    }
    return true;
  }

  private static boolean validJwt(String jwt) {
    String[] parts = jwt.split("\\.");
    if (parts.length != 3) {
      return false;
    }
    try {
      Signature verifier = Signature.getInstance("SHA256withRSA");
      verifier.initVerify(KEYS.getPublic());
      verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
      if (!verifier.verify(Base64.getUrlDecoder().decode(parts[2]))) {
        return false;
      }
      JsonNode claims = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
      long now = Instant.now().getEpochSecond();
      return claims.path("iss").asString().equals(APP_ID)
          && claims.path("iat").asLong() <= now
          && claims.path("exp").asLong() > now
          && claims.path("exp").asLong() - claims.path("iat").asLong() <= 600;
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      return false;
    }
  }

  private static void send(HttpExchange exchange, int status, Object body) throws IOException {
    byte[] bytes = JSON.writeValueAsBytes(body);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
  }

  /** La clave privada en PKCS#1 ({@code BEGIN RSA PRIVATE KEY}), como la da GitHub. */
  static String pkcs1Pem(KeyPair keys) {
    byte[] pkcs8 = keys.getPrivate().getEncoded();
    // PKCS#8 de RSA: SEQUENCE { INTEGER 0, AlgorithmIdentifier, OCTET STRING { PKCS#1 } }. Con
    // 2048 bits, la cabecera hasta el contenido del OCTET STRING mide 26 bytes.
    byte[] pkcs1 = Arrays.copyOfRange(pkcs8, 26, pkcs8.length);
    return "-----BEGIN RSA PRIVATE KEY-----\n"
        + Base64.getMimeEncoder().encodeToString(pkcs1)
        + "\n-----END RSA PRIVATE KEY-----\n";
  }

  /** Escribe la clave privada (PKCS#1) en un fichero temporal y devuelve su ruta. */
  static Path writeKey() {
    try {
      Path file = Files.createTempFile("skynet-github-app", ".pem");
      Files.writeString(file, pkcs1Pem(KEYS));
      return file;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static KeyPair generateKeys() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private record Installation(
      long id, String account, Instant suspendedAt, List<String> repositories) {}
}
