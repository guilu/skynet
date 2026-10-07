package dev.skynet.controlplane.runner;

import dev.skynet.controlplane.artifact.ArtifactService;
import dev.skynet.controlplane.artifact.ArtifactTooLargeException;
import dev.skynet.controlplane.artifact.InvalidArtifactException;
import dev.skynet.controlplane.workflow.AgentEventIngestion;
import dev.skynet.protocol.NormalizedEvent;
import dev.skynet.protocol.runner.ArtifactStored;
import dev.skynet.protocol.runner.ArtifactUpload;
import dev.skynet.protocol.runner.EventBatch;
import dev.skynet.protocol.runner.EventBatchResult;
import dev.skynet.protocol.runner.RunnerCommand;
import dev.skynet.protocol.runner.RunnerHeartbeat;
import dev.skynet.protocol.runner.RunnerRegistered;
import dev.skynet.protocol.runner.RunnerRegistration;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** API del runner. Todo salvo el registro exige {@code Authorization: Bearer <token>}. */
@RestController
@RequestMapping("/api/runner")
class RunnerController {

  private static final Logger log = LoggerFactory.getLogger(RunnerController.class);
  private static final int INGEST_ATTEMPTS = 3;

  private final RunnerRegistry registry;
  private final CommandQueue commands;
  private final CommandSignal signal;
  private final AgentEventIngestion ingestion;
  private final RunnerProperties properties;
  private final ArtifactService artifacts;

  RunnerController(
      RunnerRegistry registry,
      CommandQueue commands,
      CommandSignal signal,
      AgentEventIngestion ingestion,
      RunnerProperties properties,
      ArtifactService artifacts) {
    this.registry = registry;
    this.commands = commands;
    this.signal = signal;
    this.ingestion = ingestion;
    this.properties = properties;
    this.artifacts = artifacts;
  }

  @PostMapping("/register")
  @ResponseStatus(HttpStatus.CREATED)
  RunnerRegistered register(@RequestBody RunnerRegistration registration) {
    return registry.register(registration);
  }

  @PostMapping("/heartbeat")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void heartbeat(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestBody RunnerHeartbeat heartbeat) {
    registry.heartbeat(registry.authenticate(authorization), heartbeat);
  }

  /**
   * Long-poll: devuelve en cuanto haya órdenes, o una lista vacía al agotar la espera ({@code
   * waitSeconds}, como mucho {@code skynet.runner.max-wait}).
   */
  @GetMapping("/commands")
  List<RunnerCommand> commands(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestParam(defaultValue = "30") long waitSeconds)
      throws InterruptedException {
    UUID runnerId = registry.authenticate(authorization);
    Duration wait = Duration.ofSeconds(Math.max(0, waitSeconds));
    if (wait.compareTo(properties.maxWait()) > 0) {
      wait = properties.maxWait();
    }
    long deadline = System.nanoTime() + wait.toNanos();
    while (true) {
      long seen = signal.generation();
      List<RunnerCommand> claimed = claim(runnerId);
      long remaining = deadline - System.nanoTime();
      if (!claimed.isEmpty() || remaining <= 0) {
        return claimed;
      }
      signal.await(seen, Duration.ofNanos(remaining));
    }
  }

  @PostMapping("/commands/{id}/ack")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void ack(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable UUID id) {
    commands.ack(registry.authenticate(authorization), id);
  }

  /** Ingestión por lotes, idempotente: reenviar un lote ya procesado es seguro. */
  @PostMapping("/events")
  EventBatchResult events(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestBody EventBatch batch) {
    UUID runnerId = registry.authenticate(authorization);
    int accepted = 0;
    int duplicates = 0;
    List<EventBatchResult.Rejected> rejected = new ArrayList<>();
    for (NormalizedEvent event : batch.events()) {
      switch (ingest(runnerId, event)) {
        case ACCEPTED -> accepted++;
        case DUPLICATE -> duplicates++;
        case UNKNOWN_AGENT_RUN ->
            rejected.add(new EventBatchResult.Rejected(event.eventId(), "unknown-agent-run"));
        case NOT_ASSIGNED_TO_RUNNER ->
            rejected.add(new EventBatchResult.Rejected(event.eventId(), "not-assigned-to-runner"));
        case UNKNOWN_VERIFICATION_RUN ->
            rejected.add(
                new EventBatchResult.Rejected(event.eventId(), "unknown-verification-run"));
      }
    }
    // Un agente que termina libera capacidad: puede haber arranques esperando.
    signal.wakeUp();
    return new EventBatchResult(accepted, duplicates, rejected);
  }

  /**
   * Subida de un artefacto: el contenido en el cuerpo y los metadatos en {@link
   * ArtifactUpload#HEADER} (JSON en base64url). El cuerpo se lee con el límite de {@code
   * skynet.artifacts.max-upload}. Idempotente: reenviarlo devuelve el ya guardado con {@code
   * duplicate=true}.
   */
  @PostMapping(path = "/artifacts", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
  ArtifactStored artifact(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestHeader(ArtifactUpload.HEADER) String metadata,
      InputStream body)
      throws IOException {
    UUID runnerId = registry.authenticate(authorization);
    return artifacts.store(runnerId, artifacts.metadata(metadata), artifacts.content(body));
  }

  private List<RunnerCommand> claim(UUID runnerId) {
    try {
      return commands.claim(runnerId);
    } catch (OptimisticLockingFailureException e) {
      // Un agente cambió a la vez (p. ej. se canceló): se reintentará en la siguiente vuelta.
      log.debug("Conflicto al reclamar órdenes del runner {}", runnerId, e);
      return List.of();
    }
  }

  private AgentEventIngestion.Outcome ingest(UUID runnerId, NormalizedEvent event) {
    for (int attempt = 1; ; attempt++) {
      try {
        return ingestion.ingest(runnerId, event);
      } catch (OptimisticLockingFailureException e) {
        if (attempt == INGEST_ATTEMPTS) {
          throw e;
        }
      }
    }
  }

  @ExceptionHandler(InvalidArtifactException.class)
  ProblemDetail invalidArtifact(InvalidArtifactException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler(ArtifactTooLargeException.class)
  ProblemDetail artifactTooLarge(ArtifactTooLargeException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE, e.getMessage());
  }

  @ExceptionHandler(UnauthorizedRunnerException.class)
  ProblemDetail unauthorized(UnauthorizedRunnerException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
  }
}
