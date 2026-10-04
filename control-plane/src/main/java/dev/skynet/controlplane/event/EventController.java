package dev.skynet.controlplane.event;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Histórico paginado de eventos y streaming en tiempo real por SSE. */
@RestController
@RequestMapping("/api/events")
class EventController {

  private static final int MAX_PAGE = 1000;
  private static final Duration STREAM_TIMEOUT = Duration.ofMinutes(30);

  private final EventRepository events;
  private final EventDispatcher dispatcher;
  private final Set<SseSubscription> subscriptions = new CopyOnWriteArraySet<>();

  EventController(EventRepository events, EventDispatcher dispatcher) {
    this.events = events;
    this.dispatcher = dispatcher;
  }

  @GetMapping
  List<StoredEvent> list(
      @RequestParam(required = false) UUID workflowRunId,
      @RequestParam(required = false) UUID aggregateId,
      @RequestParam(defaultValue = "0") long after,
      @RequestParam(defaultValue = "200") int limit) {
    return events.list(
        after, new EventFilter(workflowRunId, aggregateId), Math.clamp(limit, 1, MAX_PAGE));
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
            lastEventId != null ? lastEventId : after);
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
    subscription.replay(events);
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
}
