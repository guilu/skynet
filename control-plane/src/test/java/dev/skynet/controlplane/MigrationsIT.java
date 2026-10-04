package dev.skynet.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skynet.controlplane.support.IntegrationTest;
import org.junit.jupiter.api.Test;

class MigrationsIT extends IntegrationTest {

  @Test
  void appliesMigrationsAndSeedsTheAdhocWorkflow() {
    Integer applied =
        jdbc.sql("SELECT count(*) FROM flyway_schema_history WHERE success")
            .query(Integer.class)
            .single();
    assertThat(applied).isGreaterThanOrEqualTo(2);
    assertThat(
            jdbc.sql("SELECT count(*) FROM workflow_definition WHERE key = 'adhoc'")
                .query(Integer.class)
                .single())
        .isEqualTo(1);
  }
}
