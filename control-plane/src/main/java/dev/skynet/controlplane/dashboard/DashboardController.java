package dev.skynet.controlplane.dashboard;

import dev.skynet.controlplane.runner.RunnerDirectory;
import dev.skynet.controlplane.shared.InvalidRequestException;
import dev.skynet.controlplane.shared.TimeSource;
import dev.skynet.controlplane.workflow.RunMetrics;
import dev.skynet.controlplane.workflow.RunPage;
import dev.skynet.controlplane.workflow.RunService;
import dev.skynet.controlplane.workflow.RunView;
import dev.skynet.protocol.WorkflowRunStatus;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
        runs.list(
            EnumSet.of(WorkflowRunStatus.PENDING, WorkflowRunStatus.RUNNING), null, null, 0, LIMIT);
    Instant since = now.minus(FAILURE_WINDOW);
    List<RunView> failures =
        runs.list(EnumSet.of(WorkflowRunStatus.FAILED), null, null, 0, LIMIT).items().stream()
            .filter(r -> r.finishedAt() != null && r.finishedAt().isAfter(since))
            .toList();
    return new DashboardSummary(
        active.items(), active.total(), failures, runs.unresponsiveAgents(), runners.stale(), now);
  }

  /**
   * Métricas de las ejecuciones de las últimas 24 horas (por horas), 7 o 30 días (por días). Los
   * tramos siguen el calendario de {@code tz} (zona IANA, por defecto UTC), la del navegador.
   */
  @GetMapping("/api/dashboard/metrics")
  RunMetrics metrics(
      @RequestParam(defaultValue = "7d") String period,
      @RequestParam(defaultValue = "UTC") String tz) {
    Duration length =
        switch (period) {
          case "24h" -> Duration.ofHours(24);
          case "7d" -> Duration.ofDays(7);
          case "30d" -> Duration.ofDays(30);
          default ->
              throw new InvalidRequestException(
                  "Periodo no válido: " + period + " (24h, 7d o 30d)");
        };
    ZoneId zone;
    try {
      zone = ZoneId.of(tz);
    } catch (DateTimeException e) {
      throw new InvalidRequestException("Zona horaria no válida: " + tz);
    }
    Instant now = time.now();
    return runs.metrics(now.minus(length), now, period.equals("24h") ? "hour" : "day", zone);
  }
}
