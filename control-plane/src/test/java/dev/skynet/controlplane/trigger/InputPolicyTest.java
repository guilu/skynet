package dev.skynet.controlplane.trigger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InputPolicyTest {

  private static final Map<String, InputPolicy.Rule> POLICY =
      Map.of(
          "alcance", new InputPolicy.Rule("string", 10, List.of("tests", "docs")),
          "rondas", new InputPolicy.Rule("number", null, null),
          "rapido", new InputPolicy.Rule("boolean", null, null),
          "notas", new InputPolicy.Rule("text", null, null));

  @Test
  void acceptsWhatThePolicyAllows() {
    assertThat(InputPolicy.apply(POLICY, Map.of("alcance", "tests", "rondas", 2, "rapido", false)))
        .containsEntry("alcance", "tests")
        .containsEntry("rondas", 2)
        .containsEntry("rapido", false);
    assertThat(InputPolicy.apply(POLICY, Map.of())).isEmpty();
  }

  @Test
  void rejectsUnknownBadlyTypedTooLongOrNotAllowedValues() {
    assertThatThrownBy(() -> InputPolicy.apply(POLICY, Map.of("otro", "x")))
        .hasMessageContaining("`otro` no se puede dar desde GitHub");
    assertThatThrownBy(() -> InputPolicy.apply(Map.of(), Map.of("otro", "x")))
        .hasMessageContaining("no admite ninguno");
    assertThatThrownBy(() -> InputPolicy.apply(POLICY, Map.of("rondas", "2")))
        .hasMessageContaining("de tipo number");
    assertThatThrownBy(() -> InputPolicy.apply(POLICY, Map.of("rapido", "sí")))
        .hasMessageContaining("de tipo boolean");
    assertThatThrownBy(() -> InputPolicy.apply(POLICY, Map.of("alcance", "todo")))
        .hasMessageContaining("no está entre los admitidos");
    assertThatThrownBy(() -> InputPolicy.apply(POLICY, Map.of("notas", "x".repeat(1001))))
        .hasMessageContaining("como mucho 1000");
    assertThatThrownBy(() -> InputPolicy.apply(POLICY, Map.of("rondas", 1e13)))
        .hasMessageContaining("de tipo number");
  }

  @Test
  void aPolicyCannotLetTheIssueGiveInstructions() {
    assertThatThrownBy(
            () -> InputPolicy.validate(Map.of("prompt", new InputPolicy.Rule("text", null, null))))
        .hasMessageContaining("parece una instrucción");
    assertThatThrownBy(
            () ->
                InputPolicy.validate(
                    Map.of("systemMessage", new InputPolicy.Rule("string", null, null))))
        .hasMessageContaining("parece una instrucción");
    assertThatThrownBy(
            () -> InputPolicy.validate(Map.of("a", new InputPolicy.Rule("object", null, null))))
        .hasMessageContaining("necesita un tipo");
    InputPolicy.validate(POLICY);
  }
}
