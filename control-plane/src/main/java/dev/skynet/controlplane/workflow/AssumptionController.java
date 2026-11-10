package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.shared.NotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Los supuestos de una ejecución (ES-C): lo que sus agentes supusieron sin comprobarlo. */
@RestController
class AssumptionController {

  private final JdbcClient jdbc;

  AssumptionController(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @GetMapping("/api/workflow-runs/{id}/assumptions")
  List<RunAssumptions.Assumption> ofRun(@PathVariable UUID id) {
    if (!jdbc.sql("SELECT EXISTS (SELECT 1 FROM workflow_run WHERE id = ?)")
        .param(id)
        .query(Boolean.class)
        .single()) {
      throw new NotFoundException("Ejecución", id);
    }
    return RunAssumptions.of(jdbc, id);
  }
}
