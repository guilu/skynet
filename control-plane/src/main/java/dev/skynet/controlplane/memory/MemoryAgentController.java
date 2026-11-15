package dev.skynet.controlplane.memory;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * La memoria para los agentes, a través del puente MCP del runner ({@code skynet-runner
 * memory-mcp}). Cada petición lleva {@code Authorization: Bearer <token de memoria>}; el ámbito
 * sale de la invocación dueña del token.
 */
@RestController
@RequestMapping("/api/runner/memory")
class MemoryAgentController {

  private final MemoryAgents agents;

  MemoryAgentController(MemoryAgents agents) {
    this.agents = agents;
  }

  record SearchRequest(String query, Set<MemoryKind> kinds, Integer limit) {}

  record GetRequest(UUID id) {}

  /** Una observación entera, como la ve el agente. */
  record ObservationView(
      UUID id,
      MemoryKind kind,
      String title,
      String topicKey,
      String scope,
      String what,
      String why,
      String where,
      String learned,
      List<String> tags,
      boolean pinned,
      int version,
      MemoryOrigin origin,
      MemoryTrust trust,
      String updatedAt) {

    static ObservationView of(MemoryObservation o) {
      return new ObservationView(
          o.id(),
          o.kind(),
          o.title(),
          o.topicKey(),
          o.repositoryId() == null ? "project" : "repository",
          o.what(),
          o.why(),
          o.where(),
          o.learned(),
          o.tags(),
          o.pinned(),
          o.version(),
          o.origin(),
          o.trust(),
          o.updatedAt().toString());
    }
  }

  @PostMapping("/context")
  MemoryAgents.Context context(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
    return agents.context(agents.authenticate(authorization));
  }

  @PostMapping("/search")
  MemoryAgents.SearchResult search(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestBody SearchRequest request) {
    MemoryAgents.Caller caller = agents.authenticate(authorization);
    return agents.search(caller, request.query(), request.kinds(), request.limit());
  }

  @PostMapping("/get")
  ObservationView get(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestBody GetRequest request) {
    MemoryAgents.Caller caller = agents.authenticate(authorization);
    if (request.id() == null) {
      throw new dev.skynet.controlplane.shared.InvalidRequestException("Falta el id");
    }
    return ObservationView.of(agents.get(caller, request.id()));
  }

  /** Qué pregunta el agente con {@code mem_done}: sin {@code query}, su propio trabajo. */
  record DoneRequest(String query) {}

  record DoneResult(List<DoneWork.Match> matches) {}

  @PostMapping("/done")
  DoneResult done(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestBody(required = false) DoneRequest request) {
    MemoryAgents.Caller caller = agents.authenticate(authorization);
    return new DoneResult(agents.done(caller, request == null ? null : request.query()));
  }

  /** El índice de la wiki del proyecto (MV-D): las páginas que hay y el texto del índice. */
  @PostMapping("/wiki-index")
  MemoryAgents.WikiIndex wikiIndex(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
    return agents.wikiIndex(agents.authenticate(authorization));
  }

  record WikiReadRequest(String slug) {}

  /** Una página de la wiki, por su {@code slug} (MV-D). */
  @PostMapping("/wiki-read")
  MemoryAgents.WikiRead wikiRead(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestBody(required = false) WikiReadRequest request) {
    MemoryAgents.Caller caller = agents.authenticate(authorization);
    return agents.wikiRead(caller, request == null ? null : request.slug());
  }

  @PostMapping("/save")
  MemoryAgents.Saved save(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestBody MemoryAgents.SaveRequest request) {
    return agents.save(agents.authenticate(authorization), request);
  }

  @PostMapping("/session-summary")
  MemoryAgents.Saved sessionSummary(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestBody MemoryAgents.SessionSummary request) {
    return agents.sessionSummary(agents.authenticate(authorization), request);
  }

  @ExceptionHandler(MemoryAgents.MemoryTokenException.class)
  ProblemDetail unauthorized(MemoryAgents.MemoryTokenException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
  }

  @ExceptionHandler(MemoryAgents.MemoryLimitException.class)
  ProblemDetail tooMany(MemoryAgents.MemoryLimitException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
  }

  @ExceptionHandler(MemoryAgents.MemoryReadOnlyException.class)
  ProblemDetail readOnly(MemoryAgents.MemoryReadOnlyException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
  }
}
