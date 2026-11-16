package dev.skynet.controlplane.chat;

import dev.skynet.controlplane.shared.ConflictException;
import dev.skynet.controlplane.shared.TimeSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.server.PathContainer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * El token de una conversación (CH-B, docs/chat.md «El MCP de Skynet»): con él, el MCP de Skynet
 * lee la API en nombre de Skyny. Solo vale para {@code GET} en las rutas de {@link #READABLE},
 * caduca a los {@link #LIFETIME} y tiene un tope de {@link #MAX_CALLS} llamadas; no sirve para nada
 * que cambie algo: las acciones se proponen y las confirma la persona desde la web.
 */
@Service
public class ChatTokens {

  /** El prefijo de los tokens de conversación, para reconocerlos en la cabecera. */
  public static final String PREFIX = "skc_";

  /** Cuánto vale un token: de sobra para un turno. */
  public static final Duration LIFETIME = Duration.ofMinutes(30);

  /** Cuántas llamadas puede hacer un token. */
  public static final int MAX_CALLS = 200;

  /** Lo que Skyny puede leer de la API: solo {@code GET}, solo estas rutas. */
  static final List<String> READABLE =
      List.of(
          "/api/dashboard",
          "/api/projects",
          "/api/projects/{id}/overview",
          "/api/projects/{id}/memory",
          "/api/projects/{id}/wiki",
          "/api/projects/{id}/wiki/{slug}",
          "/api/memory/{id}",
          "/api/work-items",
          "/api/work-items/{id}/summary",
          "/api/work-items/{id}/runs",
          "/api/workflow-runs",
          "/api/workflow-runs/{id}",
          "/api/workflow-runs/{id}/findings",
          "/api/workflow-runs/{id}/pull-requests",
          "/api/approvals",
          "/api/approvals/{id}",
          "/api/workflows",
          "/api/workflows/{key}",
          "/api/usage/windows",
          "/api/usage/agents",
          "/api/usage/runs");

  private static final List<PathPattern> PATTERNS =
      READABLE.stream().map(PathPatternParser.defaultInstance::parse).toList();

  private static final SecureRandom RANDOM = new SecureRandom();

  /** Un token recién emitido: solo existe en claro en esta respuesta. */
  public record Issued(String token, Instant expiresAt) {}

  private final JdbcClient jdbc;
  private final TimeSource time;

  ChatTokens(JdbcClient jdbc, TimeSource time) {
    this.jdbc = jdbc;
    this.time = time;
  }

  /**
   * Si una petición con token de conversación se puede hacer: un {@code GET} a una ruta de {@link
   * #READABLE}.
   */
  public static boolean readable(String method, String path) {
    if (!"GET".equals(method) || path == null) {
      return false;
    }
    PathContainer container = PathContainer.parsePath(path);
    return PATTERNS.stream().anyMatch(p -> p.matches(container));
  }

  /** Si la cabecera {@code Authorization} lleva un token de conversación. */
  public static boolean isChatToken(String authorization) {
    return authorization != null && authorization.startsWith("Bearer " + PREFIX);
  }

  /**
   * Un token nuevo para la conversación; el anterior, si lo había, deja de valer. Una conversación
   * archivada no lo recibe.
   */
  @Transactional
  public Issued issue(UUID conversationId) {
    Boolean archived =
        jdbc.sql("SELECT archived_at IS NOT NULL FROM conversation WHERE id = ?")
            .param(conversationId)
            .query(Boolean.class)
            .optional()
            .orElseThrow(
                () ->
                    new dev.skynet.controlplane.shared.NotFoundException(
                        "Conversación", conversationId));
    if (archived) {
      throw new ConflictException("La conversación está archivada");
    }
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    String token = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    Instant now = time.now();
    Instant expires = now.plus(LIFETIME);
    jdbc.sql(
            "INSERT INTO conversation_token (conversation_id, token_hash, calls, created_at,"
                + " expires_at) VALUES (:id, :hash, 0, :now, :expires)"
                + " ON CONFLICT (conversation_id) DO UPDATE SET token_hash = EXCLUDED.token_hash,"
                + " calls = 0, created_at = EXCLUDED.created_at, expires_at = EXCLUDED.expires_at")
        .param("id", conversationId)
        .param("hash", hash(token))
        .param("now", Timestamp.from(now))
        .param("expires", Timestamp.from(expires))
        .update();
    return new Issued(token, expires);
  }

  /** El token de la conversación deja de valer. */
  @Transactional
  public void revoke(UUID conversationId) {
    jdbc.sql("DELETE FROM conversation_token WHERE conversation_id = ?")
        .param(conversationId)
        .update();
  }

  /**
   * La conversación de la cabecera {@code Authorization: Bearer skc_…}, si el token vale: existe,
   * no ha caducado, su conversación no está archivada y no ha llegado al tope de llamadas. Cuenta
   * la llamada.
   */
  @Transactional
  public Optional<UUID> authenticate(String authorization) {
    if (!isChatToken(authorization)) {
      return Optional.empty();
    }
    String hash = hash(authorization.substring("Bearer ".length()).strip());
    return jdbc.sql(
            "UPDATE conversation_token t SET calls = t.calls + 1 FROM conversation c"
                + " WHERE c.id = t.conversation_id AND t.token_hash = ? AND t.expires_at > ?"
                + " AND t.calls < ? AND c.archived_at IS NULL RETURNING t.conversation_id")
        .params(hash, Timestamp.from(time.now()), MAX_CALLS)
        .query(UUID.class)
        .optional();
  }

  static String hash(String token) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
