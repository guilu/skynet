package dev.skynet.controlplane;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ControlPlaneApplicationTest {

  @Test
  void withTheDatabasePasswordRequiredItHasToBeSetExplicitly() {
    // Docker Compose (perfil app) lo exige: sin él, la API y PostgreSQL usarían skynet/skynet.
    Map<String, String> required = Map.of("SKYNET_REQUIRE_DB_PASSWORD", "true");
    assertThatThrownBy(() -> ControlPlaneApplication.checkDatabasePassword(required))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SKYNET_DB_PASSWORD");
    assertThatThrownBy(
            () ->
                ControlPlaneApplication.checkDatabasePassword(
                    Map.of("SKYNET_REQUIRE_DB_PASSWORD", "true", "SKYNET_DB_PASSWORD", " ")))
        .isInstanceOf(IllegalStateException.class);

    assertThatCode(
            () ->
                ControlPlaneApplication.checkDatabasePassword(
                    Map.of("SKYNET_REQUIRE_DB_PASSWORD", "true", "SKYNET_DB_PASSWORD", "otra")))
        .doesNotThrowAnyException();
  }

  @Test
  void withoutTheRequirementTheLocalDefaultStillWorks() {
    assertThatCode(() -> ControlPlaneApplication.checkDatabasePassword(Map.of()))
        .doesNotThrowAnyException();
  }
}
