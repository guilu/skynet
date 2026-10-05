package dev.skynet.controlplane.runner;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lectura de los runners para la web (la API de los propios runners está en {@link
 * RunnerController}).
 */
@RestController
class RunnersController {

  private final RunnerDirectory directory;

  RunnersController(RunnerDirectory directory) {
    this.directory = directory;
  }

  @GetMapping("/api/runners")
  List<RunnerView> list() {
    return directory.list();
  }
}
