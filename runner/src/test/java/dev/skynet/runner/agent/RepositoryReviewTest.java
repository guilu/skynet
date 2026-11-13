package dev.skynet.runner.agent;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skynet.protocol.runner.StartAgent;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryReviewTest {

  private static final String PROMPT =
      "## La rúbrica\n\nPuntúa.\n" + StartAgent.REVIEW_MARKER + "\n## Los hallazgos\n";

  @TempDir Path worktree;

  @Test
  void withoutAFileTheMarkerGoesAndThePromptIsAsBefore() throws Exception {
    RepositoryReview.Expanded expanded = RepositoryReview.expand(PROMPT, worktree);
    assertThat(expanded.file()).isNull();
    assertThat(expanded.prompt()).isEqualTo("## La rúbrica\n\nPuntúa.\n\n## Los hallazgos\n");
  }

  @Test
  void reviewMdGoesUnderTheRubricAndSkynetsOwnFileWins() throws Exception {
    Files.writeString(worktree.resolve("REVIEW.md"), "No señales los nombres de variables.\n");
    RepositoryReview.Expanded expanded = RepositoryReview.expand(PROMPT, worktree);
    assertThat(expanded.file()).isEqualTo("REVIEW.md");
    assertThat(expanded.prompt())
        .startsWith("## La rúbrica\n\nPuntúa.\n\n## Criterios de este repositorio\n\n")
        .contains("El repositorio dice en `REVIEW.md` cómo quiere que se le revise.")
        .contains("No señales los nombres de variables.\n\n## Los hallazgos\n")
        .doesNotContain("skynet:review");

    Files.createDirectories(worktree.resolve(".skynet"));
    Files.writeString(worktree.resolve(".skynet/review.md"), "Solo fallos de seguridad.");
    RepositoryReview.Expanded own = RepositoryReview.expand(PROMPT, worktree);
    assertThat(own.file()).isEqualTo(".skynet/review.md");
    assertThat(own.prompt()).contains("Solo fallos de seguridad.").doesNotContain("variables");
  }

  @Test
  void aLongFileIsCutAtEightKilobytesWithoutBreakingACharacter() throws Exception {
    Files.writeString(worktree.resolve("REVIEW.md"), "€".repeat(3000));
    String prompt = RepositoryReview.expand(PROMPT, worktree).prompt();
    assertThat(prompt).contains("(El fichero sigue: pasa de 8 KB y aquí se corta.)");
    assertThat(prompt.chars().filter(c -> c == '€').count()).isEqualTo(2730);
  }

  @Test
  void anEmptyFileOrALinkOrAPromptWithoutMarkerChangeNothing() throws Exception {
    Files.writeString(worktree.resolve("REVIEW.md"), "  \n");
    assertThat(RepositoryReview.expand(PROMPT, worktree).file()).isNull();
    Files.delete(worktree.resolve("REVIEW.md"));
    Path outside = Files.writeString(worktree.getParent().resolve("fuera.md"), "Fuera");
    Files.createSymbolicLink(worktree.resolve("REVIEW.md"), outside);
    assertThat(RepositoryReview.expand(PROMPT, worktree).file()).isNull();
    assertThat(RepositoryReview.expand("Implementa", worktree))
        .isEqualTo(new RepositoryReview.Expanded("Implementa", null));
  }

  @Test
  void theLearnedLineSaysHowManyCriteriaThePromptCarriesAndIsRemoved() {
    String prompt =
        "Juzga\n\n## Lo aprendido en este repositorio\n\nNo señales A\n\nNo señales B\n"
            + StartAgent.learnedMarker(2)
            + "\n## Los hallazgos\n";
    assertThat(RepositoryReview.learned(prompt)).isEqualTo(2);
    assertThat(RepositoryReview.withoutLearned(prompt))
        .isEqualTo(
            "Juzga\n\n## Lo aprendido en este repositorio\n\nNo señales A\n\nNo señales B\n"
                + "\n## Los hallazgos\n");
    assertThat(RepositoryReview.learned("Implementa")).isZero();
    assertThat(RepositoryReview.withoutLearned("Implementa")).isEqualTo("Implementa");
  }
}
