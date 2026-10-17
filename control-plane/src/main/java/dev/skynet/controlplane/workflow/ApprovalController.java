package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.shared.InvalidRequestException;
import java.security.Principal;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Centro de aprobaciones (W5): las peticiones de las puertas y sus decisiones. */
@RestController
class ApprovalController {

  private static final Set<String> STATUSES =
      Set.of(
          Approvals.PENDING,
          Approvals.APPROVED,
          Approvals.REJECTED,
          Approvals.CHANGES_REQUESTED,
          Approvals.CANCELLED,
          Approvals.EXPIRED);
  private static final Set<String> RISKS = Set.of("LOW", "MEDIUM", "HIGH");

  private final Approvals approvals;

  ApprovalController(Approvals approvals) {
    this.approvals = approvals;
  }

  /**
   * Las peticiones, primero las pendientes. {@code status} admite varios separados por comas; sin
   * él, todas.
   */
  @GetMapping("/api/approvals")
  List<ApprovalSummary> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID projectId,
      @RequestParam(required = false) String risk) {
    Set<String> statuses =
        status == null || status.isBlank()
            ? Set.of()
            : Arrays.stream(status.split(","))
                .map(s -> s.strip().toUpperCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    for (String value : statuses) {
      if (!STATUSES.contains(value)) {
        throw new InvalidRequestException("Estado de aprobación desconocido: " + value);
      }
    }
    String riskFilter = risk == null || risk.isBlank() ? null : risk.toUpperCase(Locale.ROOT);
    if (riskFilter != null && !RISKS.contains(riskFilter)) {
      throw new InvalidRequestException("Riesgo desconocido: " + risk);
    }
    return approvals.list(statuses, projectId, riskFilter);
  }

  @GetMapping("/api/approvals/{id}")
  ApprovalView get(@PathVariable UUID id) {
    return approvals.view(id);
  }

  /** Aprobar, rechazar, pedir cambios o comentar. */
  @PostMapping("/api/approvals/{id}/decisions")
  ApprovalView decide(
      @PathVariable UUID id, @RequestBody DecisionRequest request, Principal principal) {
    if (request == null || request.action() == null) {
      throw new InvalidRequestException(
          "Falta `action`: APPROVE, REJECT, REQUEST_CHANGES o COMMENT");
    }
    Approvals.Action action;
    try {
      action = Approvals.Action.valueOf(request.action().strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new InvalidRequestException(
          "Acción desconocida: "
              + request.action()
              + ". Admite: APPROVE, REJECT, REQUEST_CHANGES o COMMENT");
    }
    return approvals.decide(
        id, action, request.comment(), principal == null ? "anónimo" : principal.getName());
  }

  record DecisionRequest(String action, String comment) {}
}
