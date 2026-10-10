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
    registry.add("skynet.runner.registration-token", () -> RUNNER_REGISTRATION_TOKEN);
    registry.add("skynet.security.username", () -> ADMIN_USER);
    registry.add("skynet.security.password", () -> ADMIN_SECRET);
    registry.add("skynet.runner.redeliver-after", () -> "1s");
    registry.add("skynet.artifacts.root", () -> ARTIFACTS.toString());
    registry.add("skynet.artifacts.max-size", () -> "64KB");
    registry.add("skynet.artifacts.max-upload", () -> "512KB");
  }

  protected static final String RUNNER_REGISTRATION_TOKEN = "test-registration-token";
  protected static final String ADMIN_USER = "admin";
  protected static final String ADMIN_SECRET = "test-admin-password";

  /** Cabecera HTTP Basic del usuario de la web, para las llamadas de los tests a la API. */
  protected static final String ADMIN_BASIC_AUTH =
      "Basic "
          + java.util.Base64.getEncoder()
              .encodeToString(
                  (ADMIN_USER + ":" + ADMIN_SECRET)
                      .getBytes(java.nio.charset.StandardCharsets.UTF_8));

  /** Blobs de los artefactos, compartidos por todos los tests de la JVM. */
  protected static final java.nio.file.Path ARTIFACTS = createArtifactsDir();

  private static java.nio.file.Path createArtifactsDir() {
    try {
      return java.nio.file.Files.createTempDirectory("skynet-artifacts");
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  /** Secuencia del último evento registrado antes del test. */
  protected long baseline;

  @BeforeEach
  void cleanDatabase() {
    // TRUNCATE no dispara el trigger append-only de event (es por fila). La secuencia de
    // eventos no se reinicia: igual que en producción, nunca retrocede.
    jdbc.sql(
            "TRUNCATE app_setting, event, runner_command, artifact, verification_run, prompt, agent_run, workspace, runner, stage_run, workflow_run, work_item, repository,"
                + " project")
        .update();
    // Los workflows de los tests; adhoc viene de las migraciones y se queda. Las versiones
    // publicadas solo se borran dentro de una purga.
    jdbc.sql(
            "DO $$ BEGIN PERFORM set_config('skynet.purge', 'on', true);"
                + " DELETE FROM workflow_definition WHERE key <> 'adhoc';"
                + " DELETE FROM workflow WHERE key <> 'adhoc';"
                + " UPDATE workflow SET archived_at = NULL; END $$")
        .update();
    baseline =
        jdbc.sql("SELECT last_value FROM event_sequence WHERE id = 1").query(Long.class).single();
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port)
            .defaultHeader(org.springframework.http.HttpHeaders.AUTHORIZATION, ADMIN_BASIC_AUTH)
            .build();
  }
}
