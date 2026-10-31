package dev.skynet.controlplane.github;

import dev.skynet.controlplane.event.EventDraft;
import dev.skynet.controlplane.event.EventStore;
import dev.skynet.controlplane.github.WebhookQueue.Claimed;
import dev.skynet.controlplane.github.WebhookQueue.Status;
import dev.skynet.controlplane.shared.PayloadRedactor;
import dev.skynet.controlplane.shared.TimeSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Procesa las entregas pendientes: los eventos {@code installation*} actualizan las instalaciones;
 * los demás se publican como {@code github.<evento>.<acción>} en cada repositorio de Skynet
 * enlazado. Una entrega que falla se reintenta con espera creciente hasta {@code maxAttempts}.
 */
@Service
class WebhookProcessor {

  private static final Logger log = LoggerFactory.getLogger(WebhookProcessor.class);

  /** Entregas que procesa cada pasada como mucho, para no quedarse con el hilo del planificador. */
  static final int BATCH = 50;

  static final String AGGREGATE = "repository";

  private final WebhookQueue queue;
  private final GitHubInstallations installations;
  private final EventStore events;
  private final PayloadRedactor redactor;
  private final WebhookProperties properties;
  private final TransactionTemplate transactions;
  private final TimeSource time;

  WebhookProcessor(
      WebhookQueue queue,
      GitHubInstallations installations,
      EventStore events,
      PayloadRedactor redactor,
      WebhookProperties properties,
      TransactionTemplate transactions,
      TimeSource time) {
    this.queue = queue;
    this.installations = installations;
    this.events = events;
    this.redactor = redactor;
    this.properties = properties;
    this.transactions = transactions;
    this.time = time;
  }

  @Scheduled(fixedDelayString = "${skynet.github.webhooks.poll-interval:1s}")
  void poll() {
    for (int i = 0; i < BATCH; i++) {
      Optional<Claimed> claimed = queue.claim(properties.lease());
      if (claimed.isEmpty()) {
        return;
      }
      process(claimed.get());
    }
  }

  @Scheduled(fixedDelayString = "${skynet.github.webhooks.purge-interval:1h}")
  void purge() {
    int purged = queue.purge(time.now().minus(properties.retention()));
    if (purged > 0) {
      log.info("Purgadas {} entregas de webhooks de GitHub", purged);
    }
  }

  private void process(Claimed delivery) {
    try {
      transactions.executeWithoutResult(
          status -> {
            Outcome outcome = handle(delivery);
            queue.finish(
                delivery.deliveryId(), outcome.status(), outcome.reason(), outcome.events());
          });
    } catch (RuntimeException e) {
      String error = e.getClass().getSimpleName() + ": " + e.getMessage();
      if (delivery.attempts() >= properties.maxAttempts()) {
        log.warn(
            "Entrega {} de GitHub fallida tras {} intentos: {}",
            delivery.deliveryId(),
            delivery.attempts(),
            error);
        queue.finish(delivery.deliveryId(), Status.FAILED, error, 0);
      } else {
        Duration delay = properties.retryDelay().multipliedBy(1L << (delivery.attempts() - 1));
        queue.retry(delivery.deliveryId(), error, delay);
      }
    }
  }

  private Outcome handle(Claimed delivery) {
    JsonNode payload = delivery.payload();
    String action = delivery.action() == null ? "" : delivery.action();
    Instant now = time.now();
    switch (delivery.event()) {
      case "installation" -> {
        long id = requiredId(payload.path("installation"));
        switch (action) {
          case "deleted" -> installations.removeInstallation(id);
          case "suspend" -> installations.suspend(id, now);
          case "unsuspend" -> installations.suspend(id, null);
          default -> {
            installations.upsertInstallation(payload.path("installation"), now);
            payload.path("repositories").forEach(r -> installations.addRepository(id, r));
          }
        }
        return Outcome.processed(0);
      }
      case "installation_repositories" -> {
        long id = installations.upsertInstallation(payload.path("installation"), now);
        payload.path("repositories_added").forEach(r -> installations.addRepository(id, r));
        payload
            .path("repositories_removed")
            .forEach(r -> installations.removeRepository(id, requiredId(r)));
        return Outcome.processed(0);
      }
      default -> {
        return publish(delivery, payload);
      }
    }
  }

  private Outcome publish(Claimed delivery, JsonNode payload) {
    JsonNode name = payload.path("repository").path("full_name");
    if (!name.isString()) {
      return Outcome.ignored("La entrega no es de un repositorio");
    }
    List<UUID> repositories = installations.repositoriesNamed(name.asString());
    if (repositories.isEmpty()) {
      return Outcome.ignored("Ningún repositorio de Skynet es " + name.asString());
    }
    String type =
        "github." + delivery.event() + (delivery.action() == null ? "" : "." + delivery.action());
    Map<String, Object> summary =
        WebhookSummary.of(delivery.deliveryId(), delivery.event(), payload, redactor::redact);
    for (UUID repository : repositories) {
      events.append(
          new EventDraft(
              AGGREGATE,
              repository,
              type,
              null,
              summary,
              "github:" + delivery.deliveryId() + ":" + repository,
              delivery.receivedAt()));
    }
    return Outcome.processed(repositories.size());
  }

  private static long requiredId(JsonNode node) {
    JsonNode id = node.path("id");
    if (!id.isIntegralNumber()) {
      throw new IllegalArgumentException(
          "La entrega no trae el id de la instalación o el repositorio");
    }
    return id.asLong();
  }

  private record Outcome(Status status, String reason, int events) {
    static Outcome processed(int events) {
      return new Outcome(Status.PROCESSED, null, events);
    }

    static Outcome ignored(String reason) {
      return new Outcome(Status.IGNORED, reason, 0);
    }
  }
}
