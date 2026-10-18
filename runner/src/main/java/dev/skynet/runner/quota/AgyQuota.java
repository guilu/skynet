package dev.skynet.runner.quota;

import dev.skynet.protocol.runner.QuotaSample;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * La cuota de Antigravity CLI: {@code agy -p /usage --output-format json}, que no gasta cuota. Da
 * grupos de modelos ({@code command.data.groups}) con una ventana de 5 h y otra semanal cada uno
 * ({@code buckets}: {@code window} {@code 5h} o {@code weekly}, {@code remaining_fraction} y {@code
 * reset_time}). Grabado con agy 1.4.0: {@code fixtures/agy/usage.json}.
 */
public final class AgyQuota implements QuotaSource {

  static final String PROVIDER = "antigravity";
  private static final ObjectMapper JSON = JsonMapper.builder().build();
  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private final String executable;
  private final Map<String, String> env;

  public AgyQuota(String executable, Map<String, String> env) {
    this.executable = executable;
    this.env = Map.copyOf(env);
  }

  @Override
  public String provider() {
    return PROVIDER;
  }

  @Override
  public List<QuotaSample> read() throws IOException, InterruptedException {
    String output =
        CliProcess.run(
            List.of(
                executable, "-p", "/usage", "--output-format", "json", "--log-file", "/dev/null"),
            env,
            TIMEOUT);
    return parse(output, Instant.now());
  }

  /** Las ventanas de la salida de {@code /usage}; vacía si no tiene la forma conocida. */
  static List<QuotaSample> parse(String output, Instant observed) {
    JsonNode root;
    try {
      root = JSON.readTree(output);
    } catch (JacksonException e) {
      return List.of();
    }
    List<QuotaSample> samples = new ArrayList<>();
    for (JsonNode group : root.path("command").path("data").path("groups")) {
      String label = group.path("name").asString(null);
      for (JsonNode bucket : group.path("buckets")) {
        String window =
            switch (bucket.path("window").asString("")) {
              case "5h" -> QuotaSample.FIVE_HOUR;
              case "weekly" -> QuotaSample.SEVEN_DAY;
              default -> null;
            };
        JsonNode remaining = bucket.get("remaining_fraction");
        String id = bucket.path("id").asString(null);
        if (window == null || remaining == null || !remaining.isNumber() || id == null) {
          continue;
        }
        samples.add(
            new QuotaSample(
                PROVIDER,
                group(id),
                label,
                window,
                1 - remaining.asDouble(),
                instant(bucket.path("reset_time").asString(null)),
                null,
                observed));
      }
    }
    return samples;
  }

  /** El grupo, del id de la ventana sin su sufijo: {@code gemini-5h} → {@code gemini}. */
  static String group(String id) {
    return id.replaceFirst("-(5h|weekly)$", "");
  }

  private static Instant instant(String text) {
    if (text == null) {
      return null;
    }
    try {
      return Instant.parse(text);
    } catch (DateTimeParseException e) {
      return null;
    }
  }
}
