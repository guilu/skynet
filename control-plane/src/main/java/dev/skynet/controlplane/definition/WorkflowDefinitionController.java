package dev.skynet.controlplane.definition;

import dev.skynet.controlplane.shared.ArchiveState;
import dev.skynet.controlplane.shared.Archived;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Workflows ({@code /api/workflows/{key}}) y sus versiones ({@code /api/workflow-versions/{id}}).
 * La lista antigua de definiciones publicadas, {@code /api/workflow-definitions}, sigue en {@code
 * RunController}.
 */
@RestController
class WorkflowDefinitionController {

  private static final String SCHEMA = loadSchema();

  private final WorkflowDefinitions definitions;

  WorkflowDefinitionController(WorkflowDefinitions definitions) {
    this.definitions = definitions;
  }

  record Source(@NotNull String sourceYaml) {}

  /** YAML que validar; {@code key} y {@code version}, si es el borrador de un workflow. */
  record ValidateRequest(@NotNull String sourceYaml, String key, Integer version) {}

  record SaveDraft(@NotNull String sourceYaml, @NotNull Long revision) {}

  record Publish(@NotNull Long revision) {}

  @GetMapping("/api/workflows")
  List<WorkflowSummary> list(@RequestParam(required = false) String archived) {
    return definitions.list(Archived.of(archived));
  }

  /** Crea un workflow con el YAML como borrador de su versión 1. */
  @PostMapping("/api/workflows")
  @ResponseStatus(HttpStatus.CREATED)
  DefinitionDetail create(@Valid @RequestBody Source request) {
    return definitions.create(request.sourceYaml());
  }

  @GetMapping("/api/workflows/{key}")
  WorkflowView workflow(@PathVariable String key) {
    return definitions.workflow(key);
  }

  /** Abre (o devuelve) el borrador de la versión siguiente. */
  @PostMapping("/api/workflows/{key}/draft")
  DefinitionDetail draft(@PathVariable String key) {
    return definitions.draft(key);
  }

  @PostMapping("/api/workflows/{key}/archive")
  ArchiveState archive(@PathVariable String key) {
    return definitions.archive(key);
  }

  @PostMapping("/api/workflows/{key}/restore")
  ArchiveState restore(@PathVariable String key) {
    return definitions.restore(key);
  }

  /** Valida un YAML sin guardarlo. */
  @PostMapping("/api/workflows/validate")
  ValidationView validate(@Valid @RequestBody ValidateRequest request) {
    return definitions.validate(request.sourceYaml(), request.key(), request.version());
  }

  /** JSON Schema del YAML, para el autocompletado del editor de la web. */
  @GetMapping(value = "/api/workflows/schema", produces = MediaType.APPLICATION_JSON_VALUE)
  String schema() {
    return SCHEMA;
  }

  @GetMapping("/api/workflow-versions/{id}")
  DefinitionDetail version(@PathVariable UUID id) {
    return definitions.definition(id);
  }

  @PutMapping("/api/workflow-versions/{id}")
  DefinitionDetail save(@PathVariable UUID id, @Valid @RequestBody SaveDraft request) {
    return definitions.save(id, request.sourceYaml(), request.revision());
  }

  @PostMapping("/api/workflow-versions/{id}/publish")
  DefinitionDetail publish(@PathVariable UUID id, @Valid @RequestBody Publish request) {
    return definitions.publish(id, request.revision());
  }

  /** Descarta un borrador. */
  @DeleteMapping("/api/workflow-versions/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void discard(@PathVariable UUID id) {
    definitions.discard(id);
  }

  private static String loadSchema() {
    try (InputStream in =
        WorkflowDefinitionController.class.getResourceAsStream(
            "/dev/skynet/protocol/workflow-definition.schema.json")) {
      if (in == null) {
        throw new IllegalStateException("Falta workflow-definition.schema.json en protocol");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @ExceptionHandler(DefinitionRejectedException.class)
  ProblemDetail rejected(DefinitionRejectedException e) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            e.conflict() ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST, e.getMessage());
    problem.setProperty("problems", e.problems());
    return problem;
  }
}
