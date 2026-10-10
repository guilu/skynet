package dev.skynet.controlplane.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skynet.controlplane.definition.DefinitionParser;
import dev.skynet.controlplane.definition.DefinitionParser.Expectations;
import dev.skynet.controlplane.definition.WorkflowDefinition;
import dev.skynet.controlplane.engine.Planner.Cancel;
import dev.skynet.controlplane.engine.Planner.Finish;
import dev.skynet.controlplane.engine.Planner.Ready;
import dev.skynet.controlplane.engine.Planner.Skip;
import dev.skynet.controlplane.engine.Planner.Start;
import dev.skynet.controlplane.workflow.RunSteps.StageState;
import dev.skynet.protocol.StageStatus;
import dev.skynet.protocol.WorkflowRunStatus;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Qué decide el motor según el estado de las fases. */
class PlannerTest {

  /**
   * {@code plan} y {@code tests} en paralelo; {@code fix} depende de los dos, {@code plan}
   * opcional.
   */
  static final WorkflowDefinition DAG =
      DefinitionParser.parse(
              """
              id: dag
              version: 1
              stages:
                - id: plan
                  type: agent
                  prompt: planifica
                - id: tests
                  type: agent
                  prompt: prueba
                - id: fix
                  type: agent
                  prompt: arregla
                  dependsOn: ["plan?", tests]
                - id: review
                  type: agent
                  prompt: revisa
                  dependsOn: [fix]
              """,
              Expectations.NONE)
          .definition();

  @Test
  void stagesWithoutDependenciesStartTogether() {
    var plan = Planner.plan(DAG, states("PENDING", "PENDING", "PENDING", "PENDING"));

    assertThat(plan)
        .containsExactly(
            new Ready("plan"), new Ready("tests"), new Start("plan"), new Start("tests"));
  }

  @Test
  void aStageWaitsForAllItsDependencies() {
    var plan = Planner.plan(DAG, states("SUCCEEDED", "RUNNING", "PENDING", "PENDING"));

    assertThat(plan).isEmpty();
  }

  @Test
  void aSkippedOptionalDependencyCountsAsDone() {
    var plan = Planner.plan(DAG, states("SKIPPED", "SUCCEEDED", "PENDING", "PENDING"));

    assertThat(plan).containsExactly(new Ready("fix"), new Start("fix"));
  }

  @Test
  void aSkippedRequiredDependencySkipsItsDependentsInTheSamePass() {
    var plan = Planner.plan(DAG, states("SUCCEEDED", "SKIPPED", "PENDING", "PENDING"));

    assertThat(plan)
        .containsExactly(
            new Skip("fix", "dependency-skipped"),
            new Skip("review", "dependency-skipped"),
            new Finish(WorkflowRunStatus.SUCCEEDED, "all-stages-finished"));
  }

  @Test
  void aReadyStageWithItsAgentIsNotStartedAgain() {
    Map<String, StageState> stages = states("RUNNING", "READY", "PENDING", "PENDING");
    stages.put("tests", new StageState("tests", StageStatus.READY, true));

    assertThat(Planner.plan(DAG, stages)).isEmpty();
  }

  @Test
  void aFailedStageCancelsTheOthersAndThenFailsTheRun() {
    assertThat(Planner.plan(DAG, states("FAILED", "RUNNING", "PENDING", "PENDING")))
        .containsExactly(
            new Cancel("tests", "stage-failed"),
            new Cancel("fix", "stage-failed"),
            new Cancel("review", "stage-failed"));

    assertThat(Planner.plan(DAG, states("FAILED", "CANCELLED", "CANCELLED", "CANCELLED")))
        .containsExactly(new Finish(WorkflowRunStatus.FAILED, "stage-failed"));
  }

  @Test
  void aCancelledStageCancelsTheRun() {
    assertThat(Planner.plan(DAG, states("CANCELLED", "SUCCEEDED", "CANCELLED", "CANCELLED")))
        .containsExactly(new Finish(WorkflowRunStatus.CANCELLED, "stage-cancelled"));
  }

  @Test
  void theRunSucceedsWhenEveryStageHasFinished() {
    assertThat(Planner.plan(DAG, states("SUCCEEDED", "SUCCEEDED", "SUCCEEDED", "SUCCEEDED")))
        .containsExactly(new Finish(WorkflowRunStatus.SUCCEEDED, "all-stages-finished"));
  }

  /** Estados de plan, tests, fix y review; las fases que ya pasaron de READY tienen agente. */
  private static Map<String, StageState> states(String... statuses) {
    String[] keys = {"plan", "tests", "fix", "review"};
    Map<String, StageState> stages = new LinkedHashMap<>();
    for (int i = 0; i < keys.length; i++) {
      StageStatus status = StageStatus.valueOf(statuses[i]);
      boolean hasAgent =
          status != StageStatus.PENDING
              && status != StageStatus.READY
              && status != StageStatus.SKIPPED;
      stages.put(keys[i], new StageState(keys[i], status, hasAgent));
    }
    return stages;
  }
}
