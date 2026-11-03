package dev.skynet.fakeclaude;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** El juez guionizado de los CLI falsos (VA-B). */
class FakeJudgeTest {

  private static final String SCHEMA =
      "{\"properties\":{\"criteria\":{},\"findings\":{},\"summary\":{}}}";
  private static final String PROMPT =
      "## La rúbrica\n\n- `resuelve` (peso 3, obligatorio): Resuelve\n- `tests` (peso 2): Tests\n";

  @Test
  void withoutTheMarkerItApprovesEveryCriterionOfThePrompt(@TempDir Path dir) throws Exception {
    JsonNode verdict = verdict(dir, PROMPT);

    assertThat(verdict.path("criteria").findValuesAsString("id"))
        .containsExactly("resuelve", "tests");
    assertThat(verdict.path("criteria").get(0).path("score").asInt()).isEqualTo(5);
    assertThat(verdict.path("findings").isEmpty()).isTrue();
  }

  @Test
  void withTheMarkerItFailsOnceWithTheScriptedFinding(@TempDir Path dir) throws Exception {
    Path marker = dir.resolve("suspende");
    Files.writeString(marker, "src/Calc.java:12 Divide entre cero sin comprobarlo\n");

    JsonNode failed = verdict(dir, PROMPT);
    JsonNode finding = failed.path("findings").get(0);
    assertThat(failed.path("criteria").get(1).path("score").asInt()).isEqualTo(2);
    assertThat(finding.path("file").asString()).isEqualTo("src/Calc.java");
    assertThat(finding.path("line").asInt()).isEqualTo(12);
    assertThat(finding.path("severity").asString()).isEqualTo("blocking");
    assertThat(finding.path("text").asString()).isEqualTo("Divide entre cero sin comprobarlo");
    assertThat(marker).doesNotExist();

    // La ronda siguiente aprueba.
    assertThat(verdict(dir, PROMPT).path("findings").isEmpty()).isTrue();
  }

  @Test
  void withABangOnlyTheFindingFails(@TempDir Path dir) throws Exception {
    Files.writeString(dir.resolve("suspende"), "!calc.py:2 add() no trata los negativos");

    JsonNode failed = verdict(dir, PROMPT);

    assertThat(failed.path("criteria").get(0).path("score").asInt()).isEqualTo(5);
    assertThat(failed.path("findings").get(0).path("text").asString())
        .isEqualTo("add() no trata los negativos");
  }

  @Test
  void itOnlyAnswersAJudge(@TempDir Path dir) throws Exception {
    Map<String, String> env = Map.of("FAKE_CLAUDE_JUDGE", dir.resolve("suspende").toString());

    assertThat(FakeJudge.verdict(env, "FAKE_CLAUDE_JUDGE", "{\"properties\":{}}", PROMPT, dir))
        .isNull();
    assertThat(FakeJudge.verdict(Map.of(), "FAKE_CLAUDE_JUDGE", SCHEMA, PROMPT, dir)).isNull();
  }

  @Test
  void aSkepticRefutesOnlyTheFindingsTheMarkerNames(@TempDir Path dir) throws Exception {
    String schema = "{\"properties\":{\"refuted\":{},\"reason\":{}}}";
    Path marker = dir.resolve("refuta");
    Map<String, String> env = Map.of("FAKE_CLAUDE_REFUTE", marker.toString());
    String prompt = "## El hallazgo\n\nEn `calc.py:2`: add() no trata los negativos\n";

    JsonNode without =
        JsonMapper.shared()
            .readTree(FakeJudge.refutation(env, "FAKE_CLAUDE_REFUTE", schema, prompt, dir));
    Files.writeString(marker, "no trata los negativos");
    JsonNode named =
        JsonMapper.shared()
            .readTree(FakeJudge.refutation(env, "FAKE_CLAUDE_REFUTE", schema, prompt, dir));
    JsonNode other =
        JsonMapper.shared()
            .readTree(
                FakeJudge.refutation(env, "FAKE_CLAUDE_REFUTE", schema, "Otro hallazgo", dir));

    assertThat(without.path("refuted").asBoolean()).isFalse();
    assertThat(named.path("refuted").asBoolean()).isTrue();
    assertThat(other.path("refuted").asBoolean()).isFalse();
    assertThat(marker).exists();
    // Ni a un juez ni sin el testigo configurado.
    assertThat(FakeJudge.refutation(env, "FAKE_CLAUDE_REFUTE", SCHEMA, prompt, dir)).isNull();
    assertThat(FakeJudge.refutation(Map.of(), "FAKE_CLAUDE_REFUTE", schema, prompt, dir)).isNull();
  }

  @Test
  void fakeClaudeReturnsTheVerdictAsItsStructuredOutput(@TempDir Path dir) throws Exception {
    Files.writeString(dir.resolve("suspende"), "");
    var buffer = new ByteArrayOutputStream();
    int code =
        FakeClaude.run(
            new String[] {
              "--output-format", "stream-json", "--json-schema", SCHEMA, "-p", "--", PROMPT
            },
            Map.of(
                "FAKE_CLAUDE_DELAY_MS",
                "0",
                "FAKE_CLAUDE_JUDGE",
                dir.resolve("suspende").toString()),
            dir,
            new PrintStream(buffer, true, StandardCharsets.UTF_8),
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));

    assertThat(code).isZero();
    String[] lines = buffer.toString(StandardCharsets.UTF_8).strip().split("\n");
    JsonNode output =
        JsonMapper.shared().readTree(lines[lines.length - 1]).path("structured_output");
    assertThat(output.path("findings").get(0).path("text").asString())
        .isEqualTo("Falta un test para el caso que pide la tarea");
  }

  private static JsonNode verdict(Path dir, String prompt) throws Exception {
    Map<String, String> env = Map.of("FAKE_CODEX_JUDGE", dir.resolve("suspende").toString());
    return JsonMapper.shared()
        .readTree(FakeJudge.verdict(env, "FAKE_CODEX_JUDGE", SCHEMA, prompt, dir));
  }
}
