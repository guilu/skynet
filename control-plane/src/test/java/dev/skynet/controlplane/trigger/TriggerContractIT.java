package dev.skynet.controlplane.trigger;

import dev.skynet.controlplane.support.ContractFixture;
import dev.skynet.controlplane.support.IntegrationTest;
import dev.skynet.controlplane.workitem.WorkItemType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

/** El contrato con la web de las vistas de los disparadores (DS): ver {@code ContractIT}. */
class TriggerContractIT extends IntegrationTest {

  static final UUID REPOSITORY = UUID.fromString("0b6a3c1e-5f0e-4a8e-9a43-1c2d3e4f5a03");
  static final UUID WORK_ITEM = UUID.fromString("0b6a3c1e-5f0e-4a8e-9a43-1c2d3e4f5a02");
  static final UUID RUN = UUID.fromString("0b6a3c1e-5f0e-4a8e-9a43-1c2d3e4f5a04");
  static final UUID LABEL_RULE = UUID.fromString("0b6a3c1e-5f0e-4a8e-9a43-1c2d3e4f5a20");
  static final UUID SCHEDULE_RULE = UUID.fromString("0b6a3c1e-5f0e-4a8e-9a43-1c2d3e4f5a21");
  static final UUID LAUNCH = UUID.fromString("0b6a3c1e-5f0e-4a8e-9a43-1c2d3e4f5a22");
  static final Instant T0 = Instant.parse("2026-10-05T09:00:00Z");

  @Autowired ObjectMapper json;

  @Test
  void settings() {
    ContractFixture.check(
        json,
        "trigger-settings",
        new TriggerSettings.View(
            true, List.of("guilu"), true, List.of("guilu/skynet-commands"), 10, 3, T0));
  }

  @Test
  void rules() {
    TriggerRules.Rule label =
        new TriggerRules.Rule(
            LABEL_RULE,
            REPOSITORY,
            TriggerRules.Kind.ISSUE,
            "feature",
            "skynet:feature",
            WorkItemType.FEATURE,
            Map.of(),
            Map.of("priority", new InputPolicy.Rule("string", 20, List.of("alta", "baja"))),
            true,
            T0,
            T0,
            null);
    TriggerRules.Rule schedule =
        new TriggerRules.Rule(
            SCHEDULE_RULE,
            REPOSITORY,
            TriggerRules.Kind.SCHEDULE,
            "revision-semanal",
            null,
            WorkItemType.FEATURE,
            Map.of("prompt", "Revisa las dependencias"),
            Map.of(),
            true,
            T0,
            T0,
            new TriggerRules.Schedule(
                "0 0 9 * * MON",
                "Europe/Madrid",
                "revision-semanal (programado)",
                WORK_ITEM,
                T0.plusSeconds(7 * 24 * 3600),
                T0,
                "LAUNCHED",
                null,
                RUN));
    ContractFixture.check(json, "trigger-rules", List.of(label, schedule));
  }

  @Test
  void launches() {
    ContractFixture.check(
        json,
        "trigger-launches",
        List.of(
            new TriggerController.LaunchView(
                LAUNCH,
                "0a1b2c3d-delivery",
                "guilu/demo-repo",
                12,
                "https://github.com/guilu/demo-repo/issues/12",
                "skynet:feature",
                "ISSUE",
                null,
                false,
                "guilu",
                "guilu",
                "Añadir la resta",
                "LAUNCHED",
                null,
                LABEL_RULE,
                REPOSITORY,
                WORK_ITEM,
                RUN,
                false,
                null,
                T0,
                T0.plusSeconds(2))));
  }
}
