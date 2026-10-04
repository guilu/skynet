package dev.skynet.fakeclaude;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class FakeClaudeTest {

  private static final Path CWD = Path.of("/workspaces/wr_1/ar_1");
  private static final String SESSION = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

  @ParameterizedTest
  @CsvSource({
    "01-simple-text, 0",
    "02-tools, 0",
    "03-resume, 0",
    "04-fork, 0",
    "05-max-turns, 1",
    "06-permission-denied, 0",
    "07-cancelled, 143",
    "08-budget-exceeded, 1",
    "09-json-schema, 0",
  })
  void replaysEveryFixtureWithExpectedExitCode(String fixture, int expectedExit) throws Exception {
    Result r =
        run(fixture, "-p", "hola", "--output-format", "stream-json", "--session-id", SESSION);

    assertThat(r.exitCode).isEqualTo(expectedExit);
    assertThat(r.lines).isNotEmpty();
    JsonNode init = r.lines.getFirst();
    assertThat(init.path("subtype").asString()).isEqualTo("init");
    assertThat(init.path("cwd").asString()).isEqualTo(CWD.toString());
    assertThat(r.lines)
        .filteredOn(n -> n.has("session_id"))
        .allSatisfy(n -> assertThat(n.path("session_id").asString()).isEqualTo(SESSION));
  }

  @Test
  void resumeKeepsSessionAndForkCreatesANewOne() throws Exception {
    Result resumed =
        run("03-resume", "-p", "x", "--output-format", "stream-json", "--resume", SESSION);
    assertThat(resumed.lines.getFirst().path("session_id").asString()).isEqualTo(SESSION);

    Result forked =
        run(
            "04-fork",
            "-p",
            "x",
            "--output-format",
            "stream-json",
            "--resume",
            SESSION,
            "--fork-session");
    assertThat(forked.lines.getFirst().path("session_id").asString()).isNotEqualTo(SESSION);
  }

  @Test
  void rejectsNonStreamJsonOutput() throws Exception {
    assertThat(run("01-simple-text", "-p", "x").exitCode).isEqualTo(2);
  }

  private record Result(int exitCode, List<JsonNode> lines) {}

  private static Result run(String fixture, String... args) throws Exception {
    var buffer = new ByteArrayOutputStream();
    var out = new PrintStream(buffer, true, StandardCharsets.UTF_8);
    var err = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    Map<String, String> env = Map.of("FAKE_CLAUDE_FIXTURE", fixture, "FAKE_CLAUDE_DELAY_MS", "0");
    int code = FakeClaude.run(args, env, CWD, out, err);
    var mapper = JsonMapper.builder().build();
    List<JsonNode> lines =
        buffer.toString(StandardCharsets.UTF_8).lines().map(mapper::readTree).toList();
    return new Result(code, lines);
  }
}
