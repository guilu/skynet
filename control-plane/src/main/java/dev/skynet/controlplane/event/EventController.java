package dev.skynet.controlplane.event;

import dev.skynet.controlplane.shared.NotFoundException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Histórico paginado de eventos y streaming en tiempo real por SSE. */
@RestController
@RequestMapping("/api/events")
class EventController implements SmartLifecycle {

  private static final int MAX_PAGE = 1000;
  private static final Duration STREAM_TIMEOUT = Duration.ofMinutes(30);

  private final EventRepository events;
  private final EventDispatcher dispatcher;
  private final int queueCapacity;
  private final Set<SseSubscription> subscriptions = new CopyOnWriteArraySet<>();
  private volatile boolean running;

  EventController(
      EventRepository events,
      EventDispatcher dispatcher,
      @Value("${skynet.events.subscriber-queue:1000}") int queueCapacity) {
    this.events = events;
    this.dispatcher = dispatcher;
    this.queueCapacity = queueCapacity;
  }

  /**
   * Historial en orden de secuencia. Con {@code after} avanza desde una secuencia; con {@code
   * before}, los {@code limit} eventos anteriores a ella (para cargar historial hacia atrás).
   */
  @GetMapping
  List<StoredEvent> list(
      @RequestParam(required = false) UUID workflowRunId,
      @RequestParam(required = false) UUID aggregateId,
      @RequestParam(defaultValue = "0") long after,
      @RequestParam(required = false) Long before,
      @RequestParam(defaultValue = "200") int limit) {
    EventFilter filter = new EventFilter(workflowRunId, aggregateId);
    int size = Math.clamp(limit, 1, MAX_PAGE);
    return before != null
        ? events.listBefore(before, filter, size)
        : events.list(after, filter, size);
  }

  /** Un evento concreto, con su payload completo (ya redactado al ingerirlo). */
  @GetMapping("/{sequence}")
  StoredEvent get(@PathVariable long sequence) {
    return events.find(sequence).orElseThrow(() -> new NotFoundException("Evento", sequence));
  }

  /**
   * Stream SSE. Cada mensaje lleva como {@code id} la secuencia del evento; al reconectar, el
   * navegador envía {@code Last-Event-ID} y el stream continúa justo después.
   */
  @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  SseEmitter stream(
      @RequestParam(required = false) UUID workflowRunId,
      @RequestParam(required = false) UUID aggregateId,
      @RequestParam(defaultValue = "0") long after,
      @RequestHeader(name = "Last-Event-ID", required = false) Long lastEventId) {
    SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());
    SseSubscription subscription =
        new SseSubscription(
            emitter,
            new EventFilter(workflowRunId, aggregateId),
            lastEventId != null ? lastEventId : after,
            queueCapacity);
    Runnable cleanup =
        () -> {
          subscription.close();
          dispatcher.unsubscribe(subscription);
          subscriptions.remove(subscription);
        };
    emitter.onCompletion(cleanup);
    emitter.onTimeout(cleanup);
    emitter.onError(e -> cleanup.run());

    dispatcher.subscribe(subscription);
    subscriptions.add(subscription);
    subscription.start(events::list);
    return emitter;
  }

  @Scheduled(fixedDelay = 15_000)
  void heartbeat() {
    for (SseSubscription subscription : subscriptions) {
      subscription.heartbeat();
      if (subscription.isClosed()) {
        dispatcher.unsubscribe(subscription);
        subscriptions.remove(subscription);
      }
    }
  }

  @Override
  public void start() {
    running = true;
  }

  /**
   * Al apagar, cierra los streams antes del apagado ordenado del servidor web (fase mayor, se para
   * antes): si no, este esperaría a que acabaran las peticiones SSE, que no acaban nunca.
   */
  @Override
  public void stop() {
    running = false;
    subscriptions.forEach(SseSubscription::complete);
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public int getPhase() {
    return Integer.MAX_VALUE;
  }
}
