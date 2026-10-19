package dev.skynet.controlplane.definition;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skynet.controlplane.definition.DefinitionParser.Expectations;
import dev.skynet.controlplane.definition.DefinitionParser.Parsed;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Los workflows iniciales de {@code workflows/} (los publica {@code scripts/seed-workflows.sh}) se
 * pueden publicar tal cual, con los modelos de {@code workflows/modelos.env} y con todos rellenos.
 * La sustitución es la misma que la del script.
 */
class SeedWorkflowsTest {

  private static final Path DIR =
      Path.of(System.getProperty("skynet.seedWorkflows", "../workflows"));
  private static final Pattern MODEL_LINE =
      Pattern.compile("^([ \\t]*model:[ \\t]*)\\$\\{([A-Z_]+)\\}[ \\t]*\\n", Pattern.MULTILINE);

  static Stream<Path> workflows() throws IOException {
    try (Stream<Path> files = Files.list(DIR)) {
      return files.filter(f -> f.toString().endsWith(".yaml")).sorted().toList().stream();
    }
  }

  @ParameterizedTest
  @MethodSource("workflows")
  void publishableWithTheDefaultModels(Path file) throws IOException {
    assertPublishable(file, models());
  }

  @ParameterizedTest
  @MethodSource("workflows")
  void publishableWithEveryModelFilledIn(Path file) throws IOException {
    Map<String, String> filled = new HashMap<>();
    models().keySet().forEach(name -> filled.put(name, "modelo-" + name.toLowerCase()));
    assertPublishable(file, filled);
  }

  private static void assertPublishable(Path file, Map<String, String> models) throws IOException {
    String source = substitute(Files.readString(file), models);
    assertThat(source).doesNotContain("${");
    Parsed parsed = DefinitionParser.parse(source, Expectations.NONE);
    assertThat(parsed.validation().problems()).isEmpty();
    assertThat(parsed.validation().publishable()).isTrue();
    assertThat(file.getFileName().toString()).isEqualTo(parsed.key() + ".yaml");
  }

  /** Cada {@code model: ${X}} toma el valor de X; si está vacío, la línea desaparece. */
  static String substitute(String source, Map<String, String> models) {
    Matcher matcher = MODEL_LINE.matcher(source);
    StringBuilder out = new StringBuilder();
    while (matcher.find()) {
      String value = models.get(matcher.group(2));
      assertThat(value).as("variable %s en modelos.env", matcher.group(2)).isNotNull();
      matcher.appendReplacement(
          out, value.isEmpty() ? "" : Matcher.quoteReplacement(matcher.group(1) + value + "\n"));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  /** {@code NOMBRE=valor # comentario}, como lo lee el script. */
  static Map<String, String> models() throws IOException {
    List<String> lines = Files.readAllLines(DIR.resolve("modelos.env"));
    return lines.stream()
        .map(line -> line.replaceFirst("#.*", "").strip())
        .filter(line -> line.contains("="))
        .collect(
            Collectors.toMap(
                line -> line.substring(0, line.indexOf('=')).strip(),
                line -> line.substring(line.indexOf('=') + 1).strip()));
  }
}
