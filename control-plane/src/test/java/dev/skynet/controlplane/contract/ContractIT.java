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
import dev.skynet.controlplane.runner.RunnerView;
import dev.skynet.controlplane.support.IntegrationTest;
import dev.skynet.controlplane.workflow.AgentRunDetail;
import dev.skynet.controlplane.workflow.AgentRunKind;
import dev.skynet.controlplane.workflow.AgentRunView;
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
import dev.skynet.protocol.StageStatus;
import dev.skynet.protocol.WorkflowRunStatus;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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
        Arguments.of("effective-policy", Samples.effectivePolicy()));
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
          worktree());
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
          worktree());
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
          worktree());
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
              StageStatus.SUCCEEDED,
              1,
              at(2),
              at(42),
              List.of(agent)),
          agent);
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
              StageStatus.RUNNING,
              1,
              at(2),
              null,
              List.of(agent)),
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
          currentStage,
          currentAgent,
          new RunTotals(
              agent.inputTokens(),
              agent.outputTokens(),
              agent.cacheReadTokens(),
              agent.cacheCreationTokens(),
              agent.costUsd()),
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
              null),
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
              null),
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
              "./gradlew test"));
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
          null);
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
          null);
    }

    static DashboardSummary dashboard() {
      return new DashboardSummary(
          List.of(runningRun()),
          1,
          List.of(),
          List.of(new UnresponsiveAgent(AGENT, RUN, "TKM-1", at(17))),
          List.of(staleRunner()),
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
          - id: fix
            type: agent
            prompt: Corrige lo que encontró la revisión.
            dependsOn: [review]
          - id: verify
            type: verification
            dependsOn: ["fix?"]
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
              at(58)));
    }

    private Samples() {}
  }
}
