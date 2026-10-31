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
        if (installation == null) {
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
      Pattern.compile("/repos/([^/]+/[^/]+)(/pulls(?:/(\\d+))?|/issues/(\\d+)/labels)?");

  /** La API de un repositorio con un token de instalación: el repositorio y sus PR (EN-A). */
  private void repos(HttpExchange exchange, String method, String path, String bearer)
      throws IOException {
    Long installation = issuedTokens.get(bearer);
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
    } else if (m.group(4) != null && method.equals("POST")) {
      Map<String, Object> pr = prs.get(Long.parseLong(m.group(4)));
      @SuppressWarnings("unchecked")
      List<String> labels = (List<String>) pr.get("labels");
      JSON.readTree(body).path("labels").forEach(l -> labels.add(l.asString()));
      send(exchange, 200, labels);
    } else {
      send(exchange, 404, Map.of("message", "Not Found"));
    }
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
