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

class FakeGeminiTest {

  @TempDir Path dir;

  @ParameterizedTest
  @CsvSource({
    "01-simple-text, 0",
    "02-tools, 0",
    "03-resume, 0",
    "04-turn-limit, 53",
    "05-cancelled, 143",
  })
  void replaysEveryFixtureWithExpectedExitCode(String fixture, int expectedExit) throws Exception {
    Result r =
        run(
            Map.of("FAKE_GEMINI_FIXTURE", fixture),
            "--output-format",
            "stream-json",
            "--session-id",
            "s1",
            "--prompt=hola");

    assertThat(r.exitCode).isEqualTo(expectedExit);
    assertThat(r.lines.getFirst().path("type").asString()).isEqualTo("init");
    assertThat(r.lines.getFirst().path("session_id").asString()).isEqualTo("s1");
    assertThat(r.lines.get(1).path("content").asString()).isEqualTo("hola");
  }

  @Test
  void answersVersionLikeTheRealCli() throws Exception {
    Result r = run(Map.of(), "--version");

    assertThat(r.exitCode).isZero();
    assertThat(r.out.strip()).isEqualTo(FakeGemini.VERSION);
  }

  @Test
  void resumeNeedsTheSessionOfThisDirectoryAndASessionIdCannotBeReused() throws Exception {
    Map<String, String> env = Map.of("GEMINI_CLI_HOME", dir.resolve("home").toString());
    String[] start = {"--output-format", "stream-json", "--session-id", "s1", "--prompt=hola"};
    assertThat(run(env, start).exitCode).isZero();

    Result resumed = run(env, "--output-format", "stream-json", "--resume", "s1", "--prompt=sigue");
    assertThat(resumed.exitCode).isZero();
    assertThat(resumed.lines.getFirst().path("session_id").asString()).isEqualTo("s1");
    assertThat(resumed.out).contains("Sigo donde lo dejamos");

    Result again = run(env, start);
    assertThat(again.exitCode).isEqualTo(FakeGemini.EXIT_INPUT_ERROR);
    assertThat(again.err).contains("already exists");

    Result missing =
        run(env, "--output-format", "stream-json", "--resume", "otra", "--prompt=sigue");
    assertThat(missing.exitCode).isEqualTo(FakeGemini.EXIT_INPUT_ERROR);
    assertThat(missing.err).contains("Invalid session identifier");
  }

  @Test
  void aPromptThatAsksForJsonGetsTheStructuredOutputAndTheModelGetsTheUsage() throws Exception {
    Result r =
        run(
            Map.of("FAKE_GEMINI_STRUCTURED_OUTPUT", "{\"fixed\":true}"),
            "--output-format",
            "stream-json",
            "--model",
            "gemini-2.5-flash",
            "--prompt=Arregla add\n```json\n{}\n```");

    JsonNode last = r.lines.get(r.lines.size() - 2);
    assertThat(last.path("content").asString()).contains("{\"fixed\":true}");
    assertThat(r.lines.getFirst().path("model").asString()).isEqualTo("gemini-2.5-flash");
    assertThat(r.lines.getLast().path("stats").path("models").propertyNames())
        .containsExactly("gemini-2.5-flash");
  }

  private record Result(int exitCode, List<JsonNode> lines, String out, String err) {}

  private Result run(Map<String, String> env, String... args) throws Exception {
    Map<String, String> all = new HashMap<>(env);
    all.put("FAKE_GEMINI_DELAY_MS", "0");
    all.put("FAKE_GEMINI_HANG", "false");
    var out = new ByteArrayOutputStream();
    var err = new ByteArrayOutputStream();
    Files.createDirectories(dir.resolve("worktree"));
    int code =
        FakeGemini.run(
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
