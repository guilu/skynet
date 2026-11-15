package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.memory.WikiPages;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Regenerar la wiki de un proyecto a mano (MV-C): lo mismo que hace Skynet al terminar cada
 * ejecución. Sirve para un proyecto con historia de antes de la wiki o tras confirmar memoria.
 */
@RestController
class WikiRefreshController {

  private final WikiWriter writer;
  private final WikiPages pages;

  WikiRefreshController(WikiWriter writer, WikiPages pages) {
    this.writer = writer;
    this.pages = pages;
  }

  @PostMapping("/api/projects/{projectId}/wiki/refresh")
  List<WikiPages.Summary> refresh(@PathVariable UUID projectId) {
    writer.refresh(projectId);
    return pages.list(projectId);
  }
}
