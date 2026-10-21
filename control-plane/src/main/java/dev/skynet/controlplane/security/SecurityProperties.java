package dev.skynet.controlplane.security;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param username usuario único de la web y la API
 * @param password su contraseña; vacía, se genera una al arrancar y se guarda en {@code
 *     generatedPasswordFile}
 * @param requirePassword si es {@code true}, no arranca sin contraseña (Docker Compose)
 * @param corsOrigins orígenes a los que se permite llamar a la API desde otro dominio; vacío,
 *     ninguno (la web va por el mismo origen)
 * @param generatedPasswordFile fichero, legible solo por el usuario del control plane, donde se
 *     guarda la contraseña generada; el log solo dice dónde está
 * @param maxFailedAttempts fallos seguidos de login, HTTP Basic o registro del runner desde un
 *     mismo origen tras los que se bloquea
 * @param lockout cuánto dura ese bloqueo
 */
@ConfigurationProperties("skynet.security")
record SecurityProperties(
    String username,
    String password,
    boolean requirePassword,
    List<String> corsOrigins,
    Path generatedPasswordFile,
    Integer maxFailedAttempts,
    Duration lockout) {

  SecurityProperties {
    username = username == null || username.isBlank() ? "admin" : username;
    corsOrigins = corsOrigins == null ? List.of() : List.copyOf(corsOrigins);
    generatedPasswordFile =
        generatedPasswordFile == null ? Path.of("data", "admin-password") : generatedPasswordFile;
    maxFailedAttempts = maxFailedAttempts == null || maxFailedAttempts < 1 ? 10 : maxFailedAttempts;
    lockout = lockout == null ? Duration.ofMinutes(5) : lockout;
  }
}
