package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.shared.InvalidRequestException;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** El registro de hallazgos de los jueces (RH-A): lo que encontraron y qué pasó con ello. */
@RestController
class FindingController {

  private final Findings findings;
  private final JudgeStats judges;

  FindingController(Findings findings, JudgeStats judges) {
    this.findings = findings;
    this.judges = judges;
  }

  /** Ruido y acierto de cada juez en los últimos {@code days} días (RH-C). */
  @GetMapping("/api/factory/judges")
  List<JudgeStats.JudgeRow> judges(
      @RequestParam(defaultValue = "30") int days,
      @RequestParam(required = false) String category) {
    return judges.since(days, category == null || category.isBlank() ? null : category);
  }

  @GetMapping("/api/workflow-runs/{id}/findings")
  List<Findings.FindingView> ofRun(@PathVariable UUID id) {
    return findings.ofRun(id);
  }

  /** Decir que un hallazgo es intencionado ({@code aceptado}), que no aplica o deshacerlo. */
  @PostMapping("/api/findings/{id}/outcome")
  Findings.FindingView decide(
      @PathVariable UUID id, @RequestBody OutcomeRequest request, Principal principal) {
    if (request == null || request.outcome() == null) {
      throw new InvalidRequestException("Falta `outcome`: aceptado, irrelevante o abierto");
    }
    return findings.decide(
        id,
        request.outcome(),
        request.reason(),
        principal == null ? "anónimo" : principal.getName());
  }

  record OutcomeRequest(String outcome, String reason) {}
}
