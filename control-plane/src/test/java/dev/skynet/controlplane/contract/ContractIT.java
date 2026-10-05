package dev.skynet.controlplane.contract;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skynet.controlplane.dashboard.DashboardSummary;
import dev.skynet.controlplane.event.StoredEvent;
import dev.skynet.controlplane.runner.RunnerView;
import dev.skynet.controlplane.support.IntegrationTest;
import dev.skynet.controlplane.workflow.AgentRunDetail;
import dev.skynet.controlplane.workflow.AgentRunKind;
import dev.skynet.controlplane.workflow.AgentRunView;
import dev.skynet.controlplane.workflow.PromptView;
import dev.skynet.controlplane.workflow.RunPage;
import dev.skynet.controlplane.workflow.RunTotals;
import dev.skynet.controlplane.workflow.RunView;
import dev.skynet.controlplane.workflow.StageRunView;
import dev.skynet.controlplane.workflow.UnresponsiveAgent;
import dev.skynet.controlplane.workflow.WorkflowDefinitionView;
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
        Arguments.of("stored-event", Samples.toolStartedEvent()),
        Arguments.of("workflow-definitions", List.of(Samples.adhocDefinition())));
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
          null);
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
          "Read");
    }

    static RunView completedRun() {
      AgentRunView agent = completedAgent();
      return run(
          WorkflowRunStatus.SUCCEEDED,
          at(42),
          null,
          null,
          new StageRunView(STAGE, "agent", StageStatus.SUCCEEDED, 1, at(2), at(42), List.of(agent)),
          agent);
    }

    static RunView runningRun() {
      AgentRunView agent = workingAgent();
      return run(
          WorkflowRunStatus.RUNNING,
          null,
          STAGE,
          AGENT,
          new StageRunView(STAGE, "agent", StageStatus.RUNNING, 1, at(2), null, List.of(agent)),
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
          currentStage,
          currentAgent,
          new RunTotals(
              agent.inputTokens(),
              agent.outputTokens(),
              agent.cacheReadTokens(),
              agent.cacheCreationTokens(),
              agent.costUsd()),
          List.of(stage));
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
          RunnerView.Status.ONLINE);
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
          RunnerView.Status.STALE);
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
          "id: adhoc\nversion: 1\nstages:\n  - id: agent\n    type: agent\n",
          T0);
    }

    private Samples() {}
  }
}
