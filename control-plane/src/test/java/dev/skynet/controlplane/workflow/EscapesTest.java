package dev.skynet.controlplane.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EscapesTest {

  @Test
  void aRevertPullRequestSaysWhichPullRequestOfTheRepositoryItReverts() {
    assertThat(
            Escapes.revertedNumber(
                "guilu/demo-repo", "Revert \"Arreglar add()\"", "Reverts guilu/demo-repo#12"))
        .isEqualTo(12L);
    assertThat(
            Escapes.revertedNumber(
                "Guilu/Demo-Repo",
                "Revert \"Arreglar add()\"",
                "Motivo\n\n  reverts guilu/demo-repo#7 porque sí"))
        .isEqualTo(7L);
    // Otro repositorio, sin «Revert "» en el título o sin la línea: no.
    assertThat(
            Escapes.revertedNumber(
                "guilu/demo-repo", "Revert \"Arreglar add()\"", "Reverts guilu/otro#12"))
        .isNull();
    assertThat(
            Escapes.revertedNumber(
                "guilu/demo-repo", "Arreglar add()", "Reverts guilu/demo-repo#12"))
        .isNull();
    assertThat(Escapes.revertedNumber("guilu/demo-repo", "Revert \"X\"", "Sin más")).isNull();
    assertThat(Escapes.revertedNumber(null, null, null)).isNull();
  }

  @Test
  void aCommitMessageSaysWhichCommitsItReverts() {
    assertThat(
            Escapes.revertedShas(
                "Revert \"X\"\n\nThis reverts commit 1A2B3C4D.\nThis reverts commit"
                    + " abcdef0123456789abcdef0123456789abcdef01, reversing changes"))
        .containsExactly("1a2b3c4d", "abcdef0123456789abcdef0123456789abcdef01");
    // Menos de 7 cifras, o sin la frase: nada.
    assertThat(Escapes.revertedShas("This reverts commit abc12.")).isEmpty();
    assertThat(Escapes.revertedShas("Arregla add()")).isEmpty();
    assertThat(Escapes.revertedShas(null)).isEmpty();
  }
}
