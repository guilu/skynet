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
import org.springframework.context.ApplicationEventPublisher;
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
  private final ApplicationEventPublisher publisher;
  private final List<IssueHandler> issueHandlers;
  private final List<DeploymentHandler> deploymentHandlers;

  WebhookProcessor(
      WebhookQueue queue,
      GitHubInstallations installations,
      EventStore events,
      PayloadRedactor redactor,
      WebhookProperties properties,
      TransactionTemplate transactions,
      TimeSource time,
      ApplicationEventPublisher publisher,
      List<IssueHandler> issueHandlers,
      List<DeploymentHandler> deploymentHandlers) {
    this.queue = queue;
    this.installations = installations;
    this.events = events;
    this.redactor = redactor;
    this.properties = properties;
    this.transactions = transactions;
    this.time = time;
    this.publisher = publisher;
    this.issueHandlers = List.copyOf(issueHandlers);
    this.deploymentHandlers = List.copyOf(deploymentHandlers);
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
    boolean labeled = issueLabeled(delivery, payload, name.asString(), repositories);
    boolean commented = commented(delivery, payload, name.asString(), repositories);
    boolean handled = labeled || commented;
    if (repositories.isEmpty()) {
      return handled
          ? Outcome.processed(0)
          : Outcome.ignored("Ningún repositorio de Skynet es " + name.asString());
    }
    // Antes de registrar los eventos github.*: quien atiende el despliegue bloquea su entorno, y
    // ese bloqueo se toma siempre antes que el de la secuencia de eventos.
    GitHubDeployment deployment = deployment(delivery, payload, name.asString(), repositories);
    if (deployment != null) {
      deploymentHandlers.forEach(handler -> handler.handle(deployment));
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
    if ("pull_request".equals(delivery.event())
        && "closed".equals(delivery.action())
        && payload.path("pull_request").path("number").isIntegralNumber()) {
      JsonNode pr = payload.path("pull_request");
      for (UUID repository : repositories) {
        publisher.publishEvent(
            new GitHubPullRequestClosed(
                repository,
                pr.path("number").asLong(),
                pr.path("merged").asBoolean(false),
                pr.path("merge_commit_sha").asString(null)));
      }
    }
    String sha = checkedSha(delivery.event(), payload);
    if (sha != null) {
      for (UUID repository : repositories) {
        publisher.publishEvent(new GitHubCommitChecked(repository, sha));
      }
    }
    return Outcome.processed(repositories.size());
  }

  /**
   * Pasa una etiqueta nueva en una issue (no en una PR) a quien la atienda (DS-A).
   *
   * @return si alguien la ha atendido
   */
  private boolean issueLabeled(
      Claimed delivery, JsonNode payload, String fullName, List<UUID> repositories) {
    if (!"issues".equals(delivery.event()) || !"labeled".equals(delivery.action())) {
      return false;
    }
    JsonNode issue = payload.path("issue");
    JsonNode label = payload.path("label").path("name");
    if (issue.has("pull_request")
        || !issue.path("number").isIntegralNumber()
        || !label.isString()) {
      return false;
    }
    GitHubIssue labeled =
        new GitHubIssue(
            delivery.deliveryId(),
            fullName,
            repositories,
            label.asString(),
            issue.path("number").asLong(),
            issue.path("title").asString(""),
            issue.path("body").asString(""),
            issue.path("html_url").asString(null),
            issue.path("user").path("login").asString(null),
            payload.path("sender").path("login").asString(null),
            delivery.receivedAt());
    boolean handled = false;
    for (IssueHandler handler : issueHandlers) {
      handled |= handler.handle(labeled);
    }
    return handled;
  }

  /**
   * Pasa un comentario nuevo, o una revisión que pide cambios, a quien lo atienda (DS-B).
   *
   * @return si alguien lo ha atendido
   */
  private boolean commented(
      Claimed delivery, JsonNode payload, String fullName, List<UUID> repositories) {
    GitHubComment comment;
    if ("issue_comment".equals(delivery.event()) && "created".equals(delivery.action())) {
      JsonNode issue = payload.path("issue");
      JsonNode body = payload.path("comment");
      if (!issue.path("number").isIntegralNumber() || !body.path("body").isString()) {
        return false;
      }
      comment =
          new GitHubComment(
              delivery.deliveryId(),
              fullName,
              repositories,
              GitHubComment.Kind.COMMENT,
              issue.path("number").asLong(),
              issue.has("pull_request"),
              body.path("id").isIntegralNumber() ? body.path("id").asLong() : null,
              body.path("body").asString(),
              body.path("user").path("login").asString(null),
              issue.path("user").path("login").asString(null),
              issue.path("title").asString(""),
              issue.path("body").asString(""),
              issue.path("html_url").asString(null),
              delivery.receivedAt());
    } else if ("pull_request_review".equals(delivery.event())
        && "submitted".equals(delivery.action())
        && "changes_requested"
            .equalsIgnoreCase(payload.path("review").path("state").asString(""))) {
      JsonNode pr = payload.path("pull_request");
      JsonNode review = payload.path("review");
      if (!pr.path("number").isIntegralNumber()) {
        return false;
      }
      comment =
          new GitHubComment(
              delivery.deliveryId(),
              fullName,
              repositories,
              GitHubComment.Kind.CHANGES_REQUESTED,
              pr.path("number").asLong(),
              true,
              null,
              review.path("body").asString(""),
              review.path("user").path("login").asString(null),
              pr.path("user").path("login").asString(null),
              pr.path("title").asString(""),
              pr.path("body").asString(""),
              review.path("html_url").asString(pr.path("html_url").asString(null)),
              delivery.receivedAt());
    } else {
      return false;
    }
    boolean handled = false;
    for (IssueHandler handler : issueHandlers) {
      handled |= handler.handleComment(comment);
    }
    return handled;
  }

  /**
   * El despliegue de un webhook {@code deployment.created} o {@code deployment_status.created}
   * (DL-B), o {@code null} si la entrega no es de un despliegue.
   */
  static GitHubDeployment deployment(
      Claimed delivery, JsonNode payload, String fullName, List<UUID> repositories) {
    if (!"created".equals(delivery.action())
        || !("deployment".equals(delivery.event())
            || "deployment_status".equals(delivery.event()))) {
      return null;
    }
    JsonNode d = payload.path("deployment");
    if (!d.path("id").isIntegralNumber() || !d.path("sha").isString()) {
      throw new IllegalArgumentException("La entrega no trae el id o el SHA del despliegue");
    }
    GitHubDeployment.Status status = null;
    if ("deployment_status".equals(delivery.event())) {
      JsonNode s = payload.path("deployment_status");
      if (!s.path("id").isIntegralNumber() || !s.path("state").isString()) {
        throw new IllegalArgumentException("La entrega no trae el id o el estado del despliegue");
      }
      status =
          new GitHubDeployment.Status(
              s.path("id").asLong(),
              s.path("state").asString(),
              s.path("description").asString(null),
              s.path("environment_url").asString(null),
              instant(s.path("created_at"), delivery.receivedAt()));
    }
    return new GitHubDeployment(
        delivery.deliveryId(),
        fullName,
        repositories,
        d.path("id").asLong(),
        d.path("sha").asString(),
        d.path("ref").asString(null),
        d.path("environment").asString(null),
        pullRequest(d.path("payload")),
        d.path("creator").path("login").asString(null),
        instant(d.path("created_at"), delivery.receivedAt()),
        status);
  }

  /** {@code payload.pull_request} del despliegue; GitHub lo guarda como objeto o como texto. */
  private static Integer pullRequest(JsonNode payload) {
    JsonNode number = payload.path("pull_request");
    if (number.isIntegralNumber() && number.asLong() > 0 && number.asLong() <= Integer.MAX_VALUE) {
      return number.asInt();
    }
    if (number.isString() && number.asString().matches("[1-9][0-9]{0,8}")) {
      return Integer.valueOf(number.asString());
    }
    return null;
  }

  private static Instant instant(JsonNode value, Instant fallback) {
    if (!value.isString()) {
      return fallback;
    }
    try {
      return Instant.parse(value.asString());
    } catch (java.time.format.DateTimeParseException e) {
      return fallback;
    }
  }

  /** El commit cuya CI cambia con esta entrega, o {@code null} si no es de CI. */
  static String checkedSha(String event, JsonNode payload) {
    JsonNode sha =
        switch (event) {
          case "check_run" -> payload.path("check_run").path("head_sha");
          case "check_suite" -> payload.path("check_suite").path("head_sha");
          case "status" -> payload.path("sha");
          default -> null;
        };
    return sha != null && sha.isString() ? sha.asString() : null;
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
