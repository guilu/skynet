package dev.skynet.controlplane.trigger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skynet.controlplane.shared.InvalidRequestException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class IssueBodyTest {

  private static final ObjectMapper JSON = JsonMapper.builder().build();

  private static final String ORDER =
      """
      ```skynet-order
      {"skynet_project":"SKY","target_repository":"skynet","workflow":"safe-check",\
      "parameters":{"scope":"tests","n":2,"rapido":true},"description":"Run bounded checks"}
      ```
      """;

  @Test
  void readsTheOrderContractOfTheBridge() {
    IssueBody.Order order = IssueBody.order(ORDER, JSON);
    assertThat(order.project()).isEqualTo("SKY");
    assertThat(order.repository()).isEqualTo("skynet");
    assertThat(order.workflow()).isEqualTo("safe-check");
    assertThat(order.parameters()).containsEntry("scope", "tests").containsEntry("rapido", true);
    assertThat(((Number) order.parameters().get("n")).intValue()).isEqualTo(2);
    assertThat(order.description()).isEqualTo("Run bounded checks");
  }

  @Test
  void anOrderMustBeTheWholeBodyWithExactlyItsFields() {
    assertThatThrownBy(() -> IssueBody.order("Hola\n\n" + ORDER, JSON))
        .isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(
            () ->
                IssueBody.order(
                    ORDER.replace(",\"description\"", ",\"extra\":1,\"description\""), JSON))
        .hasMessageContaining("exactamente");
    assertThatThrownBy(() -> IssueBody.order(ORDER.replace("\"skynet\"", "\"\""), JSON))
        .hasMessageContaining("target_repository");
    assertThatThrownBy(
            () ->
                IssueBody.order(
                    ORDER.replace("{\"scope\":\"tests\",\"n\":2,\"rapido\":true}", "[]"), JSON))
        .hasMessageContaining("parameters");
    assertThatThrownBy(() -> IssueBody.order(ORDER.replace("\"tests\"", "{\"a\":1}"), JSON))
        .hasMessageContaining("`scope`");
    assertThatThrownBy(() -> IssueBody.order("```skynet-order\nno es json\n```", JSON))
        .hasMessageContaining("JSON");
  }

  @Test
  void inputsComeFromAnOptionalSkynetBlock() {
    assertThat(IssueBody.inputs("Sin bloque", JSON)).isEmpty();
    assertThat(IssueBody.inputs(null, JSON)).isEmpty();
    assertThat(IssueBody.inputs("Hazlo.\n\n```skynet\n{\"alcance\": \"docs\"}\n```\nGracias", JSON))
        .isEqualTo(Map.of("alcance", "docs"));
    // Un bloque skynet-order no cuenta como datos.
    assertThat(IssueBody.inputs(ORDER, JSON)).isEmpty();
    assertThatThrownBy(() -> IssueBody.inputs("```skynet\n[1]\n```", JSON))
        .hasMessageContaining("objeto JSON");
    assertThatThrownBy(() -> IssueBody.inputs("```skynet\n{\"a\": [1]}\n```", JSON))
        .hasMessageContaining("`a`");
    assertThat(List.of()).isEmpty();
  }
}
