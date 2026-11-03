package dev.skynet.controlplane.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skynet.controlplane.definition.JudgeSpec;
import dev.skynet.controlplane.definition.JudgeSpec.Criterion;
import dev.skynet.controlplane.workflow.JudgeVerdicts.Aggregate;
import dev.skynet.controlplane.workflow.JudgeVerdicts.Verdict;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Cómo decide el juez con datos sintéticos (VA-B). */
class JudgeVerdictsTest {

  private static final JudgeSpec SPEC =
      new JudgeSpec(
          List.of(new JudgeSpec.Judge("juez", null)),
          List.of(
              new Criterion("resuelve", "Resuelve la tarea", 3, true),
              new Criterion("tests", "Tiene tests", 2, false),
              new Criterion("alcance", "No toca de más", 1, false)),
          null,
          null,
          "implementar",
          2);

  @Test
  void aJudgePassesWithAWeightedMeanOverTheThreshold() {
    Verdict verdict = verdict(Map.of("resuelve", 4, "tests", 3, "alcance", 5));

    // (4·3 + 3·2 + 5·1) / 6 = 23 / 6
    assertThat(verdict.score()).isEqualByComparingTo("3.83");
    assertThat(verdict.passed()).isTrue();
    assertThat(verdict.reasons()).isEmpty();
  }

  @Test
  void aJudgeFailsUnderTheThreshold() {
    Verdict verdict = verdict(Map.of("resuelve", 3, "tests", 3, "alcance", 4));

    assertThat(verdict.score()).isEqualByComparingTo("3.17");
    assertThat(verdict.passed()).isFalse();
    assertThat(verdict.reasons()).containsExactly("La nota es 3,17 y el umbral 3,5");
  }

  @Test
  void aRequiredCriterionUnderThreeFailsWhateverTheMean() {
    JudgeSpec lenient =
        new JudgeSpec(SPEC.agents(), SPEC.rubric(), null, new BigDecimal("1.5"), null, 2);
    Verdict verdict =
        JudgeVerdicts.verdict(
            "juez",
            null,
            lenient,
            output(Map.of("resuelve", 2, "tests", 5, "alcance", 5), List.of()));

    assertThat(verdict.score()).isEqualByComparingTo("3.5");
    assertThat(verdict.passed()).isFalse();
    assertThat(verdict.reasons()).containsExactly("`resuelve` es obligatorio y saca 2");
  }

  @Test
  void aBlockingFindingFailsTheJudgeAndAMinorOneDoesNot() {
    Verdict blocked =
        JudgeVerdicts.verdict(
            "juez",
            null,
            SPEC,
            output(
                Map.of("resuelve", 5, "tests", 5, "alcance", 5),
                List.of(
                    finding("src/Calc.java", 12, "blocking", "Divide entre cero"),
                    finding("README.md", null, "minor", "Falta un ejemplo"))));
    Verdict minor =
        JudgeVerdicts.verdict(
            "juez",
            null,
            SPEC,
            output(
                Map.of("resuelve", 5, "tests", 5, "alcance", 5),
                List.of(finding("README.md", null, "minor", "Falta un ejemplo"))));

    assertThat(blocked.passed()).isFalse();
    assertThat(blocked.reasons()).containsExactly("Hay 1 hallazgo que bloquea");
    assertThat(blocked.findings().getFirst().location()).isEqualTo("src/Calc.java:12");
    assertThat(minor.passed()).isTrue();
  }

  @Test
  void aCriterionTheJudgeDidNotScoreCountsAsOne() {
    Verdict verdict = verdict(Map.of("tests", 5, "alcance", 5));

    assertThat(verdict.criteria().getFirst().score()).isEqualTo(1);
    assertThat(verdict.criteria().getFirst().reason()).isEqualTo("El juez no lo puntuó");
    assertThat(verdict.passed()).isFalse();
  }

  @Test
  void scoresOutOfRangeAreClampedAndUnknownCriteriaIgnored() {
    Verdict verdict = verdict(Map.of("resuelve", 9, "tests", 0, "alcance", 5, "otro", 1));

    assertThat(verdict.criteria())
        .extracting(JudgeVerdicts.CriterionScore::score)
        .containsExactly(5, 1, 5);
  }

  @Test
  void withARubricFileTheJudgeSaysTheWeights() {
    JudgeSpec file = new JudgeSpec(SPEC.agents(), List.of(), ".skynet/rubrica.md", null, null, 2);
    JsonNode output =
        JsonMapper.shared()
            .valueToTree(
                Map.of(
                    "criteria",
                    List.of(
                        Map.of("id", "a", "score", 5, "reason", "", "weight", 3, "required", false),
                        Map.of("id", "b", "score", 2, "reason", "", "weight", 1, "required", true)),
                    "findings",
                    List.of(),
                    "summary",
                    "Bien"));

    Verdict verdict = JudgeVerdicts.verdict("juez", null, file, output);

    assertThat(verdict.score()).isEqualByComparingTo("4.25");
    assertThat(verdict.passed()).isFalse();
    assertThat(verdict.reasons()).containsExactly("`b` es obligatorio y saca 2");
  }

