package dev.skynet.fakeclaude;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** La guía de pruebas guionizada de los CLI falsos (VA-D). */
class FakeGuideTest {

  private static final String SCHEMA =
      "{\"properties\":{\"guides\":{},\"steps\":{},\"summary\":{}}}";

  private static final String GUIDE =
      "# Probar\n\n## 1. Tests\n\n1. Pasan los tests\n2. Divide entre cero\n\n"
          + "## 4. En GitHub\n\n- Abre la PR\n";

  @Test
  void itRunsTheChangedGuidesAndFailsTheScriptedStepOnce(@TempDir Path dir) throws Exception {
    String base = repo(dir);
    Files.createDirectories(dir.resolve("docs/tests"));
    Files.writeString(dir.resolve("docs/tests/probar-calc.md"), GUIDE);
    Files.writeString(dir.resolve("docs/otra.md"), GUIDE);
    Files.writeString(dir.resolve("guia-falla"), "cero");
    String prompt = prompt(base);

    JsonNode failed = run(dir, prompt);
    assertThat(failed.path("guides").get(0).asString()).isEqualTo("docs/tests/probar-calc.md");
    assertThat(failed.path("guides").size()).isEqualTo(1);
    assertThat(failed.path("steps").findValuesAsString("status"))
        .containsExactly("passed", "failed", "skipped");
    assertThat(failed.path("steps").get(1).path("section").asString()).isEqualTo("## 1. Tests");
    assertThat(dir.resolve("guia-falla")).doesNotExist();

    JsonNode passed = run(dir, prompt);
    assertThat(passed.path("steps").findValuesAsString("status"))
        .containsExactly("passed", "passed", "skipped");
  }

  @Test
  void itSkipsTheSectionsEvenWithMangledQuotes(@TempDir Path dir) throws Exception {
    String base = repo(dir);
    Files.createDirectories(dir.resolve("docs/tests"));
    Files.writeString(dir.resolve("docs/tests/probar-calc.md"), GUIDE);

    JsonNode run = run(dir, prompt(base).replace("«", "?").replace("»", "??"));
    assertThat(run.path("steps").findValuesAsString("status"))
        .containsExactly("passed", "passed", "skipped");
  }

  @Test
  void withoutAChangedGuideItReturnsNoGuides(@TempDir Path dir) throws Exception {
    String base = repo(dir);

    JsonNode run = run(dir, prompt(base));
    assertThat(run.path("guides").isEmpty()).isTrue();
    assertThat(run.path("steps").isEmpty()).isTrue();
  }

  @Test
  void itOnlyAnswersAGuide(@TempDir Path dir) throws Exception {
    assertThat(
            FakeGuide.run(
                Map.of("FAKE_CLAUDE_GUIDE", "x"),
                "FAKE_CLAUDE_GUIDE",
                "{\"properties\":{\"criteria\":{}}}",
                "",
                dir))
        .isNull();
    assertThat(FakeGuide.run(Map.of(), "FAKE_CLAUDE_GUIDE", SCHEMA, "", dir)).isNull();
  }

  private static String prompt(String base) {
    return "## La guía\n\nSon los ficheros que cumplen `docs/tests/probar-*.md` y que cambia el"
        + " diff desde `"
        + base
        + "` (`git diff --name-only "
        + base
        + "`).\n\nNo ejecutes las secciones cuyo título empieza por «## 4.»: apunta sus pasos"
        + " como `skipped`.\n";
  }

  private static JsonNode run(Path dir, String prompt) throws Exception {
    String out =
        FakeGuide.run(
            Map.of("FAKE_CLAUDE_GUIDE", "guia-falla"), "FAKE_CLAUDE_GUIDE", SCHEMA, prompt, dir);
    return JsonMapper.shared().readTree(out);
  }

  /** Un repositorio con un commit; devuelve su sha. */
  private static String repo(Path dir) throws Exception {
    git(dir, "init", "-q");
    git(
        dir,
        "-c",
        "user.email=t@t",
        "-c",
        "user.name=t",
        "commit",
        "-q",
        "--allow-empty",
        "-m",
        "base");
    Process p = new ProcessBuilder("git", "rev-parse", "HEAD").directory(dir.toFile()).start();
    String sha = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
    p.waitFor();
    return sha;
  }

  private static void git(Path dir, String... args) throws Exception {
    List<String> command = new java.util.ArrayList<>(List.of("git"));
    command.addAll(List.of(args));
    assertThat(new ProcessBuilder(command).directory(dir.toFile()).inheritIO().start().waitFor())
        .isZero();
  }
}
