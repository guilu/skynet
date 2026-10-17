package dev.skynet.runner.provider.codex;

import dev.skynet.protocol.runner.QuotaSample;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * La cuota de la cuenta de Codex. {@code codex exec --json} no la da: está en los eventos {@code
 * token_count} del fichero de la sesión ({@code
 * $CODEX_HOME/sessions/AAAA/MM/DD/rollout-…-<thread_id>.jsonl}) y en {@code codex app-server}
 * ({@code account/rateLimits/read}). Las dos formas tienen las ventanas {@code primary} y {@code
 * secondary}; el fichero las escribe en {@code snake_case} y el app-server en {@code camelCase}.
 * Ver {@code docs/usage-windows.md}.
 */
public final class CodexQuota {

  static final String PROVIDER = "codex";
  private static final ObjectMapper JSON = JsonMapper.builder().build();
  private static final long FIVE_HOURS = 300;
  private static final long WEEK = 7L * 24 * 60;

  private CodexQuota() {}

  /** El directorio de Codex: {@code CODEX_HOME} o, sin ella, {@code ~/.codex}. */
  static Path home(Map<String, String> env) {
    String home = env.get("CODEX_HOME");
    if (home != null && !home.isBlank()) {
      return Path.of(home);
    }
    return Path.of(env.getOrDefault("HOME", System.getProperty("user.home")), ".codex");
  }

  /**
   * La última cuota del fichero de la sesión {@code threadId}, o vacía si no lo encuentra o no
   * tiene ninguna.
   */
  static List<QuotaSample> fromSession(Path codexHome, String threadId) throws IOException {
    Optional<Path> file = sessionFile(codexHome, threadId);
    if (file.isEmpty()) {
      return List.of();
    }
    List<QuotaSample> last = List.of();
    try (Stream<String> lines = Files.lines(file.get())) {
      for (String line : (Iterable<String>) lines::iterator) {
        if (!line.contains("\"token_count\"")) {
          continue;
        }
        JsonNode node = read(line);
        JsonNode payload = node == null ? null : node.path("payload");
        if (payload == null || !"token_count".equals(payload.path("type").asString(null))) {
          continue;
        }
        Instant observed = instant(node.path("timestamp").asString(null));
        List<QuotaSample> samples = fromRateLimits(payload.path("rate_limits"), observed);
        if (!samples.isEmpty()) {
          last = samples;
        }
      }
    } catch (UncheckedIOException e) {
      throw e.getCause();
    }
    return last;
  }

  /** El fichero de la sesión: el que termina en {@code <threadId>.jsonl} bajo {@code sessions}. */
  static Optional<Path> sessionFile(Path codexHome, String threadId) throws IOException {
    Path sessions = codexHome.resolve("sessions");
    if (threadId == null || !Files.isDirectory(sessions)) {
      return Optional.empty();
    }
    String suffix = threadId + ".jsonl";
    try (Stream<Path> files = Files.walk(sessions, 4)) {
      return files
          .filter(f -> f.getFileName().toString().endsWith(suffix) && Files.isRegularFile(f))
          .findFirst();
    }
  }

  /**
   * Las ventanas de un {@code rate_limits} (fichero) o {@code rateLimits} (app-server). Una ventana
   * de 300 min es la de 5 h y una de 10080, la semanal; si no dice su duración, {@code primary} es
   * la de 5 h y {@code secondary} la semanal.
   */
  static List<QuotaSample> fromRateLimits(JsonNode limits, Instant observed) {
    if (limits == null || !limits.isObject()) {
      return List.of();
    }
    String plan = text(limits, "plan_type", "planType");
    List<QuotaSample> samples = new ArrayList<>();
    for (String slot : List.of("primary", "secondary")) {
      JsonNode window = limits.path(slot);
      JsonNode used = field(window, "used_percent", "usedPercent");
      if (used == null || !used.isNumber()) {
        continue;
      }
      JsonNode minutes = field(window, "window_minutes", "windowDurationMins");
      String name =
          windowName(slot, minutes == null || !minutes.isNumber() ? null : minutes.asLong());
      if (name == null) {
        continue;
      }
      samples.add(
          new QuotaSample(
              PROVIDER,
              QuotaSample.DEFAULT_BUCKET,
              null,
              name,
              used.asDouble() / 100,
              resetsAt(window, observed),
              plan,
              observed));
    }
    return samples;
  }

  private static String windowName(String slot, Long minutes) {
    if (minutes == null) {
      return slot.equals("primary") ? QuotaSample.FIVE_HOUR : QuotaSample.SEVEN_DAY;
    }
    if (minutes == FIVE_HOURS) {
      return QuotaSample.FIVE_HOUR;
    }
    if (minutes == WEEK) {
      return QuotaSample.SEVEN_DAY;
    }
    return null;
  }

  /** {@code resets_at} (epoch) o, en versiones anteriores, {@code resets_in_seconds}. */
  private static Instant resetsAt(JsonNode window, Instant observed) {
    JsonNode at = field(window, "resets_at", "resetsAt");
    if (at != null && at.isNumber()) {
      return Instant.ofEpochSecond(at.asLong());
    }
    JsonNode in = field(window, "resets_in_seconds", "resetsInSeconds");
    if (in != null && in.isNumber()) {
      return observed.plusSeconds(in.asLong());
    }
    return null;
  }

  static JsonNode read(String line) {
    try {
      JsonNode node = JSON.readTree(line);
      return node.isObject() ? node : null;
    } catch (JacksonException e) {
      return null;
    }
  }

  private static Instant instant(String text) {
    if (text == null) {
      return Instant.now();
    }
    try {
      return Instant.parse(text);
    } catch (DateTimeParseException e) {
      return Instant.now();
    }
  }

  private static JsonNode field(JsonNode node, String snake, String camel) {
    JsonNode value = node.get(snake);
    return value == null || value.isNull() ? node.get(camel) : value;
  }

  private static String text(JsonNode node, String snake, String camel) {
    JsonNode value = field(node, snake, camel);
    return value == null || value.isNull() ? null : value.asString();
  }
}
