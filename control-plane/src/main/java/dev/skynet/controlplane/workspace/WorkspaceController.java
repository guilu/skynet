package dev.skynet.controlplane.workspace;

import dev.skynet.controlplane.shared.InvalidRequestException;
import dev.skynet.controlplane.shared.NotFoundException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** API de la fábrica (FS-B): centro de operaciones del proyecto, trabajos por fase y cambios. */
@RestController
class WorkspaceController {

  private final WorkspaceQueries queries;

  WorkspaceController(WorkspaceQueries queries) {
    this.queries = queries;
  }

  @GetMapping("/api/projects/{id}/overview")
  ProjectOverview overview(@PathVariable UUID id) {
    return queries.overview(id);
  }

  @GetMapping("/api/projects/{id}/changes")
  List<ChangeView> changes(@PathVariable UUID id) {
    return queries.changes(id);
  }

  @GetMapping("/api/results")
  List<ChangeView> results() {
    return queries.results();
  }

  /** Trabajos en esas fases ({@code phase}, repetible); sin ninguna, los no terminados. */
  @GetMapping("/api/work-items")
  List<WorkItemSummary> workItems(
      @RequestParam(required = false) List<String> phase,
      @RequestParam(required = false) UUID projectId) {
    return queries.workItems(phases(phase), projectId);
  }

  @GetMapping("/api/work-items/{id}/summary")
  WorkItemSummary workItem(@PathVariable UUID id) {
    return queries.workItem(id).orElseThrow(() -> new NotFoundException("Trabajo", id));
  }

  private static Set<WorkPhase> phases(List<String> names) {
    Set<WorkPhase> phases = EnumSet.noneOf(WorkPhase.class);
    if (names == null) {
      return phases;
    }
    for (String name : names) {
      try {
        phases.add(WorkPhase.valueOf(name));
      } catch (IllegalArgumentException e) {
        throw new InvalidRequestException("Fase no válida: " + name);
      }
    }
    return phases;
  }
}
