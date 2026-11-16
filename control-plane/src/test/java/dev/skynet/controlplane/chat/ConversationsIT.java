package dev.skynet.controlplane.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skynet.controlplane.support.IntegrationTest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;

/**
 * Las conversaciones del chat con Skyny (CH-A): se crean con o sin contexto, se escribe en ellas,
 * se leen con sus mensajes en orden, se listan por actividad y se archivan; cada cambio deja su
 * evento {@code chat.*}. Todavía no contesta nadie.
 */
class ConversationsIT extends IntegrationTest {

  @Test
  void aConversationKeepsItsMessagesInOrder() {
    JsonNode created =
        post("/api/conversations", Map.of("message", "¿Qué hay en marcha?\nY otra línea"));
    JsonNode conversation = created.path("conversation");
    String id = conversation.path("id").asString();
    assertThat(conversation.path("title").asString()).isEqualTo("¿Qué hay en marcha?");
    assertThat(conversation.path("provider").asString()).isEqualTo("claude-code");
    assertThat(conversation.path("model").asString()).isEqualTo("haiku");
    assertThat(conversation.path("createdBy").asString()).isEqualTo(ADMIN_USER);
    assertThat(conversation.path("projectId").isNull()).isTrue();
    assertThat(created.path("messages")).hasSize(1);
    assertThat(created.path("messages").get(0).path("author").asString()).isEqualTo("USER");

    JsonNode posted = post("/api/conversations/" + id + "/messages", Map.of("body", "  ¿Y ayer? "));
    assertThat(posted.path("message").path("seq").asInt()).isEqualTo(2);
    assertThat(posted.path("message").path("body").asString()).isEqualTo("¿Y ayer?");
    // Nadie contesta todavía: la conversación se queda sin conexión.
    assertThat(posted.path("conversation").path("state").asString()).isEqualTo("OFFLINE");
    assertThat(posted.path("conversation").path("messageCount").asInt()).isEqualTo(2);

    JsonNode thread = get("/api/conversations/" + id);
    assertThat(thread.path("messages").valueStream().map(m -> m.path("body").asString()))
        .containsExactly("¿Qué hay en marcha?\nY otra línea", "¿Y ayer?");
    assertThat(thread.path("messages").get(1).path("cards")).isEmpty();

    // Cada cambio, su evento, en orden.
    assertThat(eventTypes(id))
        .containsExactly(
            "chat.conversation.created", "chat.message.created", "chat.message.created");
  }

  @Test
  void theContextIsTheProjectOrTheWorkItem() {
    String project =
        post("/api/projects", Map.of("key", "CHA", "name", "akademia")).path("id").asString();
    String other =
        post("/api/projects", Map.of("key", "OTR", "name", "otro")).path("id").asString();
    JsonNode item =
        post(
            "/api/projects/" + project + "/work-items",
            Map.of("title", "Exportar notas", "type", "FEATURE"));

    // Con el trabajo basta: el proyecto sale de él.
    JsonNode fromItem =
        post("/api/conversations", Map.of("workItemId", item.path("id").asString()))
            .path("conversation");
    assertThat(fromItem.path("projectId").asString()).isEqualTo(project);
    assertThat(fromItem.path("projectKey").asString()).isEqualTo("CHA");
    assertThat(fromItem.path("workItemKey").asString()).isEqualTo(item.path("key").asString());
    assertThat(fromItem.path("title").asString()).isEqualTo("Conversación nueva");
    assertThat(fromItem.path("state").asString()).isEqualTo("IDLE");

    JsonNode fromProject =
        post("/api/conversations", Map.of("projectId", project, "title", "Sobre akademia"))
            .path("conversation");
    assertThat(fromProject.path("projectName").asString()).isEqualTo("akademia");
    assertThat(fromProject.path("workItemId").isNull()).isTrue();

    // Un trabajo de otro proyecto no vale, ni un proyecto que no existe.
    assertStatus(
        () ->
            post(
                "/api/conversations",
                Map.of("projectId", other, "workItemId", item.path("id").asString())),
        400);
    assertStatus(
        () -> post("/api/conversations", Map.of("projectId", UUID.randomUUID().toString())), 404);

    // La lista de un proyecto solo trae las suyas.
    post("/api/conversations", Map.of("title", "Sin contexto"));
    assertThat(titles(get("/api/conversations?projectId=" + project)))
        .containsExactlyInAnyOrder("Conversación nueva", "Sobre akademia");
    assertThat(get("/api/conversations")).hasSize(3);
  }

