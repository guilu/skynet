package dev.skynet.controlplane.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skynet.controlplane.definition.JudgeSpec;
import dev.skynet.controlplane.workflow.JudgeVerdicts.Verdict;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** La guía de pruebas como fase (VA-D), sin motor. */
class GuideStepsTest {

  private static final JudgeSpec.GuideSpec GUIDE =
      new JudgeSpec.GuideSpec(
          List.of("docs/tests/probar-*.md"), "probador", List.of("## 4."), false);

  private static GuideSteps.Run run(String json) {
    return GuideSteps.run(JsonMapper.shared().readTree(json));
  }

  private static final String MIXED =
      """
      {"guides": ["docs/tests/probar-calc.md"],
       "steps": [
         {"guide": "docs/tests/probar-calc.md", "section": "## 1. Tests", "step": "Pasan los tests",
          "status": "passed", "evidence": "3 passed"},
         {"guide": "docs/tests/probar-calc.md", "section": "## 1. Tests", "step": "Divide entre cero",
          "status": "failed", "evidence": "ZeroDivisionError"},
         {"guide": "docs/tests/probar-calc.md", "section": "## 2. Local", "step": "Abre el navegador",
          "status": "blocked", "evidence": "No hay navegador"},
         {"guide": "docs/tests/probar-calc.md", "section": "## 4. GitHub", "step": "Abre la PR",
          "status": "skipped", "evidence": "Sección que no se ejecuta"},
         {"guide": "docs/tests/probar-calc.md", "section": "## 4. GitHub", "step": "Raro",
          "status": "quizas", "evidence": null}],
       "summary": "Falla la división."}
      """;

  @Test
  void aFailedStepBlocksAndABlockedOneOnlyWarns() {
    GuideSteps.Run run = run(MIXED);
    // Un estado desconocido cuenta como fallido.
    assertThat(run.count(GuideSteps.FAILED)).isEqualTo(2);

    Verdict verdict = GuideSteps.verdict("probador", GUIDE, run);

    assertThat(verdict.passed()).isFalse();
    assertThat(verdict.lens()).isEqualTo("guide");
    assertThat(verdict.reasons()).containsExactly("Fallan 2 pasos de la guía");
    assertThat(verdict.findings())
        .extracting(JudgeVerdicts.Finding::severity)
        .containsExactly(JudgeVerdicts.BLOCKING, JudgeVerdicts.MINOR, JudgeVerdicts.BLOCKING);
    assertThat(verdict.findings().getFirst().text())
        .isEqualTo("Falla el paso «Divide entre cero» (## 1. Tests): ZeroDivisionError");
    // 1 pasa de 3 que se ejecutaron.
    assertThat(verdict.score()).isEqualByComparingTo(new BigDecimal("2.33"));
    assertThat(GuideSteps.note(run, GUIDE))
        .isEqualTo("1 de 4 pasos pasan · 1 no se pudo · 1 saltado");
  }

  @Test
  void itPassesWhenNoStepFails() {
    GuideSteps.Run run =
        run(
            """
            {"guides": ["g.md"], "steps": [
              {"guide": "g.md", "section": "## 1", "step": "a", "status": "passed", "evidence": "ok"},
              {"guide": "g.md", "section": "## 2", "step": "b", "status": "blocked", "evidence": "x"}],
             "summary": "Bien."}
            """);

    Verdict verdict = GuideSteps.verdict("probador", GUIDE, run);

    assertThat(verdict.passed()).isTrue();
    assertThat(verdict.score()).isEqualByComparingTo(new BigDecimal("5"));
  }

  @Test
  void withoutAGuideItFailsUnlessItSkips() {
    GuideSteps.Run none = run("{\"guides\": [], \"steps\": [], \"summary\": \"Nada.\"}");

    Verdict failed = GuideSteps.verdict("probador", GUIDE, none);
    assertThat(failed.passed()).isFalse();
    assertThat(failed.reasons()).containsExactly(GuideSteps.MISSING);
    assertThat(GuideSteps.note(none, GUIDE)).isEqualTo(GuideSteps.MISSING);

    JudgeSpec.GuideSpec skip = new JudgeSpec.GuideSpec(GUIDE.paths(), "probador", List.of(), true);
    Verdict skipped = GuideSteps.verdict("probador", skip, none);
    assertThat(skipped.passed()).isTrue();
    assertThat(skipped.score()).isEqualByComparingTo(new BigDecimal("5"));
    assertThat(GuideSteps.note(none, skip)).isEqualTo("Sin guía en el cambio: se omite");
  }

  @Test
  void thePromptSaysWhichGuidesAndSectionsAndTheMarkdownShowsEachStep() {
    String prompt = GuideSteps.prompt("SKY-1", "Dividir", "abc123", GUIDE, 2, "Usa python3");

    assertThat(prompt)
        .contains("`docs/tests/probar-*.md`")
        .contains("`git diff --name-only abc123`")
        .contains("cuyo título empieza por «## 4.»")
        .contains("Es la ronda 2")
        .contains("Usa python3")
        .contains("No arregles nada para que pase");
    assertThat(GuideSteps.outputSchema()).contains("\"guides\"", "\"steps\"", "\"blocked\"");

    String markdown = GuideSteps.markdown(run(MIXED), GUIDE);
    assertThat(markdown)
        .contains("## 1. Tests")
        .contains("- ✓ Pasan los tests")
        .contains("- ✗ Divide entre cero")
        .contains("  ZeroDivisionError")
        .contains("- ⚠ Abre el navegador")
        .contains("- – Abre la PR")
        .doesNotContain("Sección que no se ejecuta");
  }
}
