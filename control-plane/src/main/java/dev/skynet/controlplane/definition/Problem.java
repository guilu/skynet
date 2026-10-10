package dev.skynet.controlplane.definition;

/**
 * Algo que corregir en una definición.
 *
 * @param path dónde, como {@code stages[2].dependsOn[0]}; vacío para el documento entero
 * @param line línea (desde 1), o {@code null} si no se sabe
 * @param column columna (desde 1), o {@code null} si no se sabe
 */
public record Problem(
    Severity severity, String path, Integer line, Integer column, String message) {

  public enum Severity {
    /** La definición no es válida. */
    ERROR,
    /** Es válida, pero usa algo que el motor aún no ejecuta: no se puede publicar todavía. */
    UNSUPPORTED,
    /** Es válida y se puede publicar; solo es un aviso. */
    WARNING
  }
}
