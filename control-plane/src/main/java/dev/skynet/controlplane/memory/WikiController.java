package dev.skynet.controlplane.memory;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * La wiki de un proyecto (MV-C), de solo lectura: sus páginas, cada una con sus fuentes, y sus
 * versiones. {@code repositoryId} elige la página de un repositorio; sin él, la de todo el
 * proyecto.
 */
@RestController
class WikiController {

  private final WikiPages pages;

  WikiController(WikiPages pages) {
    this.pages = pages;
  }

  @GetMapping("/api/projects/{projectId}/wiki")
  List<WikiPages.Summary> list(@PathVariable UUID projectId) {
    return pages.list(projectId);
  }

  @GetMapping("/api/projects/{projectId}/wiki/{slug}")
  WikiPages.Page get(
      @PathVariable UUID projectId,
      @PathVariable String slug,
      @RequestParam(required = false) UUID repositoryId) {
    return pages.get(projectId, repositoryId, slug);
  }

  @GetMapping("/api/projects/{projectId}/wiki/{slug}/revisions")
  List<WikiPages.Revision> history(
      @PathVariable UUID projectId,
      @PathVariable String slug,
      @RequestParam(required = false) UUID repositoryId) {
    return pages.history(projectId, repositoryId, slug);
  }

  @GetMapping("/api/projects/{projectId}/wiki/{slug}/revisions/{version}")
  WikiPages.Revision revision(
      @PathVariable UUID projectId,
      @PathVariable String slug,
      @PathVariable int version,
      @RequestParam(required = false) UUID repositoryId) {
    return pages.revision(projectId, repositoryId, slug, version);
  }
}
