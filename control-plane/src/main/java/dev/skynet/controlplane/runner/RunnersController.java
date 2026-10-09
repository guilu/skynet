package dev.skynet.controlplane.runner;

import dev.skynet.controlplane.shared.ArchiveState;
import dev.skynet.controlplane.shared.Archived;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lectura de los runners para la web (la API de los propios runners está en {@link
 * RunnerController}).
 */
@RestController
class RunnersController {

  private final RunnerDirectory directory;
  private final RunnerRegistry registry;

  RunnersController(RunnerDirectory directory, RunnerRegistry registry) {
    this.directory = directory;
    this.registry = registry;
  }

  @GetMapping("/api/runners")
  List<RunnerView> list(@RequestParam(defaultValue = "false") String archived) {
    return directory.list(Archived.of(archived));
  }

  /** Invalida el token del runner; si es el legítimo, se vuelve a registrar solo. */
  @PostMapping("/api/runners/{id}/revoke")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void revoke(@PathVariable UUID id) {
    registry.revoke(id);
  }

  /** Olvida el runner: invalida su token y lo oculta. */
  @PostMapping("/api/runners/{id}/archive")
  ArchiveState archive(@PathVariable UUID id) {
    return registry.archive(id);
  }

  @PostMapping("/api/runners/{id}/restore")
  ArchiveState restore(@PathVariable UUID id) {
    return registry.restore(id);
  }
}
