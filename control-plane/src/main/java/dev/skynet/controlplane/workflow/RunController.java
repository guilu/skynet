package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.shared.ArchiveState;
import dev.skynet.controlplane.shared.Archived;
import dev.skynet.protocol.WorkflowRunStatus;
import dev.skynet.protocol.runner.AgentLimits;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RunController {

  private static final int MAX_PAGE = 200;

  private final RunService service;
  private final WorkspaceCleanup cleanup;

  RunController(RunService service, WorkspaceCleanup cleanup) {
    this.service = service;
    this.cleanup = cleanup;
  }

  /**
   * Lanzamiento de un agente. Los límites son opcionales: sin ellos se aplican los de {@code
   * skynet.agent}.
   */
  record LaunchRun(
      @NotNull UUID repositoryId,
      @NotBlank @Size(max = 100_000) String prompt,
      @Positive @Max(1_000) Integer maxTurns,
      @Positive @DecimalMax("1000") BigDecimal maxBudgetUsd,
      @Positive @Max(24 * 60) Integer timeoutMinutes) {

    AgentLimits limits() {
      return new AgentLimits(
          maxTurns,
          maxBudgetUsd,
          timeoutMinutes == null ? null : Duration.ofMinutes(timeoutMinutes));
    }
  }

  @PostMapping("/api/work-items/{workItemId}/runs")
  @ResponseStatus(HttpStatus.CREATED)
  RunView launch(@PathVariable UUID workItemId, @Valid @RequestBody LaunchRun request) {
    return service.run(
        service
            .launch(workItemId, request.repositoryId(), request.prompt(), request.limits())
            .getId());
  }

  @GetMapping("/api/work-items/{workItemId}/runs")
  List<RunView> runsOf(
      @PathVariable UUID workItemId, @RequestParam(defaultValue = "false") String archived) {
    return service.runsOf(workItemId, Archived.of(archived));
  }

  /**
   * Ejecuciones de más reciente a más antigua; {@code status} se puede repetir, {@code since} deja
   * solo las creadas desde ese instante (ISO-8601) y {@code q} busca en la clave y el título del
   * trabajo, sin distinguir mayúsculas.
   */
  @GetMapping("/api/workflow-runs")
  RunPage list(
      @RequestParam(required = false) Set<WorkflowRunStatus> status,
      @RequestParam(required = false) UUID projectId,
      @RequestParam(required = false) Instant since,
      @RequestParam(required = false) @Size(max = 200) String q,
      @RequestParam(defaultValue = "false") String archived,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return service.list(
        status,
        projectId,
        since,
        q,
        Archived.of(archived),
        Math.max(0, page),
        Math.clamp(size, 1, MAX_PAGE));
  }

  @PostMapping("/api/workflow-runs/{id}/archive")
  ArchiveState archive(@PathVariable UUID id) {
    return service.archive(id);
  }

  @PostMapping("/api/workflow-runs/{id}/restore")
  ArchiveState restore(@PathVariable UUID id) {
    return service.restore(id);
  }

  @GetMapping("/api/workflow-definitions")
  List<WorkflowDefinitionView> definitions() {
    return service.definitions();
  }

  @GetMapping("/api/workflow-runs/{id}")
  RunView run(@PathVariable UUID id) {
    return service.run(id);
  }

  @GetMapping("/api/agent-runs/{id}")
  AgentRunDetail agent(@PathVariable UUID id) {
    return service.agent(id);
  }

  @GetMapping("/api/agent-runs/{id}/conversation")
  ConversationView conversation(@PathVariable UUID id) {
    return service.conversation(id);
  }

  /** Mensaje para continuar o bifurcar la sesión de un agente. */
  record SessionMessage(@NotBlank @Size(max = 100_000) String text) {}

  /** Reanuda la sesión con un mensaje; devuelve la ejecución nueva. */
  @PostMapping("/api/agent-runs/{id}/messages")
  @ResponseStatus(HttpStatus.CREATED)
  RunView sendMessage(@PathVariable UUID id, @Valid @RequestBody SessionMessage request) {
    return service.run(service.sendMessage(id, request.text()).getId());
  }

  /** Bifurca la sesión con un mensaje; devuelve la ejecución nueva. */
  @PostMapping("/api/agent-runs/{id}/fork")
  @ResponseStatus(HttpStatus.CREATED)
  RunView fork(@PathVariable UUID id, @Valid @RequestBody SessionMessage request) {
    return service.run(service.fork(id, request.text()).getId());
  }

  /** Repite el lanzamiento con el mismo prompt y límites; devuelve la ejecución nueva. */
  @PostMapping("/api/agent-runs/{id}/retry")
  @ResponseStatus(HttpStatus.CREATED)
  RunView retry(@PathVariable UUID id) {
    return service.run(service.retry(id).getId());
  }

  @PostMapping("/api/agent-runs/{id}/cancel")
  AgentRunDetail cancel(@PathVariable UUID id) {
    service.cancelAgent(id);
    return service.agent(id);
  }

  /** Pide al runner eliminar el worktree del agente; la rama se conserva. */
  @PostMapping("/api/agent-runs/{id}/workspace/cleanup")
  @ResponseStatus(HttpStatus.ACCEPTED)
  AgentRunDetail cleanupWorkspace(@PathVariable UUID id) {
    cleanup.request(id);
    return service.agent(id);
  }
}
