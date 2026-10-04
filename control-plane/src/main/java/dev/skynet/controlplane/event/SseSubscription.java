package dev.skynet.controlplane.event;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Un cliente SSE: primero reproduce el histórico desde su último evento y después recibe los nuevos
 * en vivo, sin duplicados ni huecos.
 *
 * <p>Se suscribe al dispatcher <em>antes</em> de leer el histórico. Todo evento confirmado antes de
 * esa lectura aparece en ella, y todo evento posterior llega en vivo; los que llegan por ambos
 * caminos se descartan comparando la secuencia con la del último evento enviado.
 */
final class SseSubscription implements EventListener {

  private static final int REPLAY_BATCH = 500;

  private final SseEmitter emitter;
  private final EventFilter filter;
  private final ReentrantLock lock = new ReentrantLock();
  private long lastSent;
  private volatile boolean closed;

  SseSubscription(SseEmitter emitter, EventFilter filter, long after) {
    this.emitter = emitter;
    this.filter = filter;
    this.lastSent = after;
  }

  void replay(EventRepository events) {
    lock.lock();
    try {
      List<StoredEvent> batch;
      do {
        batch = events.list(lastSent, filter, REPLAY_BATCH);
        for (StoredEvent event : batch) {
          if (!send(event)) {
            return;
          }
        }
      } while (batch.size() == REPLAY_BATCH);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void onEvent(StoredEvent event) {
    if (closed || !filter.matches(event)) {
      return;
    }
    lock.lock();
    try {
      if (event.sequence() > lastSent) {
        send(event);
      }
    } finally {
      lock.unlock();
    }
  }

  void heartbeat() {
    if (closed) {
      return;
    }
    lock.lock();
    try {
      emitter.send(SseEmitter.event().comment("heartbeat"));
    } catch (IOException | IllegalStateException e) {
      close();
    } finally {
      lock.unlock();
    }
  }

  boolean isClosed() {
    return closed;
  }

  void close() {
    closed = true;
  }

  private boolean send(StoredEvent event) {
    if (closed) {
      return false;
    }
    try {
      emitter.send(
          SseEmitter.event()
              .id(Long.toString(event.sequence()))
              .data(event, MediaType.APPLICATION_JSON));
      lastSent = event.sequence();
      return true;
    } catch (IOException | IllegalStateException e) {
      close();
      return false;
    }
  }
}
