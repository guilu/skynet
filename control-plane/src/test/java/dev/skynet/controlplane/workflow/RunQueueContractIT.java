package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.support.ContractFixture;
import dev.skynet.controlplane.support.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

/** El contrato con la web de la cola por repositorio (DS-C): ver {@code ContractIT}. */
class RunQueueContractIT extends IntegrationTest {

  static final UUID REPOSITORY = UUID.fromString("0b6a3c1e-5f0e-4a8e-9a43-1c2d3e4f5a03");
  static final UUID RUN = UUID.fromString("0b6a3c1e-5f0e-4a8e-9a43-1c2d3e4f5a04");
  static final UUID QUEUED = UUID.fromString("0b6a3c1e-5f0e-4a8e-9a43-1c2d3e4f5a23");

  @Autowired ObjectMapper json;

  @Test
  void queue() {
    ContractFixture.check(
        json,
        "run-queue",
        new RunQueueController.QueueView(REPOSITORY, 2, false, 2, 2, List.of(QUEUED)));
    ContractFixture.check(
        json,
        "run-queue-position",
        new RunQueueController.RunPosition(QUEUED, REPOSITORY, 1, 2, 2));
  }
}
