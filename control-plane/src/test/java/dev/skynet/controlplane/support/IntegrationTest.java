package dev.skynet.controlplane.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/** Arranca la aplicación completa contra PostgreSQL y deja las tablas vacías antes de cada test. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIf("dev.skynet.controlplane.support.TestDatabase#available")
public abstract class IntegrationTest {

  @Autowired protected JdbcClient jdbc;
  @LocalServerPort protected int port;
  protected RestClient http;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    TestDatabase.register(registry);
  }

  /** Secuencia del último evento registrado antes del test. */
  protected long baseline;

  @BeforeEach
  void cleanDatabase() {
    // TRUNCATE no dispara el trigger append-only de event (es por fila). La secuencia de
    // eventos no se reinicia: igual que en producción, nunca retrocede.
    jdbc.sql(
            "TRUNCATE event, prompt, agent_run, stage_run, workflow_run, work_item, repository,"
                + " project")
        .update();
    baseline =
        jdbc.sql("SELECT last_value FROM event_sequence WHERE id = 1").query(Long.class).single();
    http = RestClient.builder().baseUrl("http://localhost:" + port).build();
  }
}
