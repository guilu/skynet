package dev.skynet.controlplane.definition;

import java.util.List;

/**
 * La PR que abre una fase {@code github-pr} (EN-A), del bloque {@code pullRequest} del YAML.
 *
 * @param title plantilla del título; {@code null} para {@code <clave> - <título del trabajo>}
 * @param body plantilla de un texto que va al principio de la descripción, antes de las evidencias;
 *     {@code null} si no hay
 * @param base rama de destino; {@code null} para la rama por defecto del repositorio en GitHub
 * @param draft si se abre como borrador
 * @param labels etiquetas que se le ponen
 */
public record PullRequestSpec(
    String title, String body, String base, boolean draft, List<String> labels) {

  public static final PullRequestSpec DEFAULT = new PullRequestSpec(null, null, null, false, null);

  public PullRequestSpec {
    labels = labels == null ? List.of() : List.copyOf(labels);
  }
}
