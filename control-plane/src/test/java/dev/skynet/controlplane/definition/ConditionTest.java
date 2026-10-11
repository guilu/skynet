package dev.skynet.controlplane.definition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skynet.controlplane.definition.Condition.Type;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** El lenguaje de las condiciones: lectura, tipos y evaluación. */
class ConditionTest {

  @Test
  void evaluatesPathsLiteralsAndOperators() {
    Map<String, Object> values = new HashMap<>();
    values.put("review.hasBlockingFindings", true);
    values.put("tests.exitCode", 0);
    values.put("inputs.foco", "seguridad");
    values.put("review.score", 7.5);

    assertThat(test("review.hasBlockingFindings", values)).isTrue();
    assertThat(test("!review.hasBlockingFindings", values)).isFalse();
    assertThat(test("tests.exitCode == 0 && review.hasBlockingFindings", values)).isTrue();
    assertThat(test("tests.exitCode != 0 || inputs.foco == 'seguridad'", values)).isTrue();
    assertThat(test("review.score >= 7 && review.score < 8", values)).isTrue();
    assertThat(test("inputs.foco in [\"calidad\", 'seguridad']", values)).isTrue();
    assertThat(test("tests.exitCode in [1, 2]", values)).isFalse();
    assertThat(test("!(tests.exitCode > 0)", values)).isTrue();
  }

  @Test
  void aMissingValueIsNullAndNullCountsAsFalse() {
    Map<String, Object> values = new HashMap<>();
    values.put("fix.status", "SKIPPED");

    assertThat(test("review.hasBlockingFindings", values)).isFalse();
    assertThat(test("!review.hasBlockingFindings", values)).isTrue();
    assertThat(test("review.score > 3", values)).isFalse();
    assertThat(test("review.hasBlockingFindings == null", values)).isTrue();
    assertThat(test("fix.status == 'SKIPPED'", values)).isTrue();
  }

  @Test
  void aValueOfTheWrongTypeFailsTheEvaluation() {
    assertThatThrownBy(
            () -> test("review.hasBlockingFindings", Map.of("review.hasBlockingFindings", "sí")))
        .isInstanceOf(Condition.EvaluationError.class)
        .hasMessageContaining("tendría que ser verdadero o falso");
  }

  @Test
  void syntaxErrorsSayWhere() {
    assertSyntax("review.ok &&", "La condición termina antes de tiempo", 12);
    assertSyntax("(review.ok", "Falta el `)` que cierra el paréntesis", 10);
    assertSyntax("review.ok = true", "`=` no se entiende aquí", 10);
    assertSyntax("review.", "Falta un nombre después del punto", 7);
    assertSyntax("inputs.rama == 'main", "Falta la comilla que cierra el texto", 15);
    assertSyntax("review.ok review.ko", "Sobra `review`", 10);
  }

  @Test
  void typesMustFit() {
    Map<String, Type> types =
        Map.of(
            "review.ok", Type.BOOLEAN, "tests.exitCode", Type.NUMBER, "inputs.rama", Type.STRING);

    check("review.ok && tests.exitCode == 0 && inputs.rama in ['main', 'dev']", types);
    assertType(
        "tests.exitCode",
        types,
        "La condición tiene que ser verdadera o falsa, y esto es un número");
    assertType(
        "tests.exitCode && review.ok",
        types,
        "`&&` necesita algo verdadero o falso, y esto es un número");
    assertType("tests.exitCode == 'cero'", types, "No se puede comparar un número con un texto");
    assertType(
        "review.ok > 1", types, "`>` compara dos números o dos textos, no un booleano y un número");
    assertType("inputs.rama in 'main'", types, "Después de `in` va una lista, p. ej. `[1, 2]`");
  }

  @Test
  void listsThePathsItReads() {
    assertThat(Condition.paths(Condition.parse("a.ok && (b.n > 1 || a.ok) && c-d.status == 'X'")))
        .extracting(Condition.Path::text)
        .containsExactly("a.ok", "b.n", "c-d.status");
  }

  private static boolean test(String condition, Map<String, ?> values) {
    return Condition.test(Condition.parse(condition), values::get);
  }

  private static void check(String condition, Map<String, Type> types) {
    Condition.check(Condition.parse(condition), p -> types.get(p.text()));
  }

  private static void assertSyntax(String condition, String message, int position) {
    assertThatThrownBy(() -> Condition.parse(condition))
        .isInstanceOfSatisfying(
            Condition.SyntaxError.class,
            e -> {
              assertThat(e.getMessage()).isEqualTo(message);
              assertThat(e.position()).isEqualTo(position);
            });
  }

  private static void assertType(String condition, Map<String, Type> types, String message) {
    assertThatThrownBy(() -> check(condition, types))
        .isInstanceOf(Condition.SyntaxError.class)
        .hasMessage(message);
  }
}
