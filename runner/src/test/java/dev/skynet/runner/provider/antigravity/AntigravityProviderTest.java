package dev.skynet.runner.provider.antigravity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skynet.protocol.runner.AgentLimits;
import dev.skynet.protocol.runner.ResumeFrom;
import dev.skynet.protocol.runner.StartAgent;
import dev.skynet.runner.provider.ModelPrices;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AntigravityProviderTest {

  @TempDir Path dir;

  private final AntigravityProvider provider =
      new AntigravityProvider("agy", List.of("EXTRA"), ModelPrices.defaults());

  @Test
  void aStartAcceptsEditsAndPassesThePromptWithEquals() throws Exception {
    assertThat(provider.command(start(null, "dontAsk", null, null), dir.resolve("a")))
        .containsExactly(
            "agy",
            "--output-format",
            "stream-json",
            "--mode",
            "accept-edits",
            "--print=-arregla add");
  }

  @Test
  void aMessageResumesTheConversationAndAForkIsRefused() throws Exception {
    ResumeFrom resume = new ResumeFrom("c-1", false, "/w/a", "skynet/a");
    ResumeFrom fork = new ResumeFrom("c-1", true, "/w/b", "skynet/b");

    assertThat(provider.command(start("gemini-3-flash", null, resume, null), dir.resolve("a")))
        .containsSubsequence(
            "--model", "gemini-3-flash", "--conversation", "c-1", "--print=-arregla add");
    assertThatThrownBy(() -> provider.command(start(null, null, fork, null), dir.resolve("a")))
        .isInstanceOf(IOException.class)
        .hasMessage("Antigravity CLI no permite bifurcar una conversación");
  }

  @Test
  void theOutputSchemaGoesInAFile() throws Exception {
    String schema = "{\"type\":\"object\",\"properties\":{\"fixed\":{\"type\":\"boolean\"}}}";

    List<String> command = provider.command(start(null, null, null, schema), dir.resolve("a"));

    Path file = dir.resolve("a.schema.json");
    assertThat(command).containsSubsequence("--json-schema", file.toString());
    assertThat(Files.readString(file)).isEqualTo(schema);
    assertThat(command.getLast()).isEqualTo("--print=-arregla add");
  }

  @Test
  void thePermissionModeChoosesTheMode() {
    assertThat(AntigravityProvider.mode("plan")).containsExactly("--mode", "plan");
    assertThat(AntigravityProvider.mode("bypassPermissions"))
        .containsExactly("--dangerously-skip-permissions");
    assertThat(AntigravityProvider.mode("acceptEdits")).containsExactly("--mode", "accept-edits");
    assertThat(AntigravityProvider.mode(null)).containsExactly("--mode", "accept-edits");
  }

  @Test
  void theAgentInheritsOnlyTheBasicAndAllowedVariables() {
    Map<String, String> env =
        provider.environment(
            Map.of("PATH", "/bin", "OPENAI_API_KEY", "no", "EXTRA", "si", "OTRA", "no"), null);

    assertThat(env).containsOnlyKeys("PATH", "EXTRA");
  }

  private static StartAgent start(
      String model, String permissionMode, ResumeFrom resume, String schema) {
    return new StartAgent(
        UUID.randomUUID(),
        "TKM-1",
        "/repos/demo",
        "main",
        UUID.randomUUID(),
        "-arregla add",
        List.of("Read"),
        permissionMode,
        model,
        AgentLimits.none(),
        resume,
        null,
        null,
        null,
        schema,
        "antigravity");
  }
}
