package dev.skynet.controlplane.usage;

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

  private final QuotaSnapshots snapshots;
  private final QuotaRefresh refresh;

  UsageController(QuotaSnapshots snapshots, QuotaRefresh refresh) {
    this.snapshots = snapshots;
    this.refresh = refresh;
  }

  /** La última muestra de cada ventana, de cada runner o solo de {@code runner}. */
  @GetMapping("/api/usage/windows")
  UsageWindows windows(@RequestParam(required = false) UUID runner) {
    return snapshots.latest(runner);
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
