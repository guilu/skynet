package dev.skynet.controlplane.event;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
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
 *
 * <p>Cada cliente escribe desde su propio hilo virtual. El dispatcher solo deja el evento en una
 * cola acotada, así que un cliente lento no frena a los demás. Si la cola se llena, se cierra la
 * conexión: el navegador reconecta con {@code Last-Event-ID} y recupera lo que faltaba del
 * histórico.
 */
final class SseSubscription implements EventListener {

  /** Lectura del histórico: eventos posteriores a una secuencia que cumplen el filtro. */
  @FunctionalInterface
  interface History {
    List<StoredEvent> list(long after, EventFilter filter, int limit);
  }

  private static final int REPLAY_BATCH = 500;

  private final SseEmitter emitter;
  private final EventFilter filter;
  private final BlockingQueue<StoredEvent> queue;
  private final ReentrantLock lock = new ReentrantLock();
  private long lastSent;
  private volatile boolean closed;
  private Thread writer;

  SseSubscription(SseEmitter emitter, EventFilter filter, long after, int queueCapacity) {
    this.emitter = emitter;
    this.filter = filter;
    this.lastSent = after;
    this.queue = new ArrayBlockingQueue<>(queueCapacity);
  }

  /** Arranca el hilo que reproduce el histórico y después envía lo que llega a la cola. */
  void start(History history) {
    writer = Thread.ofVirtual().name("sse-subscription").start(() -> run(history));
  }

  private void run(History history) {
    try {
      replay(history);
      while (!closed) {
        StoredEvent event = queue.take();
        if (event.sequence() > lastSent) {
          send(event);
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void replay(History history) {
    List<StoredEvent> batch;
    do {
      batch = history.list(lastSent, filter, REPLAY_BATCH);
      for (StoredEvent event : batch) {
        if (!send(event)) {
          return;
        }
      }
    } while (batch.size() == REPLAY_BATCH);
  }

  @Override
  public void onEvent(StoredEvent event) {
    if (closed || !filter.matches(event)) {
      return;
    }
    if (!queue.offer(event)) {
      // Cliente demasiado lento: se corta y, al reconectar, se pone al día desde el histórico.
      close();
      emitter.complete();
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
    if (writer != null) {
      writer.interrupt();
    }
  }

  private boolean send(StoredEvent event) {
    if (closed) {
      return false;
    }
    lock.lock();
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
    } finally {
      lock.unlock();
    }
  }
}
