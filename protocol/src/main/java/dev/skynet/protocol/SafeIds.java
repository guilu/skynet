package dev.skynet.protocol;

import java.util.regex.Pattern;

/**
 * Lo que llega de fuera (la API, un CLI o el control plane) y acaba como argumento de {@code git} o
 * de un CLI, o como parte de una ruta: un id de sesión, una rama o un hash. Se valida en los dos
 * lados antes de usarlo, para que no se lea como una opción ({@code -…}) ni salga de su directorio
 * ({@code ../}).
 */
public final class SafeIds {

  /**
   * Ids de sesión de Claude Code, Codex y Antigravity: UUIDs, y en general letras, dígitos, - y _.
   */
  private static final Pattern SESSION_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,127}");

  /** Un hash de git (SHA-1 o SHA-256), abreviado o completo. */
  private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{7,64}");

  /** Lo que {@code git check-ref-format} nunca admite en un nombre de rama. */
  private static final Pattern BRANCH_FORBIDDEN =
      Pattern.compile("[\\x00-\\x20\\x7f~^:?*\\[\\\\]|\\.\\.|//|@\\{|(^|/)\\.|\\.lock(/|$)");

  private SafeIds() {}

  public static boolean isSessionId(String value) {
    return value != null && SESSION_ID.matcher(value).matches();
  }

  public static boolean isCommit(String value) {
    return value != null && COMMIT.matcher(value).matches();
  }

  /**
   * Si es un nombre de rama válido para {@code git check-ref-format --branch} que además no empieza
   * por {@code -} (no se confunde con una opción).
   */
  public static boolean isBranch(String value) {
    return value != null
        && !value.isEmpty()
        && value.length() <= 200
        && !value.startsWith("-")
        && !value.equals("@")
        && !value.endsWith("/")
        && !value.endsWith(".")
        && !BRANCH_FORBIDDEN.matcher(value).find();
  }
}
