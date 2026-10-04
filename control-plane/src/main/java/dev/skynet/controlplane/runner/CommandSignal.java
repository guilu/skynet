package dev.skynet.controlplane.runner;

import java.time.Duration;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Despierta a los long-poll de órdenes cuando puede haber trabajo nuevo. Usa un contador de
 * generación para no perder avisos que llegan entre la consulta y la espera.
 */
@Component
class CommandSignal {

  private long generation;

  synchronized long generation() {
    return generation;
  }

  /** Espera hasta un aviso posterior a {@code seen} o hasta agotar {@code timeout}. */
  synchronized void await(long seen, Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (generation == seen) {
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) {
        return;
      }
      wait(Math.max(1, remaining / 1_000_000));
    }
  }

  synchronized void wakeUp() {
    generation++;
    notifyAll();
  }

  /** Avisa al confirmar la transacción en curso, o en el acto si no hay ninguna. */
  void wakeUpAfterCommit() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              wakeUp();
            }
          });
    } else {
      wakeUp();
    }
  }
}
