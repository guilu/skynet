package dev.skynet.controlplane.shared;

import java.util.Locale;

/**
 * Qué enseña una lista respecto a lo archivado: {@code archived=false} (por defecto) solo lo que no
 * lo está, {@code true} solo lo archivado y {@code all} todo.
 */
public enum Archived {
  EXCLUDE,
  ONLY,
  INCLUDE;

  /** El valor del parámetro {@code archived} de la API. */
  public static Archived of(String value) {
    if (value == null || value.isBlank()) {
      return EXCLUDE;
    }
    return switch (value.strip().toLowerCase(Locale.ROOT)) {
      case "false" -> EXCLUDE;
      case "true" -> ONLY;
      case "all" -> INCLUDE;
      default -> throw new InvalidRequestException("archived debe ser false, true o all: " + value);
    };
  }

  /**
   * Condición SQL sobre una o varias columnas {@code archived_at}: lo está si lo está cualquiera de
   * ellas (por ejemplo, la ejecución, su trabajo o su proyecto). Vacía con {@link #INCLUDE}.
   */
  public String sql(String... columns) {
    String none =
        String.join(" AND ", java.util.Arrays.stream(columns).map(c -> c + " IS NULL").toList());
    return switch (this) {
      case EXCLUDE -> " AND (" + none + ")";
      case ONLY -> " AND NOT (" + none + ")";
      case INCLUDE -> "";
    };
  }

  /** Si un elemento con esa fecha de archivado entra en la lista. */
  public boolean accepts(java.time.Instant archivedAt) {
    return switch (this) {
      case EXCLUDE -> archivedAt == null;
      case ONLY -> archivedAt != null;
      case INCLUDE -> true;
    };
  }
}
