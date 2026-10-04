package dev.skynet.controlplane.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base de datos para los tests de integración.
 *
 * <p>Si existe {@code SKYNET_TEST_DB_URL} (con {@code SKYNET_TEST_DB_USER} y {@code
 * SKYNET_TEST_DB_PASSWORD}) se usa esa base de datos; si no, se arranca un PostgreSQL con
 * Testcontainers. Sin ninguna de las dos, los tests de integración se omiten.
 */
public final class TestDatabase {

  private static final String URL = System.getenv("SKYNET_TEST_DB_URL");
  private static PostgreSQLContainer container;

  private TestDatabase() {}

  public static boolean available() {
    return URL != null || DockerClientFactory.instance().isDockerAvailable();
  }

  public static synchronized void register(DynamicPropertyRegistry registry) {
    if (URL != null) {
      registry.add("spring.datasource.url", () -> URL);
      registry.add("spring.datasource.username", () -> env("SKYNET_TEST_DB_USER", "skynet"));
      registry.add("spring.datasource.password", () -> env("SKYNET_TEST_DB_PASSWORD", "skynet"));
      return;
    }
    if (container == null) {
      container = new PostgreSQLContainer("postgres:16-alpine");
      container.start();
    }
    registry.add("spring.datasource.url", container::getJdbcUrl);
    registry.add("spring.datasource.username", container::getUsername);
    registry.add("spring.datasource.password", container::getPassword);
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value != null ? value : fallback;
  }
}