  @Test
  void withOneJudgeItDecides() {
    Aggregate aggregate =
        JudgeVerdicts.aggregate(List.of(verdict(Map.of("resuelve", 4, "tests", 4, "alcance", 3))));

    assertThat(aggregate.passed()).isTrue();
    assertThat(JudgeVerdicts.note(aggregate)).isEqualTo("3,83 / 5 · aprobado por 1 de 1");
  }

  @Test
  void withSeveralTheMajorityDecidesAndTheScoreIsTheMeanOfMeans() {
    Verdict good = verdict(Map.of("resuelve", 5, "tests", 5, "alcance", 5));
    Verdict fine = verdict(Map.of("resuelve", 4, "tests", 4, "alcance", 4));
    Verdict bad = verdict(Map.of("resuelve", 3, "tests", 2, "alcance", 2));

    Aggregate majority = JudgeVerdicts.aggregate(List.of(good, fine, bad));
    Aggregate tie = JudgeVerdicts.aggregate(List.of(good, bad));

    assertThat(majority.passed()).isTrue();
    assertThat(majority.approvals()).isEqualTo(2);
    assertThat(majority.score()).isEqualByComparingTo("3.83");
    assertThat(majority.criteria()).containsEntry("resuelve", new BigDecimal("4.00"));
    assertThat(tie.passed()).isFalse();
    assertThat(JudgeVerdicts.note(tie)).isEqualTo("3,75 / 5 · suspenso: aprueba 1 de 2");
  }

  @Test
  void aBlockingFindingFailsThePanelEvenIfTheMajorityApproves() {
    Verdict good = verdict(Map.of("resuelve", 5, "tests", 5, "alcance", 5));
    Verdict blocking =
        JudgeVerdicts.verdict(
            "seguridad",
            "security",
            SPEC,
            output(
                Map.of("resuelve", 5, "tests", 5, "alcance", 5),
                List.of(finding("src/Login.java", 3, "blocking", "La contraseña va al log"))));

    Aggregate aggregate = JudgeVerdicts.aggregate(List.of(good, good, blocking));

    assertThat(aggregate.approvals()).isEqualTo(2);
    assertThat(aggregate.passed()).isFalse();
    assertThat(JudgeVerdicts.report(List.of(good, good, blocking), aggregate))
        .contains("## Lo que hay que arreglar")
        .contains("- `src/Login.java:3`: La contraseña va al log (seguridad)");
  }

  @Test
  void theReportTellsTheFixerTheLowCriteria() {
    Verdict bad =
        JudgeVerdicts.verdict(
            "juez", null, SPEC, output(Map.of("resuelve", 2, "tests", 4, "alcance", 5), List.of()));

    String report = JudgeVerdicts.report(List.of(bad), JudgeVerdicts.aggregate(List.of(bad)));

    assertThat(report)
        .startsWith("Nota: 3,17 / 5 · suspenso: aprueba 0 de 1.")
        .contains("## Criterios con nota baja")
        .contains("- `resuelve`: 2 / 5. motivo de resuelve (juez)")
        .doesNotContain("`tests`");
  }

  @Test
  void theOutputSchemaAsksForCriteriaFindingsAndSummary() {
    JsonNode inline = JsonMapper.shared().readTree(JudgeVerdicts.outputSchema(false));
    JsonNode file = JsonMapper.shared().readTree(JudgeVerdicts.outputSchema(true));

    assertThat(inline.path("required").toString())
        .isEqualTo("[\"criteria\",\"findings\",\"summary\"]");
    assertThat(inline.path("properties").path("criteria").path("items").path("required").toString())
        .isEqualTo("[\"id\",\"score\",\"reason\"]");
    assertThat(file.path("properties").path("criteria").path("items").path("required").toString())
        .isEqualTo("[\"id\",\"score\",\"reason\",\"weight\",\"required\"]");
  }

  private static Verdict verdict(Map<String, Integer> scores) {
    return JudgeVerdicts.verdict("juez", null, SPEC, output(scores, List.of()));
  }

  private static JsonNode output(Map<String, Integer> scores, List<Map<String, Object>> findings) {
    return JsonMapper.shared()
        .valueToTree(
            Map.of(
                "criteria",
                scores.entrySet().stream()
                    .map(
                        e ->
                            Map.of(
                                "id",
                                e.getKey(),
                                "score",
                                e.getValue(),
                                "reason",
                                "motivo de " + e.getKey()))
                    .toList(),
                "findings",
                findings,
                "summary",
                "Resumen"));
  }

  private static Map<String, Object> finding(
      String file, Integer line, String severity, String text) {
    java.util.Map<String, Object> f = new java.util.LinkedHashMap<>();
    f.put("file", file);
    f.put("line", line);
    f.put("severity", severity);
    f.put("text", text);
    return f;
  }
}
