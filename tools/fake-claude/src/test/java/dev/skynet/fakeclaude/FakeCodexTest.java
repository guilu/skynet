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

class FakeCodexTest {

  @TempDir Path dir;

  @ParameterizedTest
  @CsvSource({
    "01-simple-text, 0",
    "02-tools, 0",
    "03-resume, 0",
    "04-failed, 1",
    "05-cancelled, 143",
  })
  void replaysEveryFixtureWithExpectedExitCode(String fixture, int expectedExit) throws Exception {
    Result r = run(Map.of("FAKE_CODEX_FIXTURE", fixture), "exec", "--json", "--", "hola");

    assertThat(r.exitCode).isEqualTo(expectedExit);
    assertThat(r.lines.getFirst().path("type").asString()).isEqualTo("thread.started");
  }

  @Test
  void answersVersionLikeTheRealCli() throws Exception {
    Result r = run(Map.of(), "--version");

    assertThat(r.exitCode).isZero();
    assertThat(r.out).startsWith(FakeCodex.VERSION);
  }

  @Test
  void onlyAcceptsExecJson() throws Exception {
    assertThat(run(Map.of(), "exec", "--", "hola").exitCode).isEqualTo(2);
    assertThat(run(Map.of(), "hola").exitCode).isEqualTo(2);
  }

  @Test
  void resumeContinuesAStoredThreadAndForkCopiesItToANewOne() throws Exception {
    Map<String, String> env = Map.of("CODEX_HOME", dir.resolve("home").toString());
    Result first = run(env, "exec", "--json", "--sandbox", "workspace-write", "--", "hola");
    String thread = first.lines.getFirst().path("thread_id").asString();
    Path sessions = dir.resolve("home/sessions");
    assertThat(sessions.resolve(thread + ".jsonl")).isNotEmptyFile();

    Result resumed = run(env, "exec", "--json", "resume", thread, "--", "sigue");
    assertThat(resumed.exitCode).isZero();
    assertThat(resumed.lines.getFirst().path("thread_id").asString()).isEqualTo(thread);
    assertThat(resumed.out).contains("Sigo donde lo dejamos");

    Result forked = run(env, "exec", "--json", "fork", thread, "--", "otra");
    String branch = forked.lines.getFirst().path("thread_id").asString();
    assertThat(forked.exitCode).isZero();
    assertThat(branch).isNotEqualTo(thread);
    assertThat(sessions.resolve(branch + ".jsonl")).isNotEmptyFile();

    Result missing = run(env, "exec", "--json", "resume", "no-existe", "--", "sigue");
    assertThat(missing.exitCode).isEqualTo(1);
    assertThat(missing.err).contains("no rollout found");
  }

  @Test
  void withASchemaTheLastMessageIsTheStructuredOutput() throws Exception {
    Files.writeString(dir.resolve("salida.json"), "{\"verdict\":\"ok\"}\n");
    Result r =
        run(
            Map.of("FAKE_CODEX_STRUCTURED_OUTPUT", "@salida.json"),
            "exec",
            "--json",
            "--output-schema",
            "/tmp/schema.json",
            "--",
            "revisa");

    JsonNode message =
        r.lines.stream()
            .filter(n -> "agent_message".equals(n.path("item").path("type").asString()))
            .reduce((a, b) -> b)
            .orElseThrow();
    assertThat(message.path("item").path("text").asString()).isEqualTo("{\"verdict\":\"ok\"}");
  }

  @Test
  void parsesTheOptionsBeforeTheSubcommand() {
    FakeCodex.Invocation inv =
        FakeCodex.Invocation.parse(
            new String[] {
              "exec",
              "--json",
              "--model",
              "gpt-5.5",
              "--sandbox",
              "read-only",
              "resume",
              "t1",
              "--",
              "-hola"
            });

    assertThat(inv.exec()).isTrue();
    assertThat(inv.json()).isTrue();
    assertThat(inv.model()).isEqualTo("gpt-5.5");
    assertThat(inv.sandbox()).isEqualTo("read-only");
    assertThat(inv.subcommand()).isEqualTo("resume");
    assertThat(inv.sessionId()).isEqualTo("t1");
    assertThat(inv.prompt()).isEqualTo("-hola");
  }

  private record Result(int exitCode, List<JsonNode> lines, String out, String err) {}

  private Result run(Map<String, String> env, String... args) throws Exception {
    Map<String, String> all = new HashMap<>(env);
    all.put("FAKE_CODEX_DELAY_MS", "0");
    var out = new ByteArrayOutputStream();
    var err = new ByteArrayOutputStream();
    int code =
        FakeCodex.run(
            args,
            all,
            dir,
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));
    String stdout = out.toString(StandardCharsets.UTF_8);
    var json = JsonMapper.builder().build();
    List<JsonNode> lines =
        stdout.lines().filter(l -> l.startsWith("{")).map(json::readTree).toList();
    return new Result(code, lines, stdout, err.toString(StandardCharsets.UTF_8));
  }
}
