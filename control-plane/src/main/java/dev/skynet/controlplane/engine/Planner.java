package dev.skynet.controlplane.engine;

import dev.skynet.controlplane.definition.StageDefinition;
import dev.skynet.controlplane.definition.StageDefinition.Dependency;
import dev.skynet.controlplane.definition.WorkflowDefinition;
import dev.skynet.controlplane.workflow.RunSteps.StageState;
import dev.skynet.protocol.StageStatus;
import dev.skynet.protocol.WorkflowRunStatus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Qué toca hacer en una ejecución según su definición y el estado de sus fases. No tiene efectos:
 * {@link RunEvaluator} aplica los pasos.
 *
 * <ul>
 *   <li>Una fase pendiente pasa a lista cuando todas sus dependencias han terminado bien; una
 *       dependencia opcional ({@code plan?}) también vale si se omitió. Si se omitió una que no es
 *       opcional, la fase también se omite.
 *   <li>Una fase lista sin agente se arranca.
 *   <li>Si una fase falla o se cancela, se cancelan las demás (fail-fast) y la ejecución termina
 *       fallida o cancelada cuando todas han terminado.
 *   <li>Si todas terminan bien (u omitidas), la ejecución termina bien.
 * </ul>
 */
final class Planner {

  private Planner() {}

  sealed interface Step permits Ready, Skip, Start, Cancel, Finish {}

  record Ready(String stage) implements Step {}

  record Skip(String stage, String reason) implements Step {}

  record Start(String stage) implements Step {}

  record Cancel(String stage, String reason) implements Step {}

  record Finish(WorkflowRunStatus status, String reason) implements Step {}

  static List<Step> plan(WorkflowDefinition definition, Map<String, StageState> stages) {
    List<Step> steps = new ArrayList<>();
    boolean failed = has(stages, StageStatus.FAILED);
    if (failed || has(stages, StageStatus.CANCELLED)) {
      String reason = failed ? "stage-failed" : "stage-cancelled";
      stages.values().stream()
          .filter(s -> !s.status().isTerminal())
          .forEach(s -> steps.add(new Cancel(s.key(), reason)));
      if (steps.isEmpty()) {
        steps.add(
            new Finish(failed ? WorkflowRunStatus.FAILED : WorkflowRunStatus.CANCELLED, reason));
      }
      return steps;
    }

    // Estados tras aplicar lo que se decide aquí, para que una omisión se propague en la misma
    // pasada.
    Map<String, StageStatus> next = new HashMap<>();
    stages.forEach((key, state) -> next.put(key, state.status()));
    boolean changed = true;
    while (changed) {
      changed = false;
      for (StageDefinition stage : definition.stages()) {
        if (next.get(stage.id()) != StageStatus.PENDING) {
          continue;
        }
        Readiness readiness = readiness(stage, next);
        if (readiness == Readiness.SKIP) {
          next.put(stage.id(), StageStatus.SKIPPED);
          steps.add(new Skip(stage.id(), "dependency-skipped"));
          changed = true;
        } else if (readiness == Readiness.READY) {
          next.put(stage.id(), StageStatus.READY);
          steps.add(new Ready(stage.id()));
          changed = true;
        }
      }
    }
    for (StageDefinition stage : definition.stages()) {
      StageState state = stages.get(stage.id());
      if (state != null && next.get(stage.id()) == StageStatus.READY && !state.hasAgent()) {
        steps.add(new Start(stage.id()));
      }
    }
    if (next.values().stream().allMatch(StageStatus::isTerminal)) {
      steps.add(new Finish(WorkflowRunStatus.SUCCEEDED, "all-stages-finished"));
    }
    return steps;
  }

  private enum Readiness {
    WAIT,
    READY,
    SKIP
  }

  private static Readiness readiness(StageDefinition stage, Map<String, StageStatus> statuses) {
    boolean ready = true;
    for (Dependency dependency : stage.dependsOn()) {
      StageStatus status = statuses.get(dependency.stage());
      if (status == StageStatus.SKIPPED && !dependency.optional()) {
        return Readiness.SKIP;
      }
      boolean done =
          status == StageStatus.SUCCEEDED
              || (status == StageStatus.SKIPPED && dependency.optional());
      ready &= done;
    }
    return ready ? Readiness.READY : Readiness.WAIT;
  }

  private static boolean has(Map<String, StageState> stages, StageStatus status) {
    return stages.values().stream().anyMatch(s -> s.status() == status);
  }
}
