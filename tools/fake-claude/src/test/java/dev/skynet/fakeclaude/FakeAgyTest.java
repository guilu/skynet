package dev.skynet.fakeclaude;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class FakeAgyTest {

  @TempDir Path dir;

  @ParameterizedTest
  @CsvSource({
    "01-simple-text, 0",
    "02-tools, 0",
    "03-resume, 0",
    "04-schema, 0",
    "05-denied, 0",
    "06-cancelled, 143",
  })
  void replaysEveryFixtureWithItsOwnConversation(String fixture, int expectedExit)
      throws Exception {
    Result r =
        run(Map.of("FAKE_AGY_FIXTURE", fixture), "--output-format", "stream-json", "--print=hola");

    assertThat(r.exitCode).isEqualTo(expectedExit);
    String conversation = r.lines.getFirst().path("conversation_id").asString();
    assertThat(r.lines.getFirst().path("event").asString()).isEqualTo("init");
    assertThat(r.lines.getFirst().path("init").path("cwd").asString())
        .isEqualTo(dir.resolve("worktree").toString());
    assertThat(r.lines)
        .allSatisfy(
            l ->
                assertThat(l.toString())
                    .doesNotContain(FakeAgy.FIXTURE_CWD)
                    .contains(conversation));
  }

  @Test
  void answersVersionAndUsageLikeTheRealCli() throws Exception {
    assertThat(run(Map.of(), "--version").out.strip()).isEqualTo(FakeAgy.VERSION + " (fake-agy)");

    Result usage = run(Map.of(), "-p", "/usage", "--output-format", "json");
    assertThat(usage.exitCode).isZero();
    assertThat(usage.lines.getFirst().path("command").path("name").asString()).isEqualTo("usage");
  }

  @Test
  void resumesOnlyAConversationItKnows() throws Exception {
    Map<String, String> env = Map.of("FAKE_AGY_HOME", dir.resolve("home").toString());
    Result first = run(env, "--output-format", "stream-json", "--print=hola");
    String conversation = first.lines.getFirst().path("conversation_id").asString();

    Result resumed =
        run(env, "--output-format", "stream-json", "--conversation", conversation, "--print=sigue");
    assertThat(resumed.exitCode).isZero();
    assertThat(resumed.lines.getLast().path("result").path("conversation_id").asString())
        .isEqualTo(conversation);
    assertThat(resumed.out).contains("he modificado la línea 1");

    Result missing =
        run(env, "--output-format", "stream-json", "--conversation", "otra", "--print=sigue");
    assertThat(missing.exitCode).isEqualTo(1);
    assertThat(missing.err).contains("not found");
  }

  @Test
  void theStructuredOutputComesOnlyWithASchema() throws Exception {
    Result with =
        run(
            Map.of(
                "FAKE_AGY_FIXTURE", "04-schema", "FAKE_AGY_STRUCTURED_OUTPUT", "{\"fixed\":true}"),
            "--output-format",
            "stream-json",
            "--json-schema",
            "schema.json",
            "--print=arregla");
    assertThat(with.lines.getLast().path("result").path("structured_output").toString())
        .isEqualTo("{\"fixed\":true}");

    Result without =
        run(Map.of("FAKE_AGY_FIXTURE", "04-schema"), "--output-format", "stream-json", "--print=x");
    assertThat(without.lines.getLast().path("result").has("structured_output")).isFalse();
  }

  private record Result(int exitCode, List<JsonNode> lines, String out, String err) {}

  private Result run(Map<String, String> env, String... args) throws Exception {
    Map<String, String> all = new HashMap<>(env);
    all.put("FAKE_AGY_DELAY_MS", "0");
    all.put("FAKE_AGY_HANG", "false");
    var out = new ByteArrayOutputStream();
    var err = new ByteArrayOutputStream();
    Files.createDirectories(dir.resolve("worktree"));
    int code =
        FakeAgy.run(
            args,
            all,
            dir.resolve("worktree"),
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));
    String stdout = out.toString(StandardCharsets.UTF_8);
    var json = JsonMapper.builder().build();
    List<JsonNode> lines =
        stdout.lines().filter(l -> l.startsWith("{")).map(json::readTree).toList();
    return new Result(code, lines, stdout, err.toString(StandardCharsets.UTF_8));
  }
}
