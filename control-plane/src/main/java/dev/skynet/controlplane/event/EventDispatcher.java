package dev.skynet.controlplane.event;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Difunde a los suscriptores los eventos confirmados, en orden de secuencia.
 *
 * <p>La tabla {@code event} hace de outbox: un único hilo la recorre a partir de un cursor. Se
 * despierta tras cada commit que registra eventos y, como red de seguridad, cada medio segundo.
 */
@Component
class EventDispatcher implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(EventDispatcher.class);
  private static final int BATCH = 500;

  private final EventRepository events;
  private final Set<EventListener> listeners = new CopyOnWriteArraySet<>();
  private final Semaphore signal = new Semaphore(0);
  private volatile boolean running;
  private Thread worker;
  private long cursor;

  EventDispatcher(EventRepository events) {
    this.events = events;
  }

  void subscribe(EventListener listener) {
    listeners.add(listener);
  }

  void unsubscribe(EventListener listener) {
    listeners.remove(listener);
  }

  void wakeUp() {
    signal.release();
  }

  @Override
  public void start() {
    cursor = events.lastSequence();
    running = true;
    worker = Thread.ofPlatform().name("event-dispatcher").daemon().start(this::loop);
  }

  @Override
  public void stop() {
    running = false;
    if (worker != null) {
      worker.interrupt();
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  private void loop() {
    while (running) {
      try {
        signal.tryAcquire(500, TimeUnit.MILLISECONDS);
        signal.drainPermits();
        dispatchPending();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (RuntimeException e) {
        log.warn("Error difundiendo eventos; se reintenta", e);
      }
    }
  }

  private void dispatchPending() {
    List<StoredEvent> batch;
    do {
      batch = events.list(cursor, EventFilter.ALL, BATCH);
      for (StoredEvent event : batch) {
        for (EventListener listener : listeners) {
          try {
            listener.onEvent(event);
          } catch (RuntimeException e) {
            log.warn("Un suscriptor falló procesando el evento {}", event.sequence(), e);
          }
        }
        cursor = event.sequence();
      }
    } while (batch.size() == BATCH);
  }
}
