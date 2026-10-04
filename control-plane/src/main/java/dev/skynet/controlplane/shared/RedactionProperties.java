package dev.skynet.controlplane.shared;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param secretEnv variables de entorno cuyos valores nunca deben persistirse (además de los
 *     formatos de clave conocidos)
 */
@ConfigurationProperties("skynet.redaction")
public record RedactionProperties(List<String> secretEnv) {

  static final List<String> DEFAULT_SECRET_ENV =
      List.of(
          "ANTHROPIC_API_KEY",
          "ANTHROPIC_AUTH_TOKEN",
          "SKYNET_RUNNER_REGISTRATION_TOKEN",
          "SKYNET_DB_PASSWORD",
          "GITHUB_TOKEN",
          "GH_TOKEN");

  public RedactionProperties {
    secretEnv = secretEnv == null ? DEFAULT_SECRET_ENV : List.copyOf(secretEnv);
  }
}
