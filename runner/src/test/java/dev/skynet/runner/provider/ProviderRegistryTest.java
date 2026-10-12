package dev.skynet.runner.provider;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skynet.protocol.runner.ProviderInfo;
import dev.skynet.runner.TestAgents;
import dev.skynet.runner.provider.claude.ClaudeCodeProvider;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProviderRegistryTest {

  private static final Map<String, String> ENV = TestAgents.fakeClaudeEnv("01-simple-text");

  @Test
  void declaresTheProvidersWhoseCliAnswersVersion() {
    ProviderRegistry registry =
        ProviderRegistry.detect(List.of(TestAgents.fakeClaude()), null, ENV);

    assertThat(registry.available()).extracting(ProviderInfo::id).containsExactly("claude-code");
    assertThat(registry.available().getFirst().version()).contains("fake-claude");
    assertThat(registry.get("claude-code")).isPresent();
  }

  @Test
  void aCliThatIsNotInstalledIsNotDeclared() {
    ClaudeCodeProvider missing = new ClaudeCodeProvider("/no/existe/claude", List.of());

    ProviderRegistry registry = ProviderRegistry.detect(List.of(missing), null, ENV);

    assertThat(registry.available()).isEmpty();
    assertThat(registry.get("claude-code")).isEmpty();
  }

  @Test
  void skynetProvidersDeclaresExactlyThoseEvenIfVersionFails() {
    ClaudeCodeProvider missing = new ClaudeCodeProvider("/no/existe/claude", List.of());

    assertThat(ProviderRegistry.detect(List.of(missing), Set.of("claude-code"), ENV).available())
        .containsExactly(new ProviderInfo("claude-code", null));
    assertThat(
            ProviderRegistry.detect(List.of(TestAgents.fakeClaude()), Set.of("codex"), ENV)
                .available())
        .isEmpty();
  }
}
