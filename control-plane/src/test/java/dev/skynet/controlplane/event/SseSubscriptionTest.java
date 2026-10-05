package dev.skynet.controlplane.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.node.JsonNodeFactory;

class SseSubscriptionTest {

  private static final SseSubscription.History NO_HISTORY = (after, filter, limit) -> List.of();

  @Test
  void aBlockedClientDoesNotDelayTheOthers() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    RecordingEmitter blocked = new RecordingEmitter(release);
    RecordingEmitter healthy = new RecordingEmitter(null);
    SseSubscription slow = new SseSubscription(blocked, EventFilter.ALL, 0, 100);
    SseSubscription fast = new SseSubscription(healthy, EventFilter.ALL, 0, 100);
    slow.start(NO_HISTORY);
    fast.start(NO_HISTORY);

    // El dispatcher entrega en su hilo: no debe esperar al cliente bloqueado.
    long start = System.nanoTime();
    for (long seq = 1; seq <= 5; seq++) {
      slow.onEvent(event(seq));
      fast.onEvent(event(seq));
    }
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(500));

    healthy.awaitSent(5);
    assertThat(healthy.sent).hasSize(5);
    assertThat(blocked.sent).isEmpty();

    release.countDown();
    blocked.awaitSent(5);
    assertThat(slow.isClosed()).isFalse();
  }

  @Test
  void aClientThatFallsTooFarBehindIsDisconnectedToResumeFromHistory() throws Exception {
    RecordingEmitter blocked = new RecordingEmitter(new CountDownLatch(1));
    SseSubscription subscription = new SseSubscription(blocked, EventFilter.ALL, 0, 2);
    subscription.start(NO_HISTORY);

    // El primero lo toma el hilo de escritura (y se bloquea); dos más llenan la cola.
    subscription.onEvent(event(1));
    blocked.awaitSendStarted();
    subscription.onEvent(event(2));
    subscription.onEvent(event(3));
    assertThat(blocked.completed).isFalse();

    subscription.onEvent(event(4));
    assertThat(subscription.isClosed()).isTrue();
    assertThat(blocked.completed).isTrue();
  }

  @Test
  void replaysHistoryAndSkipsLiveEventsAlreadySent() throws Exception {
    RecordingEmitter emitter = new RecordingEmitter(null);
    SseSubscription subscription = new SseSubscription(emitter, EventFilter.ALL, 0, 100);
    // Un evento confirmado durante la lectura del histórico llega por ambos caminos.
    subscription.onEvent(event(2));
    subscription.start(
        (after, filter, limit) -> after == 0 ? List.of(event(1), event(2)) : List.of());
    subscription.onEvent(event(3));

    emitter.awaitSent(3);
    assertThat(emitter.sentMoreWithin(Duration.ofMillis(200))).isFalse();
    assertThat(emitter.sent).hasSize(3);
  }

  private static StoredEvent event(long sequence) {
    return new StoredEvent(
        sequence,
        UUID.randomUUID(),
        null,
        "test",
        UUID.randomUUID(),
        "test.event",
        JsonNodeFactory.instance.objectNode(),
        Instant.EPOCH,
        Instant.EPOCH);
  }

  /** Emisor que guarda lo enviado y, si se le da un cerrojo, se bloquea en cada envío. */
  private static final class RecordingEmitter extends SseEmitter {
    final List<Set<DataWithMediaType>> sent = new CopyOnWriteArrayList<>();
    final CountDownLatch sendStarted = new CountDownLatch(1);
    private final Semaphore sends = new Semaphore(0);
    private final CountDownLatch release;
    volatile boolean completed;

    RecordingEmitter(CountDownLatch release) {
      this.release = release;
    }

    @Override
    public void send(SseEventBuilder builder) {
      sendStarted.countDown();
      if (release != null) {
        try {
          release.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(e);
        }
      }
      sent.add(builder.build());
      sends.release();
    }

    @Override
    public void complete() {
      completed = true;
    }

    void awaitSendStarted() throws InterruptedException {
      assertThat(sendStarted.await(5, TimeUnit.SECONDS)).isTrue();
    }

    /** Espera a que se hayan enviado {@code count} eventos más desde la última espera. */
    void awaitSent(int count) throws InterruptedException {
      assertThat(sends.tryAcquire(count, 5, TimeUnit.SECONDS)).isTrue();
    }

    boolean sentMoreWithin(Duration wait) throws InterruptedException {
      return sends.tryAcquire(wait.toMillis(), TimeUnit.MILLISECONDS);
    }
  }
}
