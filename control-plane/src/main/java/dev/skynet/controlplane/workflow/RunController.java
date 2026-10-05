package dev.skynet.controlplane.workflow;

import dev.skynet.protocol.WorkflowRunStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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

  RunController(RunService service) {
    this.service = service;
  }

  record LaunchRun(@NotNull UUID repositoryId, @NotBlank @Size(max = 100_000) String prompt) {}

  @PostMapping("/api/work-items/{workItemId}/runs")
  @ResponseStatus(HttpStatus.CREATED)
  RunView launch(@PathVariable UUID workItemId, @Valid @RequestBody LaunchRun request) {
    return service.run(
        service.launch(workItemId, request.repositoryId(), request.prompt()).getId());
  }

  @GetMapping("/api/work-items/{workItemId}/runs")
  List<RunView> runsOf(@PathVariable UUID workItemId) {
    return service.runsOf(workItemId);
  }

  /** Ejecuciones de más reciente a más antigua; {@code status} se puede repetir. */
  @GetMapping("/api/workflow-runs")
  RunPage list(
      @RequestParam(required = false) Set<WorkflowRunStatus> status,
      @RequestParam(required = false) UUID projectId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return service.list(status, projectId, Math.max(0, page), Math.clamp(size, 1, MAX_PAGE));
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

  @PostMapping("/api/agent-runs/{id}/cancel")
  AgentRunDetail cancel(@PathVariable UUID id) {
    service.cancelAgent(id);
    return service.agent(id);
  }
}
