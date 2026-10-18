package dev.skynet.controlplane.contract;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skynet.controlplane.artifact.ArtifactSummary;
import dev.skynet.controlplane.dashboard.DashboardSummary;
import dev.skynet.controlplane.definition.DefinitionDetail;
import dev.skynet.controlplane.definition.DefinitionParser;
import dev.skynet.controlplane.definition.DefinitionStatus;
import dev.skynet.controlplane.definition.StageType;
import dev.skynet.controlplane.definition.VersionView;
import dev.skynet.controlplane.definition.WorkflowSummary;
import dev.skynet.controlplane.definition.WorkflowView;
import dev.skynet.controlplane.event.StoredEvent;
import dev.skynet.controlplane.runner.ProviderShortage;
import dev.skynet.controlplane.runner.ProviderView;
import dev.skynet.controlplane.runner.RunnerView;
import dev.skynet.controlplane.support.IntegrationTest;
import dev.skynet.controlplane.usage.AgentUsage;
import dev.skynet.controlplane.usage.QuotaShare;
import dev.skynet.controlplane.usage.QuotaWarning;
import dev.skynet.controlplane.usage.RunUsage;
import dev.skynet.controlplane.usage.UsageHistory;
import dev.skynet.controlplane.usage.UsageWindow;
import dev.skynet.controlplane.usage.UsageWindows;
import dev.skynet.controlplane.workflow.AgentRunDetail;
import dev.skynet.controlplane.workflow.AgentRunKind;
import dev.skynet.controlplane.workflow.AgentRunView;
import dev.skynet.controlplane.workflow.ApprovalSummary;
import dev.skynet.controlplane.workflow.ApprovalView;
import dev.skynet.controlplane.workflow.ConversationView;
import dev.skynet.controlplane.workflow.PromptView;
import dev.skynet.controlplane.workflow.RunMetrics;
import dev.skynet.controlplane.workflow.RunPage;
import dev.skynet.controlplane.workflow.RunTotals;
import dev.skynet.controlplane.workflow.RunView;
import dev.skynet.controlplane.workflow.StagePolicy;
import dev.skynet.controlplane.workflow.StageRunView;
import dev.skynet.controlplane.workflow.UnresponsiveAgent;
import dev.skynet.controlplane.workflow.VerificationResult;
import dev.skynet.controlplane.workflow.WorkflowDefinitionView;
import dev.skynet.controlplane.workflow.WorkspaceView;
import dev.skynet.protocol.AgentObservableStatus;
import dev.skynet.protocol.AgentProviderKind.Capability;
import dev.skynet.protocol.StageStatus;
import dev.skynet.protocol.WorkflowRunStatus;
import dev.skynet.protocol.runner.ProviderInfo;
import dev.skynet.protocol.runner.VerificationCheck;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

/**
 * Contrato entre el backend y la web: cada vista de lectura se serializa con el {@code
 * ObjectMapper} de la aplicación y se compara con su JSON de referencia en {@code
 * fixtures/contracts/}. Los tests de la web cargan esos mismos ficheros, así que un cambio de
 * contrato rompe los dos lados.
 *
 * <p>Para regenerar los ficheros tras un cambio intencionado: {@code ./gradlew :control-plane:test
 * --tests '*ContractIT' -Dskynet.contracts.update=true}.
 */
class ContractIT extends IntegrationTest {

  private static final Path DIR = Path.of(System.getProperty("skynet.contracts"));
  private static final boolean UPDATE = Boolean.getBoolean("skynet.contracts.update");

  @Autowired ObjectMapper json;

