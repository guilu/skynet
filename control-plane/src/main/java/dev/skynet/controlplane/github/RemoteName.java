package dev.skynet.controlplane.github;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * El host y el {@code owner/nombre} de la URL remota de un repositorio, en https, ssh o la forma
 * corta de scp ({@code git@github.com:owner/nombre.git}).
 */
record RemoteName(String host, String owner, String name) {

  private static final Pattern URL =
      Pattern.compile(
          "^(?:https?|ssh|git)://(?:[^@/]+@)?([^/:]+)(?::\\d+)?/([^/]+)/([^/]+?)(?:\\.git)?/?$",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern SCP =
      Pattern.compile("^(?:[^@/]+@)?([^/:]+):([^/]+)/([^/]+?)(?:\\.git)?/?$");

  static Optional<RemoteName> parse(String remoteUrl) {
    if (remoteUrl == null || remoteUrl.isBlank()) {
      return Optional.empty();
    }
    String url = remoteUrl.strip();
    Matcher m = URL.matcher(url);
    if (!m.matches()) {
      m = SCP.matcher(url);
      if (url.contains("://") || !m.matches()) {
        return Optional.empty();
      }
    }
    return Optional.of(new RemoteName(m.group(1).toLowerCase(Locale.ROOT), m.group(2), m.group(3)));
  }

  String fullName() {
    return owner + "/" + name;
  }
}
