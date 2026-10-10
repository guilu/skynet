package dev.skynet.controlplane.engine;

import dev.skynet.controlplane.engine.JobQueue.Job;
import dev.skynet.controlplane.engine.RunEvaluator.Outcome;
import dev.skynet.controlplane.workflow.WorkflowRunChanged;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * El motor: cada cambio en una ejecución deja un trabajo en {@code workflow_job} y se evalúa en la
 * misma transacción, para que el primer agente quede en cola en cuanto se lanza. Lo que no termina
 * ahí (falla, o una fase espera su worktree) lo recogen los workers: un grupo fijo de hilos que
 * reclama trabajos vencidos con un alquiler. Al arrancar se encolan todas las ejecuciones sin
 * terminar, por si el control plane cayó a medias.
 */
@Component
class JobEngine implements WorkflowEngine {

  private static final Logger log = LoggerFactory.getLogger(JobEngine.class);

  private final JobQueue jobs;
  private final RunEvaluator evaluator;
  private final EngineProperties properties;
  private final ExecutorService workers;
  private final Semaphore idle;

  JobEngine(JobQueue jobs, RunEvaluator evaluator, EngineProperties properties) {
    this.jobs = jobs;
    this.evaluator = evaluator;
    this.properties = properties;
    this.workers =
        Executors.newFixedThreadPool(
            properties.workers(), Thread.ofPlatform().name("engine-worker-", 0).factory());
    this.idle = new Semaphore(properties.workers());
  }

  @EventListener
  void on(WorkflowRunChanged changed) {
    runChanged(changed.workflowRunId());
  }

  @Override
  public void runChanged(UUID workflowRunId) {
    long generation = jobs.enqueue(workflowRunId);
    try {
      if (evaluator.evaluateNested(workflowRunId) == Outcome.DONE) {
        jobs.complete(workflowRunId, generation);
      }
    } catch (RuntimeException e) {
      log.warn("No se pudo evaluar la ejecución {}; la retoma un worker", workflowRunId, e);
    }
  }

  @EventListener(ApplicationReadyEvent.class)
  void reconcile() {
    int pending = jobs.reconcile();
    if (pending > 0) {
      log.info("{} ejecuciones sin terminar pendientes de evaluar", pending);
    }
  }

  /** Reparte entre los workers libres los trabajos vencidos. */
  @Scheduled(fixedDelayString = "${skynet.engine.poll-interval:1s}")
  void poll() {
    int free = idle.availablePermits();
    if (free == 0 || workers.isShutdown()) {
      return;
    }
    List<Job> claimed = jobs.claim(free, properties.lease());
    for (Job job : claimed) {
      idle.acquireUninterruptibly();
      try {
        workers.execute(
            () -> {
              try {
                process(job);
              } finally {
                idle.release();
              }
            });
      } catch (RuntimeException e) {
        idle.release();
        throw e;
      }
    }
  }

  /** Evalúa un trabajo reclamado; lo termina, o lo deja para más tarde si no ha podido. */
  void process(Job job) {
    try {
      if (evaluator.evaluate(job.runId()) == Outcome.DONE) {
        jobs.complete(job.runId(), job.generation());
      } else {
        jobs.retryLater(job.runId(), job.generation(), properties.retryDelay(), null);
      }
    } catch (RuntimeException e) {
      log.warn(
          "Falló la evaluación de la ejecución {} (intento {})",
          job.runId(),
          job.attempts() + 1,
          e);
      jobs.retryLater(job.runId(), job.generation(), properties.retryDelay(), e.toString());
    }
  }

  @PreDestroy
  void stop() throws InterruptedException {
    workers.shutdown();
    if (!workers.awaitTermination(10, TimeUnit.SECONDS)) {
      workers.shutdownNow();
    }
  }
}
