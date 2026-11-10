package dev.skynet.runner.verify;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpecSectionsTest {

  private static final String COMPLETE =
      """
      # Exportar a CSV

      ## Qué se podrá hacer
      Descargar la tabla de resultados como CSV.

      ## Qué resultado se obtiene
      Un fichero como `docs/ejemplo.csv`.

      ## Qué debe respetar
      - Los filtros de la tabla.

      ## Cómo se demuestra
      `ExportTest` compara con el ejemplo.

      ## Fuera de alcance
      Excel.
      """;

  @Test
  void aCompleteProposalPasses() {
    assertThat(SpecSections.missing("p.md", COMPLETE)).isNull();
  }

  @Test
  void titlesAreRecognisedWithoutAccentsNumbersOrInEnglish() {
    String text =
        """
        ## 1. QUE PODRA HACER EL USUARIO:
        Exportar.
        ### Result
        Un CSV.
        ## Constraints ##
        Los filtros.
        ## 4. Cómo se comprueba
        Un test.
        ## Out of scope
        Excel.
        """;
    assertThat(SpecSections.missing("p.md", text)).isNull();
  }

  @Test
  void saysWhatIsMissingAndWhatIsEmpty() {
    String text =
        """
        ## Qué se podrá hacer
        Exportar.
        ## Qué resultado se obtiene
        Un CSV.
        ## Fuera de alcance
        TODO
        ## Otra cosa
        Texto que no es de Fuera de alcance.
        """;
    assertThat(SpecSections.missing("openspec/proposal.md", text))
        .isEqualTo(
            "A la propuesta `openspec/proposal.md` le falta «Qué debe respetar» y «Cómo se"
                + " demuestra»; está vacía «Fuera de alcance»");
  }

  @Test
  void aSubsectionCountsAsTextButTheNextSectionDoesNot() {
    String onlyEmpty =
        COMPLETE.replace(
            "## Cómo se demuestra\n`ExportTest` compara con el ejemplo.\n",
            "## Cómo se demuestra\n");
    assertThat(SpecSections.missing("p.md", onlyEmpty))
        .isEqualTo("En la propuesta `p.md` está vacía «Cómo se demuestra»");
    String nested =
        COMPLETE.replace(
            "## Cómo se demuestra\n`ExportTest` compara con el ejemplo.\n",
            "## Cómo se demuestra\n### Tests\n`ExportTest`.\n");
    assertThat(SpecSections.missing("p.md", nested)).isNull();
  }

  @Test
  void headingsInsideCodeAndCommentsAndTheTitleDoNotCount() {
    String text =
        """
        # Fuera de alcance
        Esto es el título del documento.
        <!-- ## Qué se podrá hacer
        nada -->
        ```
        ## Qué resultado se obtiene
        tampoco
        ```
        """;
    assertThat(SpecSections.missing("p.md", text))
        .startsWith(
            "A la propuesta `p.md` le falta «Qué se podrá hacer», «Qué resultado se obtiene»")
        .endsWith("y «Fuera de alcance»");
  }

  @Test
  void aMissingFileOrOneOutsideTheWorktreeDoesNotExist(@TempDir Path dir) throws Exception {
    assertThat(SpecSections.check(dir, "openspec/proposal.md"))
        .isEqualTo("`openspec/proposal.md` no existe");
    assertThat(SpecSections.check(dir, "../fuera.md")).isEqualTo("`../fuera.md` no existe");
    Files.createDirectories(dir.resolve("openspec"));
    Files.writeString(dir.resolve("openspec/proposal.md"), COMPLETE);
    assertThat(SpecSections.check(dir, "openspec/proposal.md")).isNull();
  }
}
