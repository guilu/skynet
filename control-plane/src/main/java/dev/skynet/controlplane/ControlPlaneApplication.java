package dev.skynet.controlplane;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ControlPlaneApplication {

  public static void main(String[] args) {
    checkDatabasePassword(System.getenv());
    SpringApplication.run(ControlPlaneApplication.class, args);
  }

  /**
   * Con {@code SKYNET_REQUIRE_DB_PASSWORD=true} (Docker Compose, perfil app) no arranca sin {@code
   * SKYNET_DB_PASSWORD}: la base de datos no se queda con la contraseña por defecto. Fuera de
   * Docker sigue valiendo la de desarrollo ({@code skynet}), con PostgreSQL solo en este equipo.
   */
  static void checkDatabasePassword(Map<String, String> env) {
    if (!"true".equalsIgnoreCase(env.getOrDefault("SKYNET_REQUIRE_DB_PASSWORD", ""))) {
      return;
    }
    String password = env.get("SKYNET_DB_PASSWORD");
    if (password == null || password.isBlank()) {
      throw new IllegalStateException(
          "Define SKYNET_DB_PASSWORD en deploy/.env: es la contraseña de PostgreSQL y no tiene"
              + " valor por defecto con el perfil app");
    }
  }
}
