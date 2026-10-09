package dev.skynet.controlplane.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skynet.controlplane.support.IntegrationTest;
import dev.skynet.protocol.runner.RunnerHeartbeat;
import dev.skynet.protocol.runner.RunnerRegistration;
import java.io.IOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Acceso a la API: sesión con CSRF para la web, HTTP Basic para scripts y token del runner. */
class SecurityIT extends IntegrationTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private CookieManager cookies;
  private HttpClient browser;

  @BeforeEach
  void browser() {
    cookies = new CookieManager();
    browser = HttpClient.newBuilder().cookieHandler(cookies).build();
  }

  @AfterEach
  void close() {
    browser.close();
  }

  @Test
  void withoutASessionTheApiAnswers401WithoutAskingTheBrowserForCredentials() throws Exception {
    HttpResponse<String> response = send("GET", "/api/projects", null, false);
    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE)).isEmpty();

    assertThat(send("GET", "/actuator/health", null, false).statusCode()).isEqualTo(200);
    // La paleta se lee sin sesión (el login ya sale con ella), pero no se cambia.
    assertThat(send("GET", "/api/settings/appearance", null, false).statusCode()).isEqualTo(200);
    send("GET", "/api/auth/session", null, false);
    assertThat(send("PUT", "/api/settings/appearance", "{\"colors\":{}}", true).statusCode())
        .isEqualTo(401);
  }

  @Test
  void loginOpensASessionThatNeedsTheCsrfTokenToChangeAnything() throws Exception {
    // La primera respuesta, aunque sea un 401, ya deja la cookie con el token CSRF.
    assertThat(send("GET", "/api/auth/session", null, false).statusCode()).isEqualTo(401);
    assertThat(xsrf()).isNotNull();

    String credentials =
        "{\"username\":\"" + ADMIN_USER + "\",\"password\":\"" + ADMIN_SECRET + "\"}";
    assertThat(send("POST", "/api/auth/login", credentials, false).statusCode()).isEqualTo(403);
    assertThat(
            send("POST", "/api/auth/login", "{\"username\":\"admin\",\"password\":\"x\"}", true)
                .statusCode())
        .isEqualTo(401);
    HttpResponse<String> login = send("POST", "/api/auth/login", credentials, true);
    assertThat(login.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(login.body()).path("username").asString()).isEqualTo(ADMIN_USER);

    HttpResponse<String> session = send("GET", "/api/auth/session", null, false);
    assertThat(session.statusCode()).isEqualTo(200);
    assertThat(send("GET", "/api/projects", null, false).statusCode()).isEqualTo(200);

    String project = "{\"key\":\"SEC\",\"name\":\"Seguridad\"}";
    assertThat(send("POST", "/api/projects", project, false).statusCode()).isEqualTo(403);
    assertThat(send("POST", "/api/projects", project, true).statusCode()).isEqualTo(201);

    assertThat(send("POST", "/api/auth/logout", null, true).statusCode()).isEqualTo(204);
    assertThat(send("GET", "/api/projects", null, false).statusCode()).isEqualTo(401);
  }

  @Test
  void loginChangesTheSessionId() throws Exception {
    send("GET", "/api/auth/session", null, false);
    String credentials =
        "{\"username\":\"" + ADMIN_USER + "\",\"password\":\"" + ADMIN_SECRET + "\"}";
    send("POST", "/api/auth/login", credentials, true);
    String first = cookie("JSESSIONID");
    send("POST", "/api/auth/login", credentials, true);
    assertThat(cookie("JSESSIONID")).isNotNull().isNotEqualTo(first);
  }

  @Test
  void scriptsUseHttpBasicWithoutCsrf() {
    JsonNode project =
        http.post()
            .uri("/api/projects")
            .body(java.util.Map.of("key", "API", "name", "Script"))
            .retrieve()
            .body(JsonNode.class);
    assertThat(project.path("key").asString()).isEqualTo("API");
  }

  @Test
  void revokingARunnerTokenLocksItOutUntilItRegistersAgain() throws Exception {
    JsonNode registered =
        http.post()
            .uri("/api/runner/register")
            .body(new RunnerRegistration("laptop", RUNNER_REGISTRATION_TOKEN, "dev", 1, null))
            .retrieve()
            .body(JsonNode.class);
    UUID runnerId = UUID.fromString(registered.path("runnerId").asString());
    String token = registered.path("token").asString();
    assertThat(heartbeat(token)).isEqualTo(204);

    http.post().uri("/api/runners/{id}/revoke", runnerId).retrieve().toBodilessEntity();
    assertThat(heartbeat(token)).isEqualTo(401);
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM event WHERE aggregate_id = ? AND event_type ="
                        + " 'runner.token.revoked'")
                .param(runnerId)
                .query(Long.class)
                .single())
        .isEqualTo(1);

    JsonNode again =
        http.post()
            .uri("/api/runner/register")
            .body(new RunnerRegistration("laptop", RUNNER_REGISTRATION_TOKEN, "dev", 1, null))
            .retrieve()
            .body(JsonNode.class);
    assertThat(again.path("runnerId").asString()).isEqualTo(runnerId.toString());
    assertThat(heartbeat(again.path("token").asString())).isEqualTo(204);

    send("GET", "/api/auth/session", null, false);
    assertThat(
            send("POST", "/api/runners/" + UUID.randomUUID() + "/revoke", null, true).statusCode())
        .isEqualTo(401);
  }

  private int heartbeat(String token) throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(base() + "/api/runner/heartbeat"))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    JSON.writeValueAsString(new RunnerHeartbeat(1, List.of()))))
            .build();
    try (HttpClient client = HttpClient.newHttpClient()) {
      return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
  }

  /** Petición del «navegador»: con sus cookies y, si se pide, la cabecera CSRF. */
  private HttpResponse<String> send(String method, String path, String body, boolean csrf)
      throws IOException, InterruptedException {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(base() + path))
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
    if (body != null) {
      request.header("Content-Type", "application/json");
    }
    if (csrf && xsrf() != null) {
      request.header("X-XSRF-TOKEN", xsrf());
    }
    return browser.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private String xsrf() {
    return cookie("XSRF-TOKEN");
  }

  private String cookie(String name) {
    return cookies.getCookieStore().getCookies().stream()
        .filter(c -> c.getName().equals(name))
        .map(HttpCookie::getValue)
        .findFirst()
        .orElse(null);
  }

  private String base() {
    return "http://localhost:" + port;
  }
}
