package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.shared.InvalidRequestException;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** El banco de evaluaciones del juez (EV-C): sus casos y repetirlos con otro juez. */
@RestController
class EvalController {

  private final Evals evals;

  EvalController(Evals evals) {
    this.evals = evals;
  }

  /** Los casos (de un repositorio, o todos) con sus repeticiones y el acierto de cada juez. */
  @GetMapping("/api/factory/evals")
  Evals.Bank bank(@RequestParam(required = false) UUID repositoryId) {
    return evals.bank(repositoryId);
  }

  /** El caso de una ejecución, si lo es: una lista vacía o con él. */
  @GetMapping("/api/workflow-runs/{id}/eval-case")
  List<Evals.CaseView> ofRun(@PathVariable UUID id) {
    return evals.bank(null).cases().stream().filter(c -> c.runId().equals(id)).toList();
  }

  /** Añade la ejecución al banco, con lo que debería decir un buen juez. */
  @PostMapping("/api/workflow-runs/{id}/eval-case")
  @ResponseStatus(HttpStatus.CREATED)
  Evals.CaseView add(@PathVariable UUID id, @RequestBody AddRequest request, Principal principal) {
    return evals.add(
        id,
        request == null ? null : request.expected(),
        request == null ? null : request.note(),
        actor(principal));
  }

  @GetMapping("/api/eval-cases/{id}")
  Evals.CaseView get(@PathVariable UUID id) {
    return evals.view(id);
  }

  @DeleteMapping("/api/eval-cases/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void remove(@PathVariable UUID id, Principal principal) {
    evals.remove(id, actor(principal));
  }

  /** Los jueces que se pueden elegir: los agentes del workflow del caso. */
  @GetMapping("/api/eval-cases/{id}/judges")
  List<Evals.JudgeOption> judges(@PathVariable UUID id) {
    return evals.options(id);
  }

  /** Repite el caso con uno o dos jueces (A/B). Cada juez es una invocación real del modelo. */
  @PostMapping("/api/eval-cases/{id}/runs")
  @ResponseStatus(HttpStatus.CREATED)
  List<Evals.EvalRunView> repeat(
      @PathVariable UUID id, @RequestBody RepeatRequest request, Principal principal) {
    return evals.repeat(id, request == null ? null : request.judges(), actor(principal));
  }

  /** Repite todos los casos de un repositorio con los mismos jueces. */
  @PostMapping("/api/factory/evals/runs")
  @ResponseStatus(HttpStatus.CREATED)
  List<Evals.EvalRunView> repeatAll(@RequestBody BankRequest request, Principal principal) {
    if (request == null || request.repositoryId() == null) {
      throw new InvalidRequestException("Falta `repositoryId`");
    }
    return evals.repeatAll(request.repositoryId(), request.judges(), actor(principal));
  }

  private static String actor(Principal principal) {
    return principal == null ? "anónimo" : principal.getName();
  }

  record AddRequest(String expected, String note) {}

  record RepeatRequest(List<Evals.JudgeChoice> judges) {}

  record BankRequest(UUID repositoryId, List<Evals.JudgeChoice> judges) {}
}
