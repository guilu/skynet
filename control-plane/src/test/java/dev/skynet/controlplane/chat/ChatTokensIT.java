package dev.skynet.controlplane.chat;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skynet.controlplane.support.IntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * El token de una conversación (CH-B): solo lee las rutas de la lista, caduca, tiene tope de
 * llamadas, deja de valer al archivar la conversación o al revocarlo, y no abre sesión.
 */
class ChatTokensIT extends IntegrationTest {

  @Autowired ChatTokens tokens;

  @Test
  void aTokenReadsTheListedRoutesAndNothingElse() {
    String project =
        post("/api/projects", Map.of("key", "CHB", "name", "akademia")).path("id").asString();
    String conversation = conversation();
    JsonNode issued = post("/api/conversations/" + conversation + "/chat-token", Map.of());
    String token = issued.path("token").asString();
    assertThat(token).startsWith(ChatTokens.PREFIX);
    assertThat(Instant.parse(issued.path("expiresAt").asString())).isAfter(Instant.now());

    RestClient skyny = bearer(token);
    assertThat(status(skyny, HttpMethod.GET, "/api/dashboard")).isEqualTo(200);
    assertThat(status(skyny, HttpMethod.GET, "/api/projects")).isEqualTo(200);
    assertThat(status(skyny, HttpMethod.GET, "/api/projects/" + project + "/overview"))
        .isEqualTo(200);
    assertThat(status(skyny, HttpMethod.GET, "/api/workflow-runs?q=x")).isEqualTo(200);
    assertThat(status(skyny, HttpMethod.GET, "/api/usage/windows")).isEqualTo(200);

    // Nada que cambie algo, ni lo que no está en la lista.
    assertThat(status(skyny, HttpMethod.POST, "/api/projects")).isEqualTo(403);
    assertThat(status(skyny, HttpMethod.POST, "/api/conversations/" + conversation + "/archive"))
        .isEqualTo(403);
    assertThat(status(skyny, HttpMethod.GET, "/api/runners")).isEqualTo(403);
    assertThat(status(skyny, HttpMethod.GET, "/api/conversations")).isEqualTo(403);
    assertThat(status(skyny, HttpMethod.GET, "/api/settings/quota")).isEqualTo(403);
    assertThat(status(skyny, HttpMethod.GET, "/api/artifacts/" + UUID.randomUUID() + "/content"))
        .isEqualTo(403);

    // Un token que no existe, 401.
    assertThat(status(bearer(ChatTokens.PREFIX + "inventado"), HttpMethod.GET, "/api/dashboard"))
        .isEqualTo(401);

    // Pedir otro deja sin valor el anterior.
    String second =
        post("/api/conversations/" + conversation + "/chat-token", Map.of())
            .path("token")
            .asString();
    assertThat(status(skyny, HttpMethod.GET, "/api/dashboard")).isEqualTo(401);
    assertThat(status(bearer(second), HttpMethod.GET, "/api/dashboard")).isEqualTo(200);
    tokens.revoke(UUID.fromString(conversation));
    assertThat(status(bearer(second), HttpMethod.GET, "/api/dashboard")).isEqualTo(401);
  }

  @Test
  void aTokenExpiresRunsOutAndDiesWithItsConversation() {
    String conversation = conversation();
    UUID id = UUID.fromString(conversation);

    String expiring = tokens.issue(id).token();
    jdbc.sql("UPDATE conversation_token SET expires_at = ? WHERE conversation_id = ?")
        .params(Timestamp.from(Instant.now().minusSeconds(1)), id)
        .update();
    assertThat(status(bearer(expiring), HttpMethod.GET, "/api/dashboard")).isEqualTo(401);

    String exhausted = tokens.issue(id).token();
    jdbc.sql("UPDATE conversation_token SET calls = ? WHERE conversation_id = ?")
        .params(ChatTokens.MAX_CALLS - 1, id)
        .update();
    assertThat(status(bearer(exhausted), HttpMethod.GET, "/api/dashboard")).isEqualTo(200);
    assertThat(status(bearer(exhausted), HttpMethod.GET, "/api/dashboard")).isEqualTo(401);

    String archived = tokens.issue(id).token();
    post("/api/conversations/" + conversation + "/archive", Map.of());
    assertThat(status(bearer(archived), HttpMethod.GET, "/api/dashboard")).isEqualTo(401);
    // Una archivada ya no recibe token.
    assertThat(status(http, HttpMethod.POST, "/api/conversations/" + conversation + "/chat-token"))
        .isEqualTo(409);
  }

  @Test
  void aTokenOpensNoSession() {
    String token = tokens.issue(UUID.fromString(conversation())).token();
    ResponseEntity<String> response =
        bearer(token).get().uri("/api/dashboard").retrieve().toEntity(String.class);
    // Sin cookie de sesión (la de CSRF la pone cualquier respuesta).
    assertThat(response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE))
        .noneMatch(c -> c.startsWith("JSESSIONID") || c.startsWith("SESSION"));
    // Sin token, tampoco se entra: la petición siguiente vuelve a pedir credenciales.
    RestClient anonymous = RestClient.builder().baseUrl("http://localhost:" + port).build();
    assertThat(status(anonymous, HttpMethod.GET, "/api/dashboard")).isEqualTo(401);
  }

  @Test
  void theRunnerApiIsLeftToItsOwnTokens() {
    // Con un token de conversación, la API del runner responde por sí misma (401 por su token),
    // sin el 403 del filtro de solo lectura.
    String token = tokens.issue(UUID.fromString(conversation())).token();
    int status = status(bearer(token), HttpMethod.POST, "/api/runner/heartbeat");
    assertThat(status).isNotEqualTo(403);
  }

  @Test
  void onlyGetsOnTheListAreReadable() {
    assertThat(ChatTokens.readable("GET", "/api/workflow-runs/" + UUID.randomUUID())).isTrue();
    assertThat(ChatTokens.readable("GET", "/api/projects/x/wiki/lo-hecho")).isTrue();
    assertThat(ChatTokens.readable("POST", "/api/workflow-runs")).isFalse();
    assertThat(ChatTokens.readable("GET", "/api/workflow-runs/x/cancel")).isFalse();
    assertThat(ChatTokens.readable("GET", "/api/projects/x/wiki/a/b")).isFalse();
    assertThat(ChatTokens.readable("GET", "/api/runners")).isFalse();
    assertThat(ChatTokens.readable("GET", null)).isFalse();
    assertThat(ChatTokens.isChatToken("Bearer skc_x")).isTrue();
    assertThat(ChatTokens.isChatToken("Bearer skm_x")).isFalse();
    assertThat(ChatTokens.isChatToken(null)).isFalse();
  }

  private String conversation() {
    return post("/api/conversations", Map.of("title", "Pruebas del MCP"))
        .path("conversation")
        .path("id")
        .asString();
  }

  private RestClient bearer(String token) {
    return RestClient.builder()
        .baseUrl("http://localhost:" + port)
        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .build();
  }

  private static int status(RestClient client, HttpMethod method, String path) {
    return client
        .method(method)
        .uri(path)
        .exchange((request, response) -> response.getStatusCode().value());
  }

  private JsonNode post(String path, Object body) {
    return http.post().uri(path).body(body).retrieve().body(JsonNode.class);
  }
}
