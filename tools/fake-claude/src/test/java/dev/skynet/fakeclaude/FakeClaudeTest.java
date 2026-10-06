package dev.skynet.fakeclaude;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
  void withConfigDirStoresSessionsAndResumeNeedsThemInTheSameDirectory(@TempDir Path config)
      throws Exception {
    Map<String, String> env =
        Map.of("CLAUDE_CONFIG_DIR", config.toString(), "FAKE_CLAUDE_DELAY_MS", "0");
    String[] start = {"-p", "x", "--output-format", "stream-json", "--session-id", SESSION};
    String[] resume = {"-p", "y", "--output-format", "stream-json", "--resume", SESSION};

    assertThat(run(env, CWD, start).exitCode).isZero();
    Path stored = FakeClaude.sessionFile(env, CWD, SESSION);
    assertThat(stored).isRegularFile();
    assertThat(stored.getParent().getFileName()).hasToString("-workspaces-wr-1-ar-1");

    assertThat(run(env, CWD, resume).exitCode).isZero();
    assertThat(run(env, Path.of("/otro"), resume).exitCode).isEqualTo(1);
  }

  @Test
  void withApplyEditsTheFilesAndRunsTheCommandsOfTheFixture(@TempDir Path repo) throws Exception {
    git(repo, "init", "-q", "-b", "main");
    Files.writeString(repo.resolve("calc.py"), "def add(a, b):\n    return a - b\n");
    git(repo, "add", ".");
    git(repo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "init");

    Map<String, String> env =
        Map.of(
            "FAKE_CLAUDE_FIXTURE",
            "02-tools",
            "FAKE_CLAUDE_DELAY_MS",
            "0",
            "FAKE_CLAUDE_APPLY",
            "1");
    Result r = run(env, repo, "-p", "x", "--output-format", "stream-json");

    assertThat(r.exitCode).isZero();
    assertThat(Files.readString(repo.resolve("calc.py"))).contains("return a + b");
    // El Bash del fixture confirma el cambio.
    assertThat(git(repo, "log", "--format=%s", "-1")).isEqualTo("fix add");
    assertThat(git(repo, "status", "--porcelain")).isEmpty();
  }

  @Test
  void withoutApplyTheWorktreeIsUntouched(@TempDir Path repo) throws Exception {
    Files.writeString(repo.resolve("calc.py"), "def add(a, b):\n    return a - b\n");

    run(
        Map.of("FAKE_CLAUDE_FIXTURE", "02-tools", "FAKE_CLAUDE_DELAY_MS", "0"),
        repo,
        "-p",
        "x",
        "--output-format",
        "stream-json");

    assertThat(Files.readString(repo.resolve("calc.py"))).contains("return a - b");
  }

  private static String git(Path dir, String... args) throws Exception {
    List<String> command = new java.util.ArrayList<>(List.of("git", "-C", dir.toString()));
    command.addAll(List.of(args));
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.waitFor()).as(output).isZero();
    return output.trim();
  }

  @Test
  void rejectsNonStreamJsonOutput() throws Exception {
    assertThat(run("01-simple-text", "-p", "x").exitCode).isEqualTo(2);
  }

  private record Result(int exitCode, List<JsonNode> lines) {}

  private static Result run(String fixture, String... args) throws Exception {
    return run(Map.of("FAKE_CLAUDE_FIXTURE", fixture, "FAKE_CLAUDE_DELAY_MS", "0"), CWD, args);
  }

  private static Result run(Map<String, String> env, Path cwd, String... args) throws Exception {
    var buffer = new ByteArrayOutputStream();
    var out = new PrintStream(buffer, true, StandardCharsets.UTF_8);
    var err = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    int code = FakeClaude.run(args, env, cwd, out, err);
    var mapper = JsonMapper.builder().build();
    List<JsonNode> lines =
        buffer.toString(StandardCharsets.UTF_8).lines().map(mapper::readTree).toList();
    return new Result(code, lines);
  }
}
