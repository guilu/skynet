package dev.skynet.runner.provider.codex;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skynet.protocol.runner.AgentLimits;
import dev.skynet.protocol.runner.ResumeFrom;
import dev.skynet.protocol.runner.StartAgent;
import dev.skynet.runner.provider.ModelPrices;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class CodexProviderTest {

  @TempDir Path dir;

  private final CodexProvider provider =
      new CodexProvider("codex", List.of("EXTRA"), ModelPrices.defaults());

  @Test
  void aStartRunsExecJsonInTheWorktreeSandbox() throws Exception {
    List<String> command = provider.command(start(null, "dontAsk", null, null), dir.resolve("a"));

    assertThat(command)
        .containsExactly(
            "codex", "exec", "--json", "--sandbox", "workspace-write", "--", "-arregla add");
  }

  @Test
  void resumeAndForkContinueTheThread() throws Exception {
    ResumeFrom resume = new ResumeFrom("thread-1", false, "/w/a", "skynet/a");
    ResumeFrom fork = new ResumeFrom("thread-1", true, "/w/b", "skynet/b");

    assertThat(provider.command(start(null, null, resume, null), dir.resolve("a")))
        .containsSubsequence("exec", "--json", "resume", "thread-1", "--", "-arregla add");
    assertThat(provider.command(start(null, null, fork, null), dir.resolve("a")))
        .containsSubsequence("exec", "--json", "fork", "thread-1", "--", "-arregla add");
  }

  @Test
  void theModelAndAStrictSchemaGoInTheCommand() throws Exception {
    String schema =
        """
        {"type":"object","properties":{"verdict":{"type":"string"},
         "issues":{"type":"array","items":{"type":"object","properties":{"line":{"type":"integer"}}}}}}
        """;
    List<String> command =
        provider.command(start("gpt-5.5", null, null, schema), dir.resolve("logs/a"));

    Path file = dir.resolve("logs/a.schema.json");
    assertThat(command)
        .containsSubsequence("--model", "gpt-5.5", "--output-schema", file.toString());
    JsonNode written = JsonMapper.builder().build().readTree(Files.readString(file));
    assertThat(written.path("additionalProperties").asBoolean(true)).isFalse();
    assertThat(written.path("required"))
        .extracting(JsonNode::asString)
        .containsExactly("verdict", "issues");
    JsonNode item = written.path("properties").path("issues").path("items");
    assertThat(item.path("additionalProperties").asBoolean(true)).isFalse();
    assertThat(item.path("required")).extracting(JsonNode::asString).containsExactly("line");
  }

  @Test
  void aForkKeepsTheModelAndTheSchemaOfTheAgent() throws Exception {
    ResumeFrom fork = new ResumeFrom("thread-1", true, "/w/b", "skynet/b");
    String schema = "{\"type\":\"object\",\"properties\":{\"fixed\":{\"type\":\"boolean\"}}}";

    List<String> command =
        provider.command(start("gpt-5.5", "acceptEdits", fork, schema), dir.resolve("f"));

    assertThat(command)
        .containsSubsequence(
            "--model",
            "gpt-5.5",
            "--output-schema",
            dir.resolve("f.schema.json").toString(),
            "fork",
            "thread-1");
    assertThat(dir.resolve("f.schema.json")).content().contains("\"required\":[\"fixed\"]");
  }

  @Test
  void thePermissionModeChoosesTheSandbox() {
    assertThat(CodexProvider.sandbox("plan")).containsExactly("--sandbox", "read-only");
    assertThat(CodexProvider.sandbox("bypassPermissions"))
        .containsExactly("--dangerously-bypass-approvals-and-sandbox");
    assertThat(CodexProvider.sandbox("acceptEdits"))
        .containsExactly("--sandbox", "workspace-write");
    assertThat(CodexProvider.sandbox(null)).containsExactly("--sandbox", "workspace-write");
  }

  @Test
  void theAgentInheritsOnlyTheBasicCodexAndAllowedVariables() {
    Map<String, String> env =
        provider.environment(
            Map.of(
                "PATH", "/bin",
                "OPENAI_API_KEY", "sk",
                "CODEX_HOME", "/c",
                "ANTHROPIC_API_KEY", "no",
                "EXTRA", "si",
                "OTRA", "no"),
            null);

    assertThat(env).containsOnlyKeys("PATH", "OPENAI_API_KEY", "CODEX_HOME", "EXTRA");
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
        "codex");
  }
}
