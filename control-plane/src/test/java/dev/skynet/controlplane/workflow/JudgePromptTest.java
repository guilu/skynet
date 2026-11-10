package dev.skynet.controlplane.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skynet.controlplane.definition.JudgeSpec;
import dev.skynet.protocol.runner.StartAgent;
import java.util.List;
import org.junit.jupiter.api.Test;

/** El prompt de un juez (VA-B). */
class JudgePromptTest {

  private static final JudgeSpec SPEC =
      new JudgeSpec(
          List.of(new JudgeSpec.Judge("juez-seguridad", "security")),
          List.of(),
          null,
          null,
          "implementar",
          2);

  private static final JudgePrompt.Context CONTEXT =
      new JudgePrompt.Context(
          "CAL-7",
          "Dividir entre cero",
          "Que `divide` lance una excepción clara",
          "abc1234",
          List.of(
              new JudgePrompt.Check("tests", true, null, List.of()),
              new JudgePrompt.Check(
                  "tests-intact",
                  true,
                  null,
                  List.of("Hay menos aserciones en `src/test/CalcTest.java` (de 4 a 3)"))),
          1,
          null);

  @Test
  void itCarriesTheTaskTheChangeTheChecksTheRubricAndTheLens() {
    String prompt = JudgePrompt.build(CONTEXT, SPEC, SPEC.agents().getFirst());

    assertThat(prompt)
        .contains("CAL-7 - Dividir entre cero\n\nQue `divide` lance una excepción clara")
        .contains("`git diff abc1234`")
        .contains("- `tests`: pasa")
        .contains("  - Aviso: Hay menos aserciones en `src/test/CalcTest.java` (de 4 a 3)")
        .contains("## Tu lente\n\nSeguridad:")
        // Sin rúbrica en el YAML, la de siempre.
        .contains("- `resuelve` (peso 3, obligatorio): Resuelve la tarea tal y como se pidió")
        .contains("- `alcance` (peso 1): No cambia nada que la tarea no pida")
        .contains("media ponderada de 3,5 o más")
        .doesNotContain("Es la ronda");
  }

  @Test
  void itNeverCarriesWhatTheAuthorSaid() {
    String prompt = JudgePrompt.build(CONTEXT, SPEC, SPEC.agents().getFirst());

    // Ni el resumen ni la salida estructurada del autor: el contexto no los tiene.
    assertThat(JudgePrompt.Context.class.getRecordComponents())
        .extracting(c -> c.getName())
        .containsExactly(
            "workItemKey",
            "title",
            "description",
            "baseCommit",
            "checks",
            "round",
            "extra",
            "seen",
            "previousHead");
    assertThat(prompt).doesNotContain("summary\":").doesNotContain("Declarado por el agente");
  }

  @Test
  void whatWasAlreadySaidGoesWithItsOutcomeAndTheDeltaOfTheLastRound() {
    JudgePrompt.Context context =
        new JudgePrompt.Context(
            "ENG-1",
            "Arreglar la división",
            null,
            "abc1234",
            List.of(),
            2,
            null,
            List.of(
                "- `src/Calc.java`: Divide entre cero (ronda 1: refutado, no es un problema real)"),
            "def5678");
    String prompt = JudgePrompt.build(context, SPEC, SPEC.agents().getFirst());
    assertThat(prompt)
        .contains("Es la ronda 2")
        .contains("Mira primero `git diff def5678`: es lo que se corrigió desde la ronda anterior.")
        .contains("## Lo que ya se ha señalado")
        .contains("No vuelvas a señalar lo refutado, lo intencionado ni lo que no aplica")
        .contains(
            "- `src/Calc.java`: Divide entre cero (ronda 1: refutado, no es un problema real)");
    assertThat(JudgePrompt.build(CONTEXT, SPEC, SPEC.agents().getFirst()))
        .doesNotContain("Lo que ya se ha señalado")
        .doesNotContain("Mira primero");
  }

  @Test
  void aLaterRoundAndARubricFileSayHowToJudge() {
    JudgeSpec file =
        new JudgeSpec(
            List.of(new JudgeSpec.Judge("juez", null)),
            List.of(),
            ".skynet/rubrica.md",
            null,
            null,
            2);
    JudgePrompt.Context second =
        new JudgePrompt.Context("CAL-7", "Dividir", null, null, List.of(), 2, "Sé breve");

    String prompt = JudgePrompt.build(second, file, file.agents().getFirst());

    assertThat(prompt)
        .contains("Es la ronda 2")
        .contains("Está en `.skynet/rubrica.md`")
        .contains("Esta fase no tiene comprobaciones automáticas")
        .contains("## Indicaciones\n\nSé breve")
        .doesNotContain("## Tu lente");
  }

  @Test
  void aSkepticOnlySeesTheFindingAndTheChange() {
    String prompt =
        JudgePrompt.refutation(
            CONTEXT,
            new JudgeVerdicts.Finding(
                "src/Calc.java", 12, "blocking", "Divide entre cero sin comprobarlo", "juez"));

    assertThat(prompt)
        .contains("CAL-7 - Dividir entre cero")
        .contains("En `src/Calc.java:12`: Divide entre cero sin comprobarlo")
        .contains("`git diff abc1234`")
        .contains("Si dudas")
        .doesNotContain("rúbrica")
        .doesNotContain("tests-intact");
  }

  @Test
  void judgesSkepticsAndTheDeltaLeaveRoomForTheRepositorysCriteriaUnderTheRubric() {
    String judge = JudgePrompt.build(CONTEXT, SPEC, SPEC.agents().getFirst());
    assertThat(judge.indexOf(StartAgent.REVIEW_MARKER))
        .isGreaterThan(judge.indexOf("## La rúbrica"))
        .isLessThan(judge.indexOf("## Los hallazgos"));
    assertThat(JudgePrompt.delta(CONTEXT, SPEC, SPEC.agents().getFirst(), 1))
        .contains(StartAgent.REVIEW_MARKER);
    String skeptic =
        JudgePrompt.refutation(
            CONTEXT, new JudgeVerdicts.Finding("src/Calc.java", 12, "blocking", "Divide", "juez"));
    assertThat(skeptic.indexOf(StartAgent.REVIEW_MARKER))
        .isGreaterThan(skeptic.indexOf("## El cambio"))
        .isLessThan(skeptic.indexOf("## Qué contestar"));
  }
}