  @Test
  void theListGoesByLatestActivityAndHidesTheArchived() {
    String first = create("Primera");
    String second = create("Segunda");
    String third = create("Tercera");
    // Escribir en la primera la sube arriba.
    post("/api/conversations/" + first + "/messages", Map.of("body", "Sigo aquí"));
    assertThat(titles(get("/api/conversations"))).containsExactly("Primera", "Tercera", "Segunda");
    assertThat(titles(get("/api/conversations?limit=1"))).containsExactly("Primera");

    JsonNode archived = post("/api/conversations/" + third + "/archive", Map.of());
    assertThat(archived.path("archivedAt").isNull()).isFalse();
    // Archivar otra vez no hace nada.
    post("/api/conversations/" + third + "/archive", Map.of());
    assertThat(titles(get("/api/conversations"))).containsExactly("Primera", "Segunda");
    assertThat(titles(get("/api/conversations?archived=true"))).containsExactly("Tercera");
    assertThat(get("/api/conversations?archived=all")).hasSize(3);
    assertThat(eventTypes(third))
        .containsExactly("chat.conversation.created", "chat.conversation.archived");

    // En una archivada ya no se escribe, pero se sigue leyendo.
    assertStatus(
        () -> post("/api/conversations/" + third + "/messages", Map.of("body", "¿Hola?")), 409);
    assertThat(get("/api/conversations/" + third).path("conversation").path("title").asString())
        .isEqualTo("Tercera");
    assertThat(second).isNotBlank();
  }

  @Test
  void aMessageIsCheckedAndItsSecretsMasked() {
    String id = create("Pruebas");
    assertStatus(() -> post("/api/conversations/" + id + "/messages", Map.of("body", "  ")), 400);
    assertStatus(
        () ->
            post(
                "/api/conversations/" + id + "/messages",
                Map.of("body", "a".repeat(Conversations.MAX_BODY + 1))),
        400);
    Map<String, Object> missing = new HashMap<>();
    missing.put("body", null);
    assertStatus(() -> post("/api/conversations/" + id + "/messages", missing), 400);
    assertStatus(
        () ->
            post("/api/conversations/" + UUID.randomUUID() + "/messages", Map.of("body", "¿Hola?")),
        404);
    assertStatus(() -> get("/api/conversations/" + UUID.randomUUID()), 404);
    assertStatus(() -> get("/api/conversations?limit=0"), 400);

    String token = "ghp_" + "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6q7R8";
    JsonNode posted =
        post("/api/conversations/" + id + "/messages", Map.of("body", "Mi token es " + token));
    assertThat(posted.path("message").path("body").asString())
        .doesNotContain(token)
        .contains("[REDACTED]");
  }

  @Test
  void aLongFirstMessageGivesAShortTitle() {
    assertThat(Conversations.title(null, "x".repeat(200))).hasSize(Conversations.MAX_TITLE);
    assertThat(Conversations.title(null, "x".repeat(200))).endsWith("…");
    assertThat(Conversations.title("  ", "  Hola   Skyny \nmás")).isEqualTo("Hola Skyny");
    assertThat(Conversations.title(null, null)).isEqualTo("Conversación nueva");
  }

  private String create(String title) {
    return post("/api/conversations", Map.of("title", title))
        .path("conversation")
        .path("id")
        .asString();
  }

  private static List<String> titles(JsonNode list) {
    return list.valueStream().map(c -> c.path("title").asString()).toList();
  }

  private List<String> eventTypes(String conversation) {
    return jdbc.sql(
            "SELECT event_type FROM event WHERE aggregate_type = 'conversation'"
                + " AND aggregate_id = ? ORDER BY sequence")
        .param(UUID.fromString(conversation))
        .query(String.class)
        .list();
  }

  private static void assertStatus(Runnable call, int status) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            HttpClientErrorException.class,
            e -> assertThat(e.getStatusCode().value()).isEqualTo(status));
  }

  private JsonNode post(String path, Object body) {
    return http.post().uri(path).body(body).retrieve().body(JsonNode.class);
  }

  private JsonNode get(String path) {
    return http.get().uri(path).retrieve().body(JsonNode.class);
  }
}
