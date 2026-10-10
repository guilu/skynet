package dev.skynet.controlplane.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skynet.controlplane.definition.InputDefinition;
import dev.skynet.controlplane.shared.InvalidRequestException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Los datos de un lanzamiento se comprueban contra los {@code inputs} del workflow. */
class LaunchInputsTest {

  static final List<InputDefinition> INPUTS =
      List.of(
          new InputDefinition("issue", "string", true, null, null),
          new InputDefinition("depth", "number", false, 2, null),
          new InputDefinition("dryRun", "boolean", false, null, null));

  @Test
  void defaultsFillTheMissingValuesAndTypesAreConverted() {
    assertThat(LaunchInputs.resolve(INPUTS, Map.of("issue", "#12", "dryRun", "true")))
        .containsExactly(
            Map.entry("issue", "#12"),
            Map.entry("depth", new BigDecimal("2")),
            Map.entry("dryRun", true));
  }

  @Test
  void aRequiredValueCannotBeMissingOrBlank() {
    assertThatThrownBy(() -> LaunchInputs.resolve(INPUTS, Map.of("issue", "  ")))
        .isInstanceOf(InvalidRequestException.class)
        .hasMessage("Falta el dato obligatorio `issue`");
  }

  @Test
  void unknownValuesAreRejected() {
    assertThatThrownBy(() -> LaunchInputs.resolve(INPUTS, Map.of("issue", "#1", "foco", "x")))
        .hasMessage("El dato `foco` no es de este workflow; admite `issue`, `depth`, `dryRun`");
    assertThatThrownBy(() -> LaunchInputs.resolve(List.of(), Map.of("foco", "x")))
        .hasMessage("El dato `foco` no es de este workflow, que no pide ninguno");
  }

  @Test
  void valuesMustHaveTheirType() {
    assertThatThrownBy(() -> LaunchInputs.resolve(INPUTS, Map.of("issue", "#1", "depth", "mucho")))
        .hasMessage("El dato `depth` tiene que ser un número");
    assertThatThrownBy(() -> LaunchInputs.resolve(INPUTS, Map.of("issue", "#1", "dryRun", 1)))
        .hasMessage("El dato `dryRun` tiene que ser true o false");
    assertThatThrownBy(() -> LaunchInputs.resolve(INPUTS, Map.of("issue", 12)))
        .hasMessage("El dato `issue` tiene que ser un texto");
  }
}
