package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.project.ProjectService;
import dev.skynet.controlplane.shared.InvalidRequestException;
import dev.skynet.controlplane.shared.NotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** La cola de ejecuciones de un repositorio (DS-C): su límite, las que ocupan y las que esperan. */
@RestController
class RunQueueController {

  static final int MAX_LIMIT = 50;

  /**
   * @param maxConcurrentRuns el límite que se aplica
   * @param custom si es el del repositorio; si no, el general ({@code defaultLimit})
   * @param running las que ocupan un hueco ahora
   * @param queued las que esperan, de la primera a la última
   */
  record QueueView(
      UUID repositoryId,
      int maxConcurrentRuns,
      boolean custom,
      int defaultLimit,
      int running,
      List<UUID> queued) {}

  /** {@code maxConcurrentRuns} {@code null} vuelve al límite general. */
  record SaveQueue(Integer maxConcurrentRuns) {}

  private final RunQueue queue;
  private final ProjectService projects;
  private final ApplicationEventPublisher publisher;
  private final JdbcClient jdbc;

  RunQueueController(
      RunQueue queue,
      ProjectService projects,
      ApplicationEventPublisher publisher,
      JdbcClient jdbc) {
    this.queue = queue;
    this.projects = projects;
    this.publisher = publisher;
    this.jdbc = jdbc;
  }

  @GetMapping("/api/repositories/{id}/run-queue")
  QueueView get(@PathVariable UUID id) {
    projects.getRepository(id);
    return view(id);
  }

  @PutMapping("/api/repositories/{id}/run-queue")
  @Transactional
  QueueView save(@PathVariable UUID id, @RequestBody SaveQueue request) {
    projects.requireActive(projects.getRepository(id));
    Integer limit = request == null ? null : request.maxConcurrentRuns();
    if (limit != null && (limit < 1 || limit > MAX_LIMIT)) {
      throw new InvalidRequestException(
          "maxConcurrentRuns tiene que estar entre 1 y " + MAX_LIMIT + " (o null)");
    }
    queue.setLimit(id, limit);
    // Con más huecos, las que esperan pueden empezar ya.
    for (UUID queued : queue.queuedRuns(id)) {
      publisher.publishEvent(new WorkflowRunChanged(queued));
    }
    return view(id);
  }

  /**
   * El sitio de una ejecución en la cola de su repositorio.
   *
   * @param position 1 si es la siguiente en entrar; 0 si no está en cola
   */
  record RunPosition(
      UUID workflowRunId, UUID repositoryId, int position, int running, int maxConcurrentRuns) {}

  @GetMapping("/api/workflow-runs/{id}/queue")
  RunPosition position(@PathVariable UUID id) {
    UUID repositoryId =
        jdbc.sql("SELECT repository_id FROM workflow_run WHERE id = ?")
            .param(id)
            .query(UUID.class)
            .optional()
            .orElseThrow(() -> new NotFoundException("Ejecución", id));
    if (repositoryId == null) {
      return new RunPosition(id, null, 0, 0, 0);
    }
    int position = queue.queuedRuns(repositoryId).indexOf(id) + 1;
    return new RunPosition(
        id, repositoryId, position, queue.occupied(repositoryId), queue.limit(repositoryId));
  }

  private QueueView view(UUID id) {
    int limit = queue.limit(id);
    return new QueueView(
        id,
        limit,
        queue.ownLimit(id) != null,
        queue.defaultLimit(),
        queue.occupied(id),
        queue.queuedRuns(id));
  }
}
