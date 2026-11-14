package dev.skynet.controlplane.definition;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skynet.controlplane.definition.DefinitionParser.Expectations;
import dev.skynet.controlplane.definition.WorkflowOverview.Input;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** El resumen de las tarjetas (WF-B), sobre los workflows sembrados y un agente suelto. */
class WorkflowOverviewTest {

  private static final Path DIR =
      Path.of(System.getProperty("skynet.seedWorkflows", "../workflows"));

  private static WorkflowOverview seed(String key) throws IOException {
    String source =
        SeedWorkflowsTest.substitute(
            Files.readString(DIR.resolve(key + ".yaml")), SeedWorkflowsTest.models());
    return WorkflowOverview.of(DefinitionParser.parse(source, Expectations.NONE).definition());
  }

  @Test
  void aWorkflowThatDeliversByPullRequest() throws IOException {
    WorkflowOverview overview = seed("especificar-planificar-implementar");

    assertThat(overview.stages()).isEqualTo(9);
    assertThat(overview.parallelChildren()).isZero();
    assertThat(overview.gates()).isEqualTo(2);
    assertThat(overview.judges()).isEqualTo(1);
    assertThat(overview.providers()).containsExactly("antigravity", "claude-code", "codex");
    assertThat(overview.fallbackProviders()).isEmpty();
    assertThat(overview.lanes()).containsExactly("spec", "plan", "implement", "review", "deliver");
    assertThat(overview.inputs()).isEmpty();
    assertThat(overview.delivers()).isTrue();
    assertThat(overview.readOnly()).isFalse();
  }

  @Test
  void twoJudgesCountTwice() throws IOException {
    WorkflowOverview overview = seed("implementar-tarea");

    assertThat(overview.judges()).isEqualTo(2);
    assertThat(overview.gates()).isEqualTo(1);
    assertThat(overview.lanes()).containsExactly("spec", "implement", "review", "deliver");
  }

  @Test
  void theConditionalGateOfCorregirBugIsAGate() throws IOException {
    WorkflowOverview overview = seed("corregir-bug");

    assertThat(overview.stages()).isEqualTo(8);
    assertThat(overview.gates()).isEqualTo(2);
    assertThat(overview.providers()).containsExactly("codex", "claude-code", "antigravity");
    // En el orden de los carriles del grafo, no en el de las fases.
    assertThat(overview.lanes()).containsExactly("implement", "test", "review", "deliver");
  }

  @Test
  void parallelReviewersThatOnlyRead() throws IOException {
    WorkflowOverview overview = seed("revisar-codigo");

    assertThat(overview.stages()).isEqualTo(2);
    assertThat(overview.parallelChildren()).isEqualTo(3);
    assertThat(overview.providers()).containsExactly("claude-code", "codex", "antigravity");
    assertThat(overview.inputs()).containsExactly(new Input("foco", false));
    assertThat(overview.delivers()).isFalse();
    assertThat(overview.readOnly()).isTrue();
  }

  @Test
  void aFallbackThatDoesNotWorkOnItsOwnIsListedApart() throws IOException {
    WorkflowOverview overview = seed("investigar-mejoras");

    assertThat(overview.providers()).containsExactly("claude-code");
    assertThat(overview.fallbackProviders()).containsExactly("codex");
    assertThat(overview.lanes()).containsExactly("plan");
    assertThat(overview.readOnly()).isTrue();
  }

  @Test
  void committingTheMapIsNotReadOnly() throws IOException {
    WorkflowOverview overview = seed("conocer-repositorio");

    assertThat(overview.providers()).containsExactly("codex", "antigravity");
    assertThat(overview.fallbackProviders()).containsExactly("claude-code");
    assertThat(overview.delivers()).isTrue();
    assertThat(overview.readOnly()).isFalse();
  }

  @Test
  void anAgentStageWithoutAgentUsesTheRepository() {
    String source =
        """
        id: suelto
        inputs:
          prompt:
            type: text
            required: true
        stages:
          - id: agent
            type: agent
            prompt: "{{inputs.prompt}}"
        """;
    WorkflowOverview overview =
        WorkflowOverview.of(DefinitionParser.parse(source, Expectations.NONE).definition());

    assertThat(overview.providers()).containsExactly(WorkflowOverview.REPOSITORY);
    assertThat(overview.lanes()).isEmpty();
    assertThat(overview.inputs()).containsExactly(new Input("prompt", true));
    assertThat(overview.readOnly()).isFalse();
    assertThat(List.of(overview.gates(), overview.judges())).containsOnly(0);
  }
}
