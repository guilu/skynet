package dev.skynet.controlplane.engine;

import dev.skynet.controlplane.definition.StageDefinition;
import dev.skynet.controlplane.definition.StageDefinition.Dependency;
import dev.skynet.controlplane.definition.WorkflowDefinition;
import dev.skynet.controlplane.workflow.RunSteps.StageState;
import dev.skynet.protocol.StageStatus;
import dev.skynet.protocol.WorkflowRunStatus;
import java.time.Instant;
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
 *   <li>Si una fase falla y su {@code retry} lo cubre, se programa otro intento (W6): mientras
 *       tanto las demás siguen. Un intento programado no empieza antes de su hora.
 *   <li>Si una fase falla sin reintento o se cancela, se cancelan las demás (fail-fast) y la
 *       ejecución termina fallida o cancelada cuando todas han terminado. Un hijo de una fase
 *       {@code parallel} que falla (sin reintento) o se cancela cuenta igual: se cancelan sus
 *       hermanos y las demás fases.
 *   <li>Si un intento llega a su {@code timeout} (W6-B), se paran sus agentes y la fase falla por
 *       tiempo; mientras no llega, hay que volver a evaluar entonces.
 *   <li>Si todas terminan bien (u omitidas), la ejecución termina bien.
 * </ul>
 */
final class Planner {

  private Planner() {}

  sealed interface Step permits Ready, Skip, Start, Cancel, TimeOut, Retry, Wake, Finish {}

  record Ready(String stage) implements Step {}

  record Skip(String stage, String reason) implements Step {}

  record Start(String stage) implements Step {}

  record Cancel(String stage, String reason) implements Step {}

  /** El intento de la fase ha llegado a su {@code timeout}. */
  record TimeOut(String stage) implements Step {}

  /** Programar el intento siguiente de una fase que ha fallado. */
  record Retry(String stage) implements Step {}

  /**
   * Nada que hacer hasta {@code at}, cuando un reintento puede empezar o una fase llega a su {@code
   * timeout}: hay que volver entonces.
   */
  record Wake(Instant at) implements Step {}

  record Finish(WorkflowRunStatus status, String reason) implements Step {}

  static List<Step> plan(
      WorkflowDefinition definition, Map<String, StageState> stages, Instant now) {
    List<Step> steps = new ArrayList<>();
    boolean failed =
        stages.values().stream()
            .anyMatch(
                s ->
                    (s.status() == StageStatus.FAILED && !s.retries())
                        || s.ending() == StageStatus.FAILED);
    if (failed || has(stages, StageStatus.CANCELLED)) {
      // Un reintento que no ha empezado no se programa: la ejecución acaba igual.
      failed |= has(stages, StageStatus.FAILED);
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

    Instant wake = null;
    for (StageState state : stages.values()) {
      Instant deadline = state.deadline();
      if (deadline == null || state.status().isTerminal()) {
        continue;
      }
      if (deadline.isAfter(now)) {
        wake = wake == null || deadline.isBefore(wake) ? deadline : wake;
      } else {
        steps.add(new TimeOut(state.key()));
      }
    }

    boolean retrying = false;
    for (StageState state : stages.values()) {
      if (state.status() == StageStatus.FAILED && state.retries()) {
        steps.add(new Retry(state.key()));
        retrying = true;
      }
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
        Instant notBefore = stages.get(stage.id()).notBefore();
        if (notBefore != null && notBefore.isAfter(now)) {
          wake = wake == null || notBefore.isBefore(wake) ? notBefore : wake;
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
    if (wake != null) {
      steps.add(new Wake(wake));
    }
    if (!retrying && next.values().stream().allMatch(StageStatus::isTerminal)) {
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

  /** Si alguna fase ha acabado así, o va a acabar así (un hijo de una {@code parallel}). */
  private static boolean has(Map<String, StageState> stages, StageStatus status) {
    return stages.values().stream().anyMatch(s -> s.status() == status || s.ending() == status);
  }
}
