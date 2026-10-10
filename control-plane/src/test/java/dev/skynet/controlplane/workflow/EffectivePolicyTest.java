package dev.skynet.controlplane.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skynet.controlplane.definition.AgentDefinition;
import dev.skynet.controlplane.project.AgentPolicy;
import dev.skynet.protocol.runner.AgentLimits;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Lo que se le permite a un agente del YAML nunca pasa de la política del repositorio. */
class EffectivePolicyTest {

  static final AgentPolicy POLICY =
      new AgentPolicy(
          List.of("Read", "Edit", "Bash"),
          "acceptEdits",
          List.of("CI=1"),
          50,
          new BigDecimal("5"),
          60);
  static final AgentLimits LAUNCH =
      new AgentLimits(20, new BigDecimal("2"), Duration.ofMinutes(30));

  @Test
  void withoutAnAgentTheRepositoryPolicyAndLaunchLimitsApply() {
    EffectivePolicy effective = EffectivePolicy.of(POLICY, null, LAUNCH);

    assertThat(effective.allowedTools()).containsExactly("Read", "Edit", "Bash");
    assertThat(effective.permissionMode()).isEqualTo("acceptEdits");
    assertThat(effective.environment()).containsExactly("CI=1");
    assertThat(effective.limits()).isEqualTo(LAUNCH);
  }

  @Test
  void anAgentOnlyKeepsTheToolsThePolicyAllows() {
    EffectivePolicy effective =
        EffectivePolicy.of(POLICY, agent(List.of("Read", "Bash(git:*)", "WebFetch"), null), LAUNCH);

    assertThat(effective.allowedTools()).containsExactly("Read", "Bash(git:*)");
  }

  @Test
  void aNarrowerPolicyToolDoesNotAllowTheWholeTool() {
    AgentPolicy narrow =
        new AgentPolicy(List.of("Bash(git:*)"), "default", List.of(), null, null, null);

    assertThat(EffectivePolicy.of(narrow, agent(List.of("Bash"), null), LAUNCH).allowedTools())
        .isEmpty();
  }

  @Test
  void anAgentCanBeStricterButNotMorePermissive() {
    assertThat(EffectivePolicy.of(POLICY, agent(null, "plan"), LAUNCH).permissionMode())
        .isEqualTo("plan");

    AgentPolicy strict = new AgentPolicy(List.of("Read"), "default", List.of(), null, null, null);
    assertThat(EffectivePolicy.of(strict, agent(null, "acceptEdits"), LAUNCH).permissionMode())
        .isEqualTo("default");
  }

  @Test
  void anAgentsLimitsAreCappedByThePolicy() {
    AgentDefinition agent =
        new AgentDefinition(
            "a", null, null, null, null, 200, new BigDecimal("1"), null, null, null);

    AgentLimits limits = EffectivePolicy.of(POLICY, agent, LAUNCH).limits();

    assertThat(limits.maxTurns()).isEqualTo(50);
    assertThat(limits.maxBudgetUsd()).isEqualByComparingTo("1");
    assertThat(limits.timeout()).isEqualTo(Duration.ofMinutes(30));
  }

  private static AgentDefinition agent(List<String> tools, String mode) {
    return new AgentDefinition("a", null, null, tools, mode, null, null, null, null, null);
  }
}
