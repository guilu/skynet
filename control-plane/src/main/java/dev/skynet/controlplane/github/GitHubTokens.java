package dev.skynet.controlplane.github;

import dev.skynet.controlplane.shared.TimeSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Tokens de instalación de la App. Duran una hora; se guardan solo en memoria y se renuevan cuando
 * les quedan menos de {@link #RENEW_BEFORE}. Nunca se escriben en la base de datos, en los logs ni
 * en los eventos.
 */
@Service
public class GitHubTokens {

  static final Duration RENEW_BEFORE = Duration.ofMinutes(5);

  /** Lo que basta para listar los repositorios de una instalación. */
  static final Map<String, String> METADATA_READ = Map.of("metadata", "read");

  private final GitHubApi api;
  private final TimeSource time;
  private final Map<Key, Token> cache = new ConcurrentHashMap<>();

  GitHubTokens(GitHubApi api, TimeSource time) {
    this.api = api;
    this.time = time;
  }

  /**
   * Un token de {@code installationId} limitado a un repositorio de GitHub y a {@code permissions}
   * (vacío: todos los de la App).
   *
   * @throws GitHubException si GitHub no lo da (App sin configurar, instalación suspendida o sin
   *     ese repositorio)
   */
  public String forRepository(
      long installationId, long repositoryId, Map<String, String> permissions) {
    return token(new Key(installationId, repositoryId, Map.copyOf(permissions)));
  }

  /** Un token de solo lectura de metadatos para toda la instalación (sincronizar). */
  String forInstallation(long installationId) {
    return token(new Key(installationId, null, METADATA_READ));
  }

  /** Olvida los tokens de una instalación (desinstalada o suspendida). */
  void forget(long installationId) {
    cache.keySet().removeIf(key -> key.installationId() == installationId);
  }

  private String token(Key key) {
    Instant now = time.now();
    Token cached = cache.get(key);
    if (cached != null && cached.expiresAt().minus(RENEW_BEFORE).isAfter(now)) {
      return cached.value();
    }
    JsonNode created =
        api.createToken(
            key.installationId(),
            key.repositoryId() == null ? List.of() : List.of(key.repositoryId()),
            key.permissions());
    Token token =
        new Token(
            created.path("token").asString(), Instant.parse(created.path("expires_at").asString()));
    cache.put(key, token);
    return token.value();
  }

  private record Key(long installationId, Long repositoryId, Map<String, String> permissions) {}

  private record Token(String value, Instant expiresAt) {
    @Override
    public String toString() {
      return "Token[expiresAt=" + expiresAt + "]";
    }
  }
}
