package dev.skynet.controlplane.engine;

import dev.skynet.controlplane.definition.PublishedDefinition;
import dev.skynet.controlplane.definition.WorkflowDefinitions;
import dev.skynet.controlplane.engine.Planner.Cancel;
import dev.skynet.controlplane.engine.Planner.Finish;
import dev.skynet.controlplane.engine.Planner.Ready;
import dev.skynet.controlplane.engine.Planner.Skip;
import dev.skynet.controlplane.engine.Planner.Start;
import dev.skynet.controlplane.engine.Planner.Step;
import dev.skynet.controlplane.workflow.RunSteps;
import dev.skynet.controlplane.workflow.RunSteps.RunState;
import dev.skynet.controlplane.workflow.RunSteps.StartOutcome;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Evalúa una ejecución: bloquea su fila, pide al {@link Planner} los pasos y los aplica hasta que
 * no queda nada que hacer. Con la fila bloqueada, dos evaluaciones de la misma ejecución no se
 * solapan; y como los pasos dependen solo del estado guardado, repetir una evaluación no duplica
 * nada.
 */
@Component
class RunEvaluator {

  /** Tope de pasadas por evaluación; cada pasada aplica al menos un cambio. */
  private static final int MAX_PASSES = 100;

  enum Outcome {
    /** No queda nada que hacer hasta el próximo cambio (o la ejecución ha terminado). */
    DONE,
    /**
     * Una fase espera a que se libere su worktree, o la ejecución estaba ocupada: hay que volver a
     * evaluarla más tarde.
     */
    RETRY_LATER
  }

  private final RunSteps steps;
  private final WorkflowDefinitions definitions;

  RunEvaluator(RunSteps steps, WorkflowDefinitions definitions) {
    this.steps = steps;
    this.definitions = definitions;
  }

  /** Evalúa en su propia transacción (los workers). */
  @Transactional
  public Outcome evaluate(UUID runId) {
    return run(runId, false);
  }

  /**
   * Evalúa dentro de la transacción del cambio, en un punto de guardado: si falla, se deshace solo
   * lo de la evaluación y el trabajo queda para los workers. Si otra transacción tiene la ejecución
   * bloqueada (un worker evaluándola), no la espera y la deja para los workers: esta transacción ya
   * ha registrado eventos, y esperar mientras el worker espera para registrar los suyos sería un
   * interbloqueo.
   */
  @Transactional(propagation = Propagation.NESTED)
  public Outcome evaluateNested(UUID runId) {
    return run(runId, true);
  }

  private Outcome run(UUID runId, boolean skipIfBusy) {
    for (int pass = 0; pass < MAX_PASSES; pass++) {
      Optional<RunState> locked = steps.lock(runId, skipIfBusy);
      if (locked.isEmpty()) {
        return skipIfBusy && steps.exists(runId) ? Outcome.RETRY_LATER : Outcome.DONE;
      }
      if (locked.get().status().isTerminal()) {
        return Outcome.DONE;
      }
      RunState state = locked.get();
      PublishedDefinition published = definitions.published(state.definitionId());
      List<Step> plan = Planner.plan(published.definition(), state.stages());
      boolean progressed = false;
      boolean deferred = false;
      for (Step step : plan) {
        switch (step) {
          case Ready ready -> {
            steps.ready(runId, ready.stage());
            progressed = true;
          }
          case Skip skip -> {
            steps.skip(runId, skip.stage(), skip.reason());
            progressed = true;
          }
          case Start start -> {
            StartOutcome outcome = steps.start(runId, start.stage(), published);
            if (outcome == StartOutcome.DEFERRED) {
              deferred = true;
            } else {
              progressed = true;
            }
          }
          case Cancel cancel -> progressed |= steps.cancel(runId, cancel.stage(), cancel.reason());
          case Finish finish -> {
            steps.finish(runId, finish.status(), finish.reason());
            return Outcome.DONE;
          }
        }
      }
      if (!progressed) {
        return deferred ? Outcome.RETRY_LATER : Outcome.DONE;
      }
    }
    throw new IllegalStateException(
        "La ejecución " + runId + " no se estabiliza tras " + MAX_PASSES + " pasadas");
  }
}
