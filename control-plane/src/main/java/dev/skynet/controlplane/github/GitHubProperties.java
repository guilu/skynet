package dev.skynet.controlplane.github;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * La GitHub App. Sin {@code appId}, {@code privateKeyFile} y {@code webhookSecret}, Skynet no habla
 * con GitHub.
 *
 * @param appId el id de la App ({@code SKYNET_GITHUB_APP_ID})
 * @param privateKeyFile ruta al {@code .pem} que da GitHub ({@code
 *     SKYNET_GITHUB_PRIVATE_KEY_FILE}); nunca se copia a la base de datos
 * @param webhookSecret el secreto con el que GitHub firma los webhooks ({@code
 *     SKYNET_GITHUB_WEBHOOK_SECRET})
 * @param apiUrl la API de GitHub ({@code SKYNET_GITHUB_API_URL}, por defecto la de github.com)
 * @param webUrl la web de ese GitHub, de la que son las URL remotas de los repositorios ({@code
 *     SKYNET_GITHUB_WEB_URL}); por defecto {@code https://github.com} con la API pública y, con
 *     GitHub Enterprise ({@code https://ghe.example.com/api/v3}), el host de la API
 * @param gitUrl de dónde cuelgan los repositorios al subir una rama ({@code
 *     SKYNET_GITHUB_GIT_URL}): por defecto {@code webUrl}. Las E2E lo apuntan a remotos locales
 *     ({@code file://}) para no subir nada a GitHub
 */
@ConfigurationProperties("skynet.github")
record GitHubProperties(
    String appId,
    String privateKeyFile,
    String webhookSecret,
    String apiUrl,
    String webUrl,
    String gitUrl) {

  static final String DEFAULT_API_URL = "https://api.github.com";

  GitHubProperties {
    appId = blankToNull(appId);
    privateKeyFile = blankToNull(privateKeyFile);
    webhookSecret = blankToNull(webhookSecret);
    apiUrl = blankToNull(apiUrl) == null ? DEFAULT_API_URL : stripSlash(apiUrl.strip());
    webUrl = blankToNull(webUrl) == null ? defaultWebUrl(apiUrl) : stripSlash(webUrl.strip());
    gitUrl = blankToNull(gitUrl) == null ? webUrl : stripSlash(gitUrl.strip());
  }

  /** Las variables que faltan para hablar con GitHub, por su nombre de entorno. */
  List<String> missing() {
    List<String> missing = new ArrayList<>();
    if (appId == null) {
      missing.add("SKYNET_GITHUB_APP_ID");
    }
    if (privateKeyFile == null) {
      missing.add("SKYNET_GITHUB_PRIVATE_KEY_FILE");
    }
    if (webhookSecret == null) {
      missing.add("SKYNET_GITHUB_WEBHOOK_SECRET");
    }
    return missing;
  }

  boolean configured() {
    return missing().isEmpty();
  }

  /** El host de las URL remotas de los repositorios de este GitHub. */
  String webHost() {
    return URI.create(webUrl).getHost();
  }

  private static String defaultWebUrl(String apiUrl) {
    URI api = URI.create(apiUrl);
    return "api.github.com".equalsIgnoreCase(api.getHost())
        ? "https://github.com"
        : api.getScheme() + "://" + api.getHost();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  private static String stripSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }
}
