package dev.skynet.controlplane.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PullRequestBodyTest {

  @Test
  void closesTheIssueTheWorkItemCameFrom() {
    assertThat(PullRequestBody.closing("github:guilu/demo-repo#7", "guilu/demo-repo"))
        .isEqualTo("Closes #7");
    assertThat(PullRequestBody.closing("github:Guilu/Demo-Repo#7", "guilu/demo-repo"))
        .isEqualTo("Closes #7");
    assertThat(PullRequestBody.closing("github:guilu/skynet-commands#3", "guilu/demo-repo"))
        .isEqualTo("Closes guilu/skynet-commands#3");
    assertThat(PullRequestBody.closing(null, "guilu/demo-repo")).isNull();
    assertThat(PullRequestBody.closing("JIRA-12", "guilu/demo-repo")).isNull();
    assertThat(PullRequestBody.closing("github:guilu/demo-repo#7 y más", "guilu/demo-repo"))
        .isNull();

    String body =
        PullRequestBody.of(
            null, "DEMO-1", "Resta", null, List.of(), null, null, UUID.randomUUID(), "Closes #7");
    assertThat(body).contains("\nCloses #7\n").endsWith("Abierta por Skynet.\n");
    assertThat(
            PullRequestBody.of(
                null, "DEMO-1", "Resta", null, List.of(), null, null, UUID.randomUUID(), null))
        .doesNotContain("Closes");
  }

  @Test
  void aStageWithDetailsGetsAFoldedBlockUnderTheTable() {
    String body =
        PullRequestBody.of(
            null,
            "DEMO-1",
            "Resta",
            null,
            List.of(
                new PullRequestBody.StageLine(
                    "guia",
                    "Guía de pruebas",
                    "terminada bien",
                    "Guía de pruebas: 2 de 2 pasos pasan",
                    List.of(),
                    "## 1. Tests\n\n- ✓ Pasan los tests\n"),
                new PullRequestBody.StageLine("pr", null, "terminada bien", null, List.of())),
            null,
            null,
            UUID.randomUUID(),
            null);
    assertThat(body)
        .contains("| Guía de pruebas | terminada bien | Guía de pruebas: 2 de 2 pasos pasan |")
        .contains(
            "<details><summary>Guía de pruebas</summary>\n\n## 1. Tests\n\n- ✓ Pasan los"
                + " tests\n\n</details>");
    assertThat(body.split("<details>", -1)).hasSize(2);
  }

  @Test
  void theRunsAssumptionsGoInTheirOwnSectionWithTheirStage() {
    String body =
        PullRequestBody.of(
            null,
            "DEMO-1",
            "Resta",
            null,
            List.of(),
            List.of(
                new RunAssumptions.Assumption("spec", "El límite es por usuario"),
                new RunAssumptions.Assumption("implementar", "La API acepta | en el nombre")),
            null,
            null,
            UUID.randomUUID(),
            null);
    assertThat(body)
        .contains("## Supuestos para revisar")
        .contains("No ha parado nada")
        .contains("- El límite es por usuario (`spec`)")
        .contains("(`implementar`)");
    assertThat(
            PullRequestBody.of(
                null, "DEMO-1", "Resta", null, List.of(), null, null, UUID.randomUUID(), null))
        .doesNotContain("Supuestos");
  }
}
