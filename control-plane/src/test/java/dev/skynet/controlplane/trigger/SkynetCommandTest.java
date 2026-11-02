package dev.skynet.controlplane.trigger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skynet.controlplane.shared.InvalidRequestException;
import org.junit.jupiter.api.Test;

class SkynetCommandTest {

  @Test
  void onlyACommentThatStartsWithTheCommandCounts() {
    assertThat(SkynetCommand.parse("Gracias, /skynet")).isNull();
    assertThat(SkynetCommand.parse("/skynetx estado")).isNull();
    assertThat(SkynetCommand.parse(null)).isNull();
    assertThat(SkynetCommand.parse("  /Skynet ESTADO  ").verb()).isEqualTo("estado");
    assertThat(SkynetCommand.parse("/skynet").verb()).isEmpty();
  }

  @Test
  void theTextIsTheRestOfTheLineAndTheFollowingOnes() {
    SkynetCommand command = SkynetCommand.parse("/skynet corrige Añade tests\n\n- de resta()\r\n");
    assertThat(command.verb()).isEqualTo("corrige");
    assertThat(command.rest()).isEqualTo("Añade tests");
    assertThat(command.text()).isEqualTo("Añade tests\n\n- de resta()");
    assertThat(command.line()).isEqualTo("/skynet corrige Añade tests");
    assertThat(SkynetCommand.parse("/skynet corrige\nSolo abajo").text()).isEqualTo("Solo abajo");
  }

  @Test
  void theArgumentsAreKeyValuePairs() {
    assertThat(SkynetCommand.parse("/skynet tarea alcance=tests limite=3\nnota").args())
        .containsExactly(
            java.util.Map.entry("alcance", "tests"), java.util.Map.entry("limite", "3"));
    assertThatThrownBy(() -> SkynetCommand.parse("/skynet tarea hazlo bien").args())
        .isInstanceOf(InvalidRequestException.class)
        .hasMessageContaining("`hazlo` no es un dato");
  }
}
