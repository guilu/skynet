package dev.skynet.controlplane.deployment;

import dev.skynet.controlplane.deployment.Deployments.Deployment;
import dev.skynet.controlplane.deployment.Deployments.Health;
import dev.skynet.controlplane.deployment.Deployments.Status;
import dev.skynet.controlplane.support.ContractFixture;
import dev.skynet.controlplane.support.IntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

/** El contrato con la web de los despliegues (DL-E): ver {@code ContractIT}. */
class DeploymentContractIT extends IntegrationTest {

  static final UUID REPOSITORY = UUID.fromString("0b6a3c1e-5f0e-4a8e-9a43-1c2d3e4f5a03");
  static final UUID DEPLOYED = UUID.fromString("0b6a3c1e-5f0e-4a8e-9a43-1c2d3e4f5a41");
  static final UUID PREVIOUS = UUID.fromString("0b6a3c1e-5f0e-4a8e-9a43-1c2d3e4f5a42");
  static final UUID STATUS = UUID.fromString("0b6a3c1e-5f0e-4a8e-9a43-1c2d3e4f5a43");
  static final Instant AT = Instant.parse("2026-11-05T18:00:00Z");

  @Autowired ObjectMapper json;

  static Deployment deployed() {
    return new Deployment(
        DEPLOYED,
        REPOSITORY,
        "omarchy",
        "3141592653",
        "99179be71dd8ad0823c9f405fbfd2c3b8122ff5f",
        "claude/dl-c",
        245,
        "http://omarchy:8081",
        "hermes",
        Status.SUCCEEDED,
        Health.HEALTHY,
        null,
        AT,
        AT.plusSeconds(5),
        AT.plusSeconds(90),
        AT.plusSeconds(90),
        null,
        AT,
        AT.plusSeconds(130),
        "Intento 2: Responde 200",
        AT.plusSeconds(130),
        2,
        null);
  }

  static Deployment previous() {
    return new Deployment(
        PREVIOUS,
        REPOSITORY,
        "omarchy",
        "3141592600",
        "7fa86f0c1a2b3c4d5e6f708192a3b4c5d6e7f809",
        "main",
        null,
        null,
        "hermes",
        Status.SUPERSEDED,
        Health.UNKNOWN,
        null,
        AT.minusSeconds(3600),
        AT.minusSeconds(3595),
        AT.minusSeconds(3500),
        AT.plusSeconds(90),
        DEPLOYED,
        AT.minusSeconds(3600),
        AT.plusSeconds(90),
        null,
        null,
        0,
        null);
  }

  @Test
  void deployments() {
    ContractFixture.check(json, "deployments", List.of(deployed(), previous()));
    ContractFixture.check(
        json,
        "deployment-environments",
        List.of(new Deployments.EnvironmentState("omarchy", deployed(), deployed())));
    ContractFixture.check(
        json,
        "deployment-detail",
        new DeploymentController.DeploymentDetail(
            deployed(),
            List.of(
                new Deployments.StatusRecord(
                    STATUS,
                    "2718281828",
                    Status.SUCCEEDED,
                    "docker compose up -d: OK",
                    AT.plusSeconds(90),
                    true,
                    AT.plusSeconds(91))),
            List.of(
                new HealthChecks.Probe(
                    1,
                    "http://omarchy:8081/actuator/health",
                    HealthChecks.Outcome.BAD_STATUS,
                    503,
                    40,
                    "Responde 503",
                    AT.plusSeconds(105)),
                new HealthChecks.Probe(
                    2,
                    "http://omarchy:8081/actuator/health",
                    HealthChecks.Outcome.OK,
                    200,
                    35,
                    "Responde 200",
                    AT.plusSeconds(130)))));
    ContractFixture.check(
        json,
        "health-checks",
        List.of(
            new HealthChecks.HealthCheck(
                REPOSITORY,
                "omarchy",
                "http://omarchy:8081/actuator/health",
                5,
                6,
                10,
                15,
                false,
                true,
                AT,
                AT)));
    ContractFixture.check(
        json, "deployment-reconciled", new DeploymentReconciler.Reconciled(REPOSITORY, 3, 5, 1));
  }
}
