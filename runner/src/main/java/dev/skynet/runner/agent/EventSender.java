package dev.skynet.runner.agent;

import dev.skynet.protocol.NormalizedEvent;
import dev.skynet.protocol.runner.EventBatch;
import dev.skynet.protocol.runner.EventBatchResult;
import dev.skynet.runner.journal.Journal;
import dev.skynet.runner.transport.ControlPlaneClient;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Envía al control plane los eventos pendientes del journal, por lotes y en orden. Un evento solo
 * se borra del journal cuando el control plane responde; si la conexión falla, se reintenta. Como
 * la ingestión es idempotente, reenviar un lote es seguro.
 */
public final class EventSender implements AutoCloseable {

  private static final Logger LOG = Logger.getLogger(EventSender.class.getName());
  static final int BATCH = 100;

  private final Journal journal;
  private final ControlPlaneClient client;
  private final Duration idle;
  private final Duration retry;
  private final Object signal = new Object();
  private boolean pending;
  private volatile boolean running = true;
  private final Thread thread;

  public EventSender(Journal journal, ControlPlaneClient client, Duration idle, Duration retry) {
    this.journal = journal;
    this.client = client;
    this.idle = idle;
    this.retry = retry;
    this.thread = Thread.ofPlatform().daemon().name("event-sender").start(this::loop);
  }

  /** Avisa de que hay eventos nuevos. */
  public void wakeUp() {
    synchronized (signal) {
      pending = true;
      signal.notifyAll();
    }
  }

  /** Intenta enviar todo lo pendiente ahora mismo. Devuelve {@code true} si no queda nada. */
  public boolean flush() {
    try {
      while (true) {
        List<NormalizedEvent> batch = journal.pending(BATCH);
        if (batch.isEmpty()) {
          return true;
        }
        send(batch);
      }
    } catch (IOException e) {
      LOG.log(Level.FINE, "No se pudieron enviar eventos", e);
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private void loop() {
    while (running) {
      if (!flush()) {
        sleep(retry);
        continue;
      }
      synchronized (signal) {
        if (!pending && running) {
          try {
            signal.wait(idle.toMillis());
          } catch (InterruptedException e) {
            return;
          }
        }
        pending = false;
      }
    }
  }

  private void send(List<NormalizedEvent> batch) throws IOException, InterruptedException {
    EventBatchResult result = client.events(new EventBatch(batch));
    for (EventBatchResult.Rejected rejected : result.rejected()) {
      LOG.warning("Evento " + rejected.eventId() + " rechazado: " + rejected.reason());
    }
    // Aceptados, duplicados y rechazados: ninguno cambiará de destino al reenviarlo.
    journal.delivered(batch.stream().map(NormalizedEvent::eventId).toList());
  }

  private void sleep(Duration duration) {
    try {
      Thread.sleep(duration);
    } catch (InterruptedException e) {
      running = false;
    }
  }

  @Override
  public void close() {
    running = false;
    thread.interrupt();
  }
}
