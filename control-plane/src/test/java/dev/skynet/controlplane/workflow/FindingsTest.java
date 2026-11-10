package dev.skynet.controlplane.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class FindingsTest {

  @Test
  void theWordsLeaveOutAccentsNumbersCodeAndEmptyWords() {
    assertThat(
            Findings.words("Divide entre cero sin comprobarlo en `calc(x)` línea 12, para todos"))
        .containsExactly("cero", "comprobarlo", "divide", "linea");
  }

  @Test
  void theSameWordsInAnotherOrderHaveTheSameFingerprint() {
    assertThat(Findings.fingerprint(Findings.words("Falta el test de la división")))
        .isEqualTo(Findings.fingerprint(Findings.words("La división: falta el test")))
        .hasSize(16);
    assertThat(Findings.fingerprint(Findings.words("Falta el test de la división")))
        .isNotEqualTo(Findings.fingerprint(Findings.words("Falta el test de la suma")));
  }

  @Test
  void similarityIsJaccardOfTheWords() {
    List<String> a = Findings.words("Divide entre cero sin comprobarlo");
    List<String> b = Findings.words("Sigue dividiendo entre cero sin comprobarlo");
    // {cero, comprobarlo, divide} frente a {cero, comprobarlo, dividiendo, sigue}: 2 de 5.
    assertThat(Findings.similarity(a, b)).isEqualTo(0.4);
    assertThat(Findings.similarity(a, Findings.words("Divide entre cero sin comprobarlo antes")))
        .isEqualTo(0.75);
    assertThat(Findings.similarity(a, a)).isEqualTo(1);
    assertThat(Findings.similarity(List.of(), List.of())).isEqualTo(1);
    assertThat(Findings.similarity(a, List.of())).isZero();
  }
}
