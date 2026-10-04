package dev.skynet.controlplane.workflow;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RunController {

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
