package dev.skynet.controlplane.dashboard;

import dev.skynet.controlplane.runner.RunnerDirectory;
import dev.skynet.controlplane.shared.TimeSource;
import dev.skynet.controlplane.workflow.RunPage;
import dev.skynet.controlplane.workflow.RunService;
import dev.skynet.controlplane.workflow.RunView;
import dev.skynet.protocol.WorkflowRunStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class DashboardController {

  private static final int LIMIT = 20;
  private static final Duration FAILURE_WINDOW = Duration.ofHours(24);

  private final RunService runs;
  private final RunnerDirectory runners;
  private final TimeSource time;

  DashboardController(RunService runs, RunnerDirectory runners, TimeSource time) {
    this.runs = runs;
    this.runners = runners;
    this.time = time;
  }

  @GetMapping("/api/dashboard")
  DashboardSummary summary() {
    Instant now = time.now();
    RunPage active =
        runs.list(EnumSet.of(WorkflowRunStatus.PENDING, WorkflowRunStatus.RUNNING), null, 0, LIMIT);
    Instant since = now.minus(FAILURE_WINDOW);
    List<RunView> failures =
        runs.list(EnumSet.of(WorkflowRunStatus.FAILED), null, 0, LIMIT).items().stream()
            .filter(r -> r.finishedAt() != null && r.finishedAt().isAfter(since))
            .toList();
    return new DashboardSummary(
        active.items(), active.total(), failures, runs.unresponsiveAgents(), runners.stale(), now);
  }
}
