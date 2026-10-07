package dev.skynet.controlplane.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param username usuario único de la web y la API
 * @param password su contraseña; vacía, se genera una al arrancar y se escribe en el log
 * @param requirePassword si es {@code true}, no arranca sin contraseña (Docker Compose)
 * @param corsOrigins orígenes a los que se permite llamar a la API desde otro dominio; vacío,
 *     ninguno (la web va por el mismo origen)
 */
@ConfigurationProperties("skynet.security")
record SecurityProperties(
    String username, String password, boolean requirePassword, List<String> corsOrigins) {

  SecurityProperties {
    username = username == null || username.isBlank() ? "admin" : username;
    corsOrigins = corsOrigins == null ? List.of() : List.copyOf(corsOrigins);
  }
}
