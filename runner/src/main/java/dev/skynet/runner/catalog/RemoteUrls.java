package dev.skynet.runner.catalog;

import java.util.regex.Pattern;

/** Quita las credenciales de la URL de un remoto antes de que salga de la máquina. */
final class RemoteUrls {

  /** {@code https://usuario:token@host/…} o {@code https://token@host/…}. */
  private static final Pattern HTTP_USERINFO =
      Pattern.compile("^(https?://)[^/@]*@", Pattern.CASE_INSENSITIVE);

  /** {@code ssh://usuario:secreto@host/…}: se queda el usuario, que no es secreto. */
  private static final Pattern SSH_PASSWORD =
      Pattern.compile("^(ssh://[^/:@]+):[^/@]*@", Pattern.CASE_INSENSITIVE);

  private RemoteUrls() {}

  static String withoutCredentials(String url) {
    if (url == null || url.isBlank()) {
      return null;
    }
    String clean = HTTP_USERINFO.matcher(url.strip()).replaceFirst("$1");
    return SSH_PASSWORD.matcher(clean).replaceFirst("$1@");
  }
}
