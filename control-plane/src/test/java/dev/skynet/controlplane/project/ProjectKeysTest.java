package dev.skynet.controlplane.project;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProjectKeysTest {

  @Test
  void shortNamesWholeLongNamesTheirFirstThreeLetters() {
    assertThat(ProjectKeys.base("forma")).isEqualTo("FORMA");
    assertThat(ProjectKeys.base("akademia")).isEqualTo("AKA");
    assertThat(ProjectKeys.base("tokenmeter")).isEqualTo("TOK");
    assertThat(ProjectKeys.base("Árbol 2")).isEqualTo("ARBOL2");
    assertThat(ProjectKeys.base("demo-repo")).isEqualTo("DEM");
  }

  @Test
  void alwaysAValidKey() {
    assertThat(ProjectKeys.base("42")).isEqualTo("P42");
    assertThat(ProjectKeys.base("x")).isEqualTo("XX");
    assertThat(ProjectKeys.base("---")).isEqualTo("PRJ");
    assertThat(ProjectKeys.base(null)).isEqualTo("PRJ");
    for (String name : new String[] {"forma", "42", "x", "ñandú", "a1b2c3d4e5f6"}) {
      assertThat(ProjectKeys.base(name)).matches("^[A-Z][A-Z0-9]{1,9}$");
    }
  }
}
