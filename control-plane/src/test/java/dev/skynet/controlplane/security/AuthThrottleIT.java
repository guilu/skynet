package dev.skynet.controlplane.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skynet.controlplane.support.IntegrationTest;
import java.io.IOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Bloqueo temporal tras varios fallos seguidos de login, HTTP Basic o registro del runner, con los
 * valores por defecto (10 fallos, 5 minutos). El fin del bloqueo lo prueba {@link
 * AuthThrottleTest}.
 */
class AuthThrottleIT extends IntegrationTest {

  private CookieManager cookies;
  private HttpClient client;

  @BeforeEach
  void client() {
    cookies = new CookieManager();
    client = HttpClient.newBuilder().cookieHandler(cookies).build();
  }

  @AfterEach
  void close() {
    client.close();
  }

  private static final int MAX_FAILURES = 10;

  @Test
  void afterTooManyFailedLoginsEvenTheRightPasswordWaits() throws Exception {
    for (int i = 0; i < MAX_FAILURES; i++) {
      assertThat(login("mala").statusCode()).isEqualTo(401);
    }

    HttpResponse<String> blocked = login(ADMIN_SECRET);
    assertThat(blocked.statusCode()).isEqualTo(429);
    assertThat(blocked.headers().firstValue("Retry-After")).contains("300");
    assertThat(blocked.body()).contains("Demasiados intentos");
  }

  @Test
  void aSuccessfulLoginClearsTheFailures() throws Exception {
    for (int i = 0; i < MAX_FAILURES - 1; i++) {
      assertThat(login("mala").statusCode()).isEqualTo(401);
    }
    assertThat(login(ADMIN_SECRET).statusCode()).isEqualTo(200);
    for (int i = 0; i < MAX_FAILURES - 1; i++) {
      assertThat(login("mala").statusCode()).isEqualTo(401);
    }

    assertThat(login(ADMIN_SECRET).statusCode()).isEqualTo(200);
  }

  @Test
  void basicAuthIsBlockedTooAfterTooManyWrongPasswords() throws Exception {
    for (int i = 0; i < MAX_FAILURES; i++) {
      assertThat(basic("mala").statusCode()).isEqualTo(401);
    }

    assertThat(basic(ADMIN_SECRET).statusCode()).isEqualTo(429);
  }

  @Test
  void runnerRegistrationHasItsOwnLockoutAndDoesNotBlockTheWeb() throws Exception {
    for (int i = 0; i < MAX_FAILURES; i++) {
      assertThat(register("malo").statusCode()).isEqualTo(401);
    }

    assertThat(register(RUNNER_REGISTRATION_TOKEN).statusCode()).isEqualTo(429);
    assertThat(basic(ADMIN_SECRET).statusCode()).isEqualTo(200);
  }

  private HttpResponse<String> login(String password) throws IOException, InterruptedException {
    if (xsrf() == null) {
      // La primera respuesta deja la cookie con el token CSRF.
      send(HttpRequest.newBuilder(uri("/api/auth/session")).GET());
    }
    return send(
        HttpRequest.newBuilder(uri("/api/auth/login"))
            .header("Content-Type", "application/json")
            .header("X-XSRF-TOKEN", xsrf())
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"username\":\"" + ADMIN_USER + "\",\"password\":\"" + password + "\"}")));
  }

  private HttpResponse<String> basic(String password) throws IOException, InterruptedException {
    String credentials =
        Base64.getEncoder()
            .encodeToString((ADMIN_USER + ":" + password).getBytes(StandardCharsets.UTF_8));
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(uri("/api/projects"))
                .header("Authorization", "Basic " + credentials)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> register(String token) throws IOException, InterruptedException {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(uri("/api/runner/register"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"name\":\"laptop\",\"registrationToken\":\""
                            + token
                            + "\",\"runnerVersion\":\"dev\",\"capacity\":1}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> send(HttpRequest.Builder request)
      throws IOException, InterruptedException {
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private String xsrf() {
    return cookies.getCookieStore().getCookies().stream()
        .filter(c -> c.getName().equals("XSRF-TOKEN"))
        .map(HttpCookie::getValue)
        .findFirst()
        .orElse(null);
  }

  private URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
  }
}