  static List<Arguments> contracts() {
    return List.of(
        Arguments.of("run-view", Samples.completedRun()),
        Arguments.of("run-page", new RunPage(List.of(Samples.runningRun()), 0, 50, 1)),
        Arguments.of("agent-run-detail", Samples.agentDetail()),
        Arguments.of("runners", List.of(Samples.onlineRunner(), Samples.staleRunner())),
        Arguments.of("dashboard-summary", Samples.dashboard()),
        Arguments.of("dashboard-metrics", Samples.metrics()),
        Arguments.of("stored-event", Samples.toolStartedEvent()),
        Arguments.of("workflow-definitions", List.of(Samples.adhocDefinition())),
        Arguments.of("workflows", List.of(Samples.reviewWorkflow())),
        Arguments.of(
            "workflow",
            new WorkflowView(
                Samples.reviewWorkflow(), List.of(Samples.reviewDraft(), Samples.reviewV1()))),
        Arguments.of("workflow-version", Samples.reviewDraftDetail()),
        Arguments.of("conversation", Samples.conversation()),
        Arguments.of("artifacts", Samples.artifacts()),
        Arguments.of("verifications", Samples.verifications()),
        Arguments.of("effective-policy", Samples.effectivePolicy()),
        Arguments.of("providers", Samples.providers()),
        Arguments.of("approvals", List.of(Samples.pendingApproval())),
        Arguments.of("approval", Samples.approval()),
        Arguments.of("usage-windows", Samples.usageWindows()),
        Arguments.of("usage-history", Samples.usageHistory()),
        Arguments.of("usage-agents", List.of(Samples.agentUsage())),
        Arguments.of("usage-runs", List.of(Samples.runUsage())));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("contracts")
  void matchesTheReferenceJson(String name, Object view) throws IOException {
    Path file = DIR.resolve(name + ".json");
    String actual = json.writer(SerializationFeature.INDENT_OUTPUT).writeValueAsString(view) + "\n";
    if (UPDATE || !Files.exists(file)) {
      Files.createDirectories(DIR);
      Files.writeString(file, actual);
    }
    JsonNode expected = json.readTree(Files.readString(file));
    assertThat(json.readTree(actual))
        .as("%s difiere de %s; regenera los contratos si el cambio es intencionado", name, file)
        .isEqualTo(expected);
  }

  /** Vistas de ejemplo con valores fijos y realistas. */
  static final class Samples {

    static final UUID PROJECT = UUID.fromString("0b6a3c1e-5f0e-4a8e-9a43-1c2d3e4f5a01");
    static final UUID WORK_ITEM = UUID.fromString("0b6a3c1e-5f0e-4a8e-9a43-1c2d3e4f5a02");
    static final UUID REPOSITORY = UUID.fromString("0b6a3c1e-5f0e-4a8e-9a43-1c2d3e4f5a03");
    static final UUID RUN = UUID.fromString("0b6a3c1e-5f0e-4a8e-9a43-1c2d3e4f5a04");
    static final UUID STAGE = UUID.fromString("0b6a3c1e-5f0e-4a8e-9a43-1c2d3e4f5a05");
    static final UUID AGENT = UUID.fromString("0b6a3c1e-5f0e-4a8e-9a43-1c2d3e4f5a06");
    static final UUID RUNNER = UUID.fromString("0b6a3c1e-5f0e-4a8e-9a43-1c2d3e4f5a07");
    static final UUID STALE_RUNNER = UUID.fromString("0b6a3c1e-5f0e-4a8e-9a43-1c2d3e4f5a08");
    static final UUID PROMPT = UUID.fromString("0b6a3c1e-5f0e-4a8e-9a43-1c2d3e4f5a09");
    static final UUID EVENT = UUID.fromString("0b6a3c1e-5f0e-4a8e-9a43-1c2d3e4f5a0a");
    static final UUID ADHOC = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID WORKSPACE = UUID.fromString("0b6a3c1e-5f0e-4a8e-9a43-1c2d3e4f5a0b");
    static final UUID RESUMED_RUN = UUID.fromString("0b6a3c1e-5f0e-4a8e-9a43-1c2d3e4f5a0c");
    static final UUID RESUMED_STAGE = UUID.fromString("0b6a3c1e-5f0e-4a8e-9a43-1c2d3e4f5a0d");
    static final UUID RESUMED_AGENT = UUID.fromString("0b6a3c1e-5f0e-4a8e-9a43-1c2d3e4f5a0e");
    static final UUID APPROVAL = UUID.fromString("0b6a3c1e-5f0e-4a8e-9a43-1c2d3e4f5a0f");
    static final UUID GATE = UUID.fromString("0b6a3c1e-5f0e-4a8e-9a43-1c2d3e4f5a10");
    static final UUID DECISION = UUID.fromString("0b6a3c1e-5f0e-4a8e-9a43-1c2d3e4f5a11");
    static final UUID CHANGES = UUID.fromString("0b6a3c1e-5f0e-4a8e-9a43-1c2d3e4f5a12");
    static final String SESSION = "e1e75e9c-2b3a-4287-ae55-186ae1babc98";

    static final Instant T0 = Instant.parse("2026-10-05T09:00:00Z");

    static Instant at(int seconds) {
      return T0.plusSeconds(seconds);
    }

    static AgentRunView completedAgent() {
      return new AgentRunView(
          AGENT,
          STAGE,
          null,
          REPOSITORY,
          AgentRunKind.START,
          AgentObservableStatus.COMPLETED,
          "claude-code",
          SESSION,
          "claude-opus-5-5",
          at(0),
          at(2),
          at(41),
          at(42),
          0,
          4,
          8L,
          1260L,
          48_520L,
          4_830L,
          new BigDecimal("0.0574210000"),
          new BigDecimal("0.0574210000"),
          RUNNER,
          null,
          "success",
          null,
          "agent.process.exited",
          null,
          worktree(),
          null,
          null,
          null,
          List.of(
              new QuotaShare("claude-code", "default", "five_hour", 0.02, "measured"),
              new QuotaShare("claude-code", "default", "seven_day", 0.004, "measured")));
    }

    static AgentRunView workingAgent() {
      return new AgentRunView(
          AGENT,
          STAGE,
          null,
          REPOSITORY,
          AgentRunKind.START,
          AgentObservableStatus.EXECUTING,
          "claude-code",
          SESSION,
          "claude-opus-5-5",
          at(0),
          at(2),
          at(17),
          null,
          null,
          null,
          4L,
          32L,
          28_437L,
          4_256L,
          null,
          null,
          RUNNER,
          null,
          null,
          null,
          "agent.tool.started",
          "Read",
          worktree(),
          null,
          null,
          null,
          List.of());
    }

    static WorkspaceView worktree() {
      return new WorkspaceView(
          WORKSPACE,
          RUNNER,
          "/home/dev/.skynet/worktrees/" + RUN + "/" + AGENT,
          "skynet/tkm-1/0b6a3c1e",
          "9f2c1d07a4e3b1c5d6e7f8091a2b3c4d5e6f7a8b",
          null,
          null,
          null);
    }

    /** Reanudación de {@link #completedAgent()}: misma sesión y worktree, en otra ejecución. */
    static AgentRunView resumedAgent() {
      return new AgentRunView(
          RESUMED_AGENT,
          RESUMED_STAGE,
          AGENT,
          REPOSITORY,
          AgentRunKind.RESUME,
          AgentObservableStatus.COMPLETED,
          "claude-code",
          SESSION,
          "claude-opus-5-5",
          at(120),
          at(121),
          at(150),
          at(151),
          0,
          2,
          4L,
          410L,
          52_300L,
          1_120L,
          new BigDecimal("0.0241000000"),
          new BigDecimal("0.0815210000"),
          RUNNER,
          null,
          "success",
          null,
          "agent.process.exited",
          null,
          worktree(),
          null,
          null,
          null,
          List.of());
    }

    static ConversationView conversation() {
      return new ConversationView(
          List.of(
              new ConversationView.Turn(
                  RUN,
                  completedAgent(),
                  "Implementa el importador de precios de modelos",
                  List.of(
                      new ConversationView.Message(
                          1050, at(40), "He añadido `PricingImporter` y sus tests."))),
              new ConversationView.Turn(
                  RESUMED_RUN,
                  resumedAgent(),
                  "Añade también los precios de caché",
                  List.of(
                      new ConversationView.Message(
                          1102, at(149), "Hecho: los precios de caché ya se importan.")))));
    }

    static RunView completedRun() {
      AgentRunView agent = completedAgent();
      return run(
          WorkflowRunStatus.SUCCEEDED,
          at(42),
          null,
          null,
          new StageRunView(
              STAGE,
              "agent",
              null,
              null,
              List.of(),
              "agent",
              null,
              null,
              List.of(
                  VerificationCheck.withPaths("artifacts", List.of("docs/spec.md")),
                  VerificationCheck.named("diff")),
              List.of("spec.output.summary"),
              StageStatus.SUCCEEDED,
              1,
              at(2),
              at(42),
              List.of(agent),
              List.of(
                  new VerificationResult(
                      STAGE_VERIFICATION,
                      AGENT,
                      "STAGE",
                      "PASSED",
                      null,
                      null,
                      null,
                      null,
                      null,
                      at(40),
                      at(40),
                      at(42),
                      STAGE,
                      List.of(
                          new VerificationResult.CheckResult(
                              "artifacts", "docs/spec.md", true, null),
                          new VerificationResult.CheckResult("diff", null, true, null)))),
              null,
              null,
              null,
              null,
              null,
              null,
              false,
              null,
              List.of(),
              null),
          agent);
    }

    static Map<String, Object> approvalOrigin() {
      Map<String, Object> origin = new LinkedHashMap<>();
      origin.put("project", Map.of("id", PROJECT, "key", "TKM", "name", "Tokenmeter"));
      origin.put(
          "workItem",
          Map.of("id", WORK_ITEM, "key", "TKM-1", "title", "Add model pricing importer"));
      origin.put("repository", Map.of("id", REPOSITORY, "name", "tokenmeter"));
      origin.put("run", Map.of("id", RUN));
      origin.put(
          "workflow",
          Map.of("key", "especificar-planificar-implementar", "version", 1, "name", "SDD"));
      origin.put("stage", Map.of("key", "aprobar-plan", "name", "Aprobar el plan"));
      return origin;
    }

    static ApprovalSummary pendingApproval() {
      return new ApprovalSummary(
          APPROVAL,
          RUN,
          GATE,
          "aprobar-plan",
          1,
          "Aprobar el plan de TKM-1",
          "HIGH",
          "PENDING",
          at(50),
          null,
          null,
          approvalOrigin());
    }

    static ApprovalView approval() {
      Map<String, Object> changes = new LinkedHashMap<>();
      changes.put("branch", "skynet/tkm-1-plan");
      changes.put("baseCommit", "4f2a9c1");
      changes.put("headCommit", "9b1e7d3");
      changes.put("commits", 1);
      changes.put("files", 2);
      changes.put("insertions", 48);
      changes.put("deletions", 3);
      changes.put("uncommittedFiles", 0);
      Map<String, Object> agent = new LinkedHashMap<>();
      agent.put("agentRunId", AGENT);
      agent.put("childKey", null);
      agent.put("status", AgentObservableStatus.COMPLETED);
      agent.put("provider", "claude-code");
      agent.put("costUsd", new BigDecimal("0.4210"));
      agent.put("structuredOutput", Map.of("summary", "Importar los precios desde un CSV"));
      agent.put(
          "checks",
          List.of(
              new VerificationResult.CheckResult("artifacts", "openspec/tasks.md", true, null)));
      agent.put("changes", changes);
      agent.put(
          "artifacts",
          List.of(Map.of("id", CHANGES, "type", "GIT_CHANGES", "name", "changes.json")));
      Map<String, Object> dependency = new LinkedHashMap<>();
      dependency.put("stageKey", "plan");
      dependency.put("name", null);
      dependency.put("type", "agent");
      dependency.put("status", StageStatus.SUCCEEDED);
      dependency.put("agents", List.of(agent));
      Map<String, Object> consequences = new LinkedHashMap<>();
      consequences.put("approve", "Pueden arrancar: implement.");
      consequences.put("reject", "La ejecución falla y se cancelan implement.");
      consequences.put(
          "requestChanges",
          "plan recibe tu comentario y la puerta vuelve a preguntar con lo corregido.");
      Map<String, Object> evidence = new LinkedHashMap<>();
      evidence.put("origin", approvalOrigin());
      evidence.put("revise", Map.of("stageKey", "plan", "agentRunId", AGENT));
      evidence.put("dependencies", List.of(dependency));
      evidence.put("costUsd", new BigDecimal("0.4210"));
      evidence.put("grants", Samples.effectivePolicy().subList(0, 1));
      evidence.put("consequences", consequences);
      evidence.put("changesRequested", null);
      return new ApprovalView(
          APPROVAL,
          RUN,
          GATE,
          "aprobar-plan",
          1,
          "Aprobar el plan de TKM-1",
          "HIGH",
          "PENDING",
          at(50),
          null,
          null,
          evidence,
          List.of(
              new ApprovalView.Decision(
                  DECISION, "COMMENT", "¿Cubre los precios en euros?", "admin", at(60))));
    }

    static RunView runningRun() {
      AgentRunView agent = workingAgent();
      return run(
          WorkflowRunStatus.RUNNING,
          null,
          STAGE,
          AGENT,
          new StageRunView(
              STAGE,
              "agent",
              null,
              null,
              List.of(),
              "agent",
              null,
              null,
              List.of(),
              List.of(),
              StageStatus.RUNNING,
              1,
              at(2),
              null,
              List.of(agent),
              List.of(),
              null,
              null,
              null,
              null,
              null,
              null,
              false,
              null,
              List.of(),
              null),
          agent);
    }

    private static RunView run(
        WorkflowRunStatus status,
        Instant finishedAt,
        UUID currentStage,
        UUID currentAgent,
        StageRunView stage,
        AgentRunView agent) {
      return new RunView(
          RUN,
          WORK_ITEM,
          "TKM-1",
          "Add model pricing importer",
          PROJECT,
          status,
          at(0),
          at(0),
          finishedAt,
          null,
          null,
          currentStage,
          currentAgent,
          new RunTotals(
              agent.inputTokens(),
              agent.outputTokens(),
              agent.cacheReadTokens(),
              agent.cacheCreationTokens(),
              agent.costUsd(),
              QuotaShare.sum(agent.quota())),
          new RunView.WorkflowRef("adhoc", 1, "Agente suelto"),
          List.of(stage));
    }

    static List<StagePolicy> effectivePolicy() {
      return List.of(
          new StagePolicy(
              "review",
              "Revisión",
              StageType.AGENT,
              "reviewer",
              List.of("Read", "Grep"),
              "plan",
              null,
              10,
              new BigDecimal("2.50"),
              null,
              "claude-haiku-4-5",
              null,
              "claude-code",
              List.of()),
          new StagePolicy(
              "second-opinion",
              "Segunda opinión",
              StageType.AGENT,
              "gemini-reviewer",
              List.of("Read", "Grep"),
              "plan",
              null,
              null,
              null,
              null,
              "gemini-2.5-pro",
              null,
              "gemini",
              List.of("allowedTools", "maxTurns")),
          new StagePolicy(
              "fix",
              null,
              StageType.AGENT,
              null,
              List.of("Read", "Edit", "Bash"),
              "dontAsk",
              List.of(),
              null,
              null,
              null,
              null,
              null,
              null,
              List.of()),
          new StagePolicy(
              "tests",
              "Tests",
              StageType.COMMAND,
              null,
              List.of("Read", "Edit", "Bash"),
              "dontAsk",
              List.of(),
              null,
              null,
              null,
              null,
              "./gradlew test",
              null,
              List.of()));
    }

    static AgentRunDetail agentDetail() {
      return new AgentRunDetail(
          completedAgent(),
          RUN,
          List.of(
              new PromptView(
                  PROMPT,
                  "user",
                  "Implementa el importador de precios de modelos",
                  "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
                  at(0))));
    }

    static List<ProviderView> providers() {
      return List.of(
          new ProviderView(
              "claude-code",
              "Claude Code",
              EnumSet.of(
                  Capability.RESUME,
                  Capability.FORK,
                  Capability.STRUCTURED_OUTPUT,
                  Capability.MAX_TURNS),
              2),
          new ProviderView(
              "codex",
              "Codex",
              EnumSet.of(Capability.RESUME, Capability.FORK, Capability.STRUCTURED_OUTPUT),
              1),
          new ProviderView(
              "gemini",
              "Gemini CLI",
              EnumSet.of(Capability.RESUME, Capability.STRUCTURED_OUTPUT),
              0));
    }

    static RunnerView onlineRunner() {
      return new RunnerView(
          RUNNER,
          "runner-01",
          2,
          1,
          "0.1.0",
          "2.1.288",
          at(-3600),
          at(30),
          RunnerView.Status.ONLINE,
          null,
          List.of(new ProviderInfo("claude-code", "2.1.288"), new ProviderInfo("codex", null)));
    }

    static RunnerView staleRunner() {
      return new RunnerView(
          STALE_RUNNER,
          "runner-02",
          1,
          0,
          "0.1.0",
          "2.1.288",
          at(-7200),
          at(-600),
          RunnerView.Status.STALE,
          null,
          List.of(new ProviderInfo("claude-code", "2.1.288")));
    }

    static UsageWindows usageWindows() {
      return new UsageWindows(
          List.of(
              new UsageWindows.Runner(
                  RUNNER,
                  "laptop",
                  List.of(
                      new UsageWindow(
                          "claude-code",
                          "Claude Code",
                          "default",
                          null,
                          "five_hour",
                          0.16,
                          at(9_000),
                          null,
                          "invocation",
                          at(42),
                          false,
                          0.5,
                          0.02),
                      new UsageWindow(
                          "antigravity",
                          "Antigravity CLI",
                          "gemini",
                          "Gemini Models",
                          "seven_day",
                          0.4,
                          at(345_600),
                          null,
                          "poll",
                          at(30),
                          false,
                          0.43,
                          0)))));
    }

    static UsageHistory usageHistory() {
      return new UsageHistory(
          RUNNER,
          "claude-code",
          "default",
          "five_hour",
          at(-9_000),
          at(9_000),
          List.of(
              new UsageHistory.Point(at(-3_600), 0.12, 0, "invocation"),
              new UsageHistory.Point(at(42), 0.16, 0.02, "invocation")));
    }

    static AgentUsage agentUsage() {
      return new AgentUsage(
          "revisar-y-corregir",
          "Revisar y corregir",
          "revisor",
          "claude-code",
          "Claude Code",
          4,
          3,
          0.12,
          0.024,
          0.006,
          0.008,
          420L,
          9_870L);
    }

    static RunUsage runUsage() {
      return new RunUsage(
          RUN,
          "TKM-1",
          "Add model pricing importer",
          "adhoc",
          "Agente suelto",
          "SUCCEEDED",
          at(0),
          List.of(
              new QuotaShare("claude-code", "default", "five_hour", 0.02, "measured"),
              new QuotaShare("claude-code", "default", "seven_day", 0.004, "measured")));
    }

    static DashboardSummary dashboard() {
      return new DashboardSummary(
          List.of(runningRun()),
          1,
          List.of(),
          List.of(new UnresponsiveAgent(AGENT, RUN, "TKM-1", at(17))),
          List.of(staleRunner()),
          List.of(new ProviderShortage("gemini", "Gemini CLI", 2)),
          List.of(
              new QuotaWarning(
                  RUNNER,
                  "laptop",
                  "claude-code",
                  "Claude Code",
                  "default",
                  null,
                  "seven_day",
                  0.78,
                  0.55,
                  at(345_600),
                  "pace")),
          at(600));
    }

    static RunMetrics metrics() {
      return new RunMetrics(
          at(-86_400),
          at(0),
          "hour",
          3,
          1,
          1,
          1,
          0,
          95.5,
          1200L,
          3400L,
          new BigDecimal("0.1234"),
          List.of(
              new RunMetrics.Bucket(at(-3600), 2, 1, 1, 0, new BigDecimal("0.1234")),
              new RunMetrics.Bucket(at(0), 1, 0, 0, 0, null)));
    }

    static StoredEvent toolStartedEvent() {
      ObjectMapper mapper = new ObjectMapper();
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("messageId", "msg_011CffQQpkipdwPCmeefQ9xY");
      payload.put("toolUseId", "toolu_01A");
      payload.put("name", "Read");
      payload.put("input", Map.of("file_path", "/w/README.md"));
      payload.put("runnerSeq", 7);
      return new StoredEvent(
          1042,
          EVENT,
          RUN,
          "agent_run",
          AGENT,
          "agent.tool.started",
          mapper.valueToTree(payload),
          at(17),
          at(17));
    }

    static WorkflowDefinitionView adhocDefinition() {
      return new WorkflowDefinitionView(
          ADHOC,
          "adhoc",
          1,
          "id: adhoc\nversion: 1\nname: Agente suelto\ninputs:\n  prompt:\n    type: text\n"
              + "    required: true\nstages:\n  - id: agent\n    type: agent\n"
              + "    prompt: \"{{inputs.prompt}}\"\n",
          T0);
    }

    static final UUID REVIEW_WORKFLOW = UUID.fromString("0b6a3c1e-5f0e-4a8e-9a43-1c2d3e4f5a12");
    static final UUID REVIEW_V1 = UUID.fromString("0b6a3c1e-5f0e-4a8e-9a43-1c2d3e4f5a13");
    static final UUID REVIEW_V2 = UUID.fromString("0b6a3c1e-5f0e-4a8e-9a43-1c2d3e4f5a14");

    /** Borrador de la v2 de un workflow: con todos los campos y una fase que aún no se ejecuta. */
    static final String REVIEW_YAML =
        """
        id: revisar-y-corregir
        version: 2
        name: Revisar y corregir
        description: Un agente revisa el cambio y otro corrige lo que encuentre.

        inputs:
          foco:
            type: string
            required: false
            default: seguridad
            description: En qué fijarse.

        agents:
          reviewer:
            description: Revisor exigente.
            prompt: |
              Revisa {{workItem.key}} ({{workItem.title}}) poniendo el foco en {{inputs.foco}}.
            tools: [Read, Grep, Glob]
            permissionMode: dontAsk
            model: claude-haiku-4-5
            limits:
              maxTurns: 30
              maxBudgetUsd: 1.5
              timeoutMinutes: 20

        stages:
          - id: review
            type: agent
            agent: reviewer
            outputSchema:
              type: object
              properties:
                summary:
                  type: string
          - id: fix
            type: agent
            prompt: "Corrige lo que encontró la revisión: {{stages.review.output.summary}}"
            dependsOn: [review]
            retry: {maxAttempts: 2}
            promptTemplate: corregir
          - id: verify
            type: verification
            dependsOn: ["fix?"]
            artifacts: [docs/review.md]
            checks: [tests, scope, {command: npm run lint}]
            scope: ["src/**", "docs/**"]
            timeout: 30m
        """;

    static VersionView reviewV1() {
      return new VersionView(REVIEW_V1, 1, DefinitionStatus.PUBLISHED, T0, at(60), at(60));
    }

    static VersionView reviewDraft() {
      return new VersionView(REVIEW_V2, 2, DefinitionStatus.VALIDATED, at(120), at(180), null);
    }

    static WorkflowSummary reviewWorkflow() {
      return new WorkflowSummary(
          REVIEW_WORKFLOW,
          "revisar-y-corregir",
          "Revisar y corregir",
          "Un agente revisa el cambio y otro corrige lo que encuentre.",
          reviewV1(),
          reviewDraft(),
          T0,
          null);
    }

    static DefinitionDetail reviewDraftDetail() {
      DefinitionParser.Parsed parsed =
          DefinitionParser.parse(
              REVIEW_YAML, new DefinitionParser.Expectations("revisar-y-corregir", 2));
      return new DefinitionDetail(
          REVIEW_V2,
          REVIEW_WORKFLOW,
          "revisar-y-corregir",
          2,
          DefinitionStatus.VALIDATED,
          REVIEW_YAML,
          3,
          at(120),
          at(180),
          null,
          null,
          parsed.validation(),
          parsed.definition());
    }

    static final UUID VERIFICATION = UUID.fromString("0b6a3c1e-5f0e-4a8e-9a43-1c2d3e4f5a0f");
    static final UUID DIFF_ARTIFACT = UUID.fromString("0b6a3c1e-5f0e-4a8e-9a43-1c2d3e4f5a10");
    static final UUID REPORT_ARTIFACT = UUID.fromString("0b6a3c1e-5f0e-4a8e-9a43-1c2d3e4f5a11");
    static final UUID STAGE_VERIFICATION = UUID.fromString("0b6a3c1e-5f0e-4a8e-9a43-1c2d3e4f5a20");

    static List<ArtifactSummary> artifacts() {
      Map<String, Object> changes = new LinkedHashMap<>();
      changes.put("branch", "skynet/tkm-1/0b6a3c1e");
      changes.put("baseCommit", "e423d4a4c8acc52a200b70a76f322933eb3d6679");
      changes.put("headCommit", "c3c0c205acb0adea069f8d5ee5d835a24da433b1");
      changes.put("commits", 1);
      changes.put("files", 1);
      changes.put("insertions", 1);
      changes.put("deletions", 1);
      changes.put("uncommittedFiles", 0);
      Map<String, Object> totals = new LinkedHashMap<>();
      totals.put("total", 12);
      totals.put("failed", 1);
      totals.put("errors", 0);
      totals.put("skipped", 2);
      return List.of(
          new ArtifactSummary(
              DIFF_ARTIFACT,
              AGENT,
              null,
              "DIFF",
              "changes.diff",
              "text/x-diff",
              157,
              "35dc711418e60edc7974186bd853c7ab08db843f16e43066417b07a53d8566ba",
              false,
              changes,
              at(43)),
          new ArtifactSummary(
              REPORT_ARTIFACT,
              AGENT,
              VERIFICATION,
              "TEST_REPORT",
              "tests.json",
              "application/json",
              812,
              "a4d62eed966ce4e2563a0776d2d4a4ed3d87a09ecc026f0e478a39ec9b613107",
              false,
              totals,
              at(58)));
    }

    static List<VerificationResult> verifications() {
      return List.of(
          new VerificationResult(
              VERIFICATION,
              AGENT,
              "AUTO",
              "FAILED",
              "./gradlew test",
              1,
              null,
              null,
              new VerificationResult.TestTotals(12, 1, 0, 2),
              at(43),
              at(44),
              at(58),
              null,
              List.of()),
          new VerificationResult(
              STAGE_VERIFICATION,
              AGENT,
              "STAGE",
              "FAILED",
              null,
              null,
              null,
              null,
              null,
              at(43),
              at(44),
              at(50),
              STAGE,
              List.of(
                  new VerificationResult.CheckResult(
                      "artifacts", "docs/spec.md", false, "`docs/spec.md` no existe"),
                  new VerificationResult.CheckResult("diff", null, true, null))));
    }

    private Samples() {}
  }
}
