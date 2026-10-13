package dev.skynet.runner.provider.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skynet.protocol.runner.AgentLimits;
import dev.skynet.protocol.runner.ResumeFrom;
import dev.skynet.protocol.runner.StartAgent;
import dev.skynet.runner.provider.ModelPrices;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GeminiProviderTest {

  private static final UUID SESSION = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

  @TempDir Path dir;

  private final GeminiProvider provider =
      new GeminiProvider("gemini", List.of("EXTRA"), ModelPrices.defaults());

  @Test
  void aStartFixesTheSessionTheControlPlaneChose() throws Exception {
    assertThat(provider.command(start(null, "dontAsk", null, null), dir.resolve("a")))
        .containsExactly(
            "gemini",
            "--output-format",
            "stream-json",
            "--approval-mode",
            "auto_edit",
            "--session-id",
            SESSION.toString(),
            "--prompt=-arregla add");
  }

  @Test
  void aMessageResumesTheSessionAndAForkIsRefused() throws Exception {
    ResumeFrom resume = new ResumeFrom("s-1", false, "/w/a", "skynet/a");
    ResumeFrom fork = new ResumeFrom("s-1", true, "/w/b", "skynet/b");

    assertThat(provider.command(start("gemini-2.5-pro", null, resume, null), dir.resolve("a")))
        .containsSubsequence(
            "--model", "gemini-2.5-pro", "--resume", "s-1", "--prompt=-arregla add")
        .doesNotContain("--session-id");
    assertThatThrownBy(() -> provider.command(start(null, null, fork, null), dir.resolve("a")))
        .isInstanceOf(IOException.class)
        .hasMessage("Gemini CLI no permite bifurcar una sesión");
  }

  @Test
  void theOutputSchemaIsAskedForInThePrompt() throws Exception {
    String schema = "{\"type\":\"object\",\"properties\":{\"fixed\":{\"type\":\"boolean\"}}}";

    String prompt = provider.command(start(null, null, null, schema), dir.resolve("a")).getLast();

    assertThat(prompt).startsWith("--prompt=-arregla add\n\n").contains("```json", "\"fixed\"");
  }

  @Test
  void thePermissionModeChoosesTheApprovalMode() {
    assertThat(GeminiProvider.approvalMode("plan")).isEqualTo("plan");
    assertThat(GeminiProvider.approvalMode("bypassPermissions")).isEqualTo("yolo");
    assertThat(GeminiProvider.approvalMode("acceptEdits")).isEqualTo("auto_edit");
    assertThat(GeminiProvider.approvalMode(null)).isEqualTo("auto_edit");
  }

  @Test
  void theAgentInheritsOnlyTheBasicGeminiAndAllowedVariablesAndTrustsItsWorktree() {
    Map<String, String> env =
        provider.environment(
            Map.of(
                "PATH", "/bin",
                "GEMINI_API_KEY", "g",
                "GOOGLE_CLOUD_PROJECT", "p",
                "OPENAI_API_KEY", "no",
                "EXTRA", "si",
                "OTRA", "no"),
            null);

    assertThat(env)
        .containsOnlyKeys(
            "PATH", "GEMINI_API_KEY", "GOOGLE_CLOUD_PROJECT", "EXTRA", "GEMINI_CLI_TRUST_WORKSPACE")
        .containsEntry("GEMINI_CLI_TRUST_WORKSPACE", "true");
  }

  private static StartAgent start(
      String model, String permissionMode, ResumeFrom resume, String schema) {
    return new StartAgent(
        UUID.randomUUID(),
        "TKM-1",
        "/repos/demo",
        "main",
        SESSION,
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
        "gemini");
  }
}
