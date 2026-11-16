package dev.skynet.runner;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skynet.protocol.runner.ChatTurn;
import dev.skynet.protocol.runner.ProviderInfo;
import dev.skynet.runner.provider.ProviderRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RunnerMainTest {

  @Test
  void reportsDevVersionWhenNotPackaged() {
    assertThat(RunnerMain.version()).isEqualTo("dev");
  }

  @Test
  void withClaudeCodeTheRunnerAlsoDeclaresTheChatUnlessItIsTurnedOff() {
    ProviderRegistry claude = TestAgents.fakeProviders(Map.of());
    assertThat(RunnerMain.declared(claude, Map.of()))
        .extracting(ProviderInfo::id)
        .containsExactly("claude-code", ChatTurn.RUNNER_FEATURE);
    assertThat(RunnerMain.declared(claude, Map.of("SKYNET_RUNNER_CHAT", "false")))
        .extracting(ProviderInfo::id)
        .containsExactly("claude-code");
    assertThat(RunnerMain.declared(ProviderRegistry.of(TestAgents.fakeCodex()), Map.of()))
        .extracting(ProviderInfo::id)
        .containsExactly("codex");
  }
}
