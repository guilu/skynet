package dev.skynet.controlplane;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTest {

  @Test
  void verifiesModuleBoundaries() {
    ApplicationModules.of(ControlPlaneApplication.class).verify();
  }
}
