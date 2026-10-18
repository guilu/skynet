package dev.skynet.controlplane.usage;

import dev.skynet.controlplane.shared.InvalidRequestException;
import dev.skynet.controlplane.shared.NotFoundException;
import dev.skynet.protocol.runner.QuotaSample;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Las cuotas de uso de los CLIs, para la página Uso. */
@RestController
class UsageController {

  private static final int MAX_RUNS = 50;

  private final QuotaSnapshots snapshots;
  private final QuotaRefresh refresh;
  private final UsageReports reports;

  UsageController(QuotaSnapshots snapshots, QuotaRefresh refresh, UsageReports reports) {
    this.snapshots = snapshots;
    this.refresh = refresh;
    this.reports = reports;
  }

  /** La última muestra de cada ventana, de cada runner o solo de {@code runner}. */
  @GetMapping("/api/usage/windows")
  UsageWindows windows(@RequestParam(required = false) UUID runner) {
    return snapshots.latest(runner);
  }

  /**
   * La evolución de la ventana actual de un CLI, para el gráfico. Sin {@code runner}, la del runner
   * que la vio por última vez. 404 si no hay muestras.
   */
  @GetMapping("/api/usage/history")
  UsageHistory history(
      @RequestParam String provider,
      @RequestParam(defaultValue = QuotaSample.DEFAULT_BUCKET) String bucket,
      @RequestParam(defaultValue = QuotaSample.FIVE_HOUR) String window,
      @RequestParam(required = false) UUID runner) {
    if (!QuotaSample.FIVE_HOUR.equals(window) && !QuotaSample.SEVEN_DAY.equals(window)) {
      throw new InvalidRequestException(
          "Ventana no válida: " + window + " (five_hour o seven_day)");
    }
    UsageHistory history = reports.history(runner, provider, bucket, window);
    if (history == null) {
      throw new NotFoundException("Cuota de " + provider, window);
    }
    return history;
  }

  /** Lo que cuesta cada agente: sus invocaciones de las últimas 24 h, 7 o 30 días. */
  @GetMapping("/api/usage/agents")
  List<AgentUsage> agents(
      @RequestParam(defaultValue = "7d") String period,
      @RequestParam(required = false) UUID runner) {
    return reports.agents(period, runner);
  }

  /** Las ejecuciones del periodo que más cuota han gastado. */
  @GetMapping("/api/usage/runs")
  List<RunUsage> runs(
      @RequestParam(defaultValue = "7d") String period,
      @RequestParam(defaultValue = "10") int limit,
      @RequestParam(required = false) UUID runner) {
    if (limit < 1 || limit > MAX_RUNS) {
      throw new InvalidRequestException("limit va de 1 a " + MAX_RUNS);
    }
    return reports.runs(period, limit, runner);
  }

  /**
   * @param runners a cuántos runners se ha pedido
   */
  record RefreshRequested(int runners) {}

  /**
   * «Actualizar»: los runners (o solo {@code runner}) consultan sus cuotas en cuanto reciben la
   * respuesta a su siguiente latido. Lo que vean llega en el latido después.
   */
  @PostMapping("/api/usage/refresh")
  @ResponseStatus(HttpStatus.ACCEPTED)
  RefreshRequested refresh(@RequestParam(required = false) UUID runner) {
    var ids = snapshots.latest(runner).runners().stream().map(UsageWindows.Runner::id).toList();
    refresh.request(ids);
    return new RefreshRequested(ids.size());
  }
}
