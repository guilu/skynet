package dev.skynet.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ControlPlaneApplicationIT {

  @Container @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

  @Autowired JdbcClient jdbc;

  @Test
  void appliesFlywayMigrations() {
    Integer applied =
        jdbc.sql("SELECT count(*) FROM flyway_schema_history WHERE success")
            .query(Integer.class)
            .single();
    assertThat(applied).isGreaterThanOrEqualTo(1);
  }
}
