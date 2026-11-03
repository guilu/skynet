package dev.skynet.controlplane.workflow;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

/**
 * La descripción de la PR de una fase {@code github-pr} (EN-A): el texto de la fase, el trabajo y
 * las evidencias de lo que ha hecho el workflow hasta ahí (fases, comprobaciones y coste). Se
 * genera, no la escribe un agente: así no se inventa nada.
 */
final class PullRequestBody {

  /** GitHub admite 65.536 caracteres; se deja margen. */
  static final int MAX_LENGTH = 60_000;

  static final int MAX_DESCRIPTION = 2_000;
  static final int MAX_SUMMARY = 400;

  private PullRequestBody() {}

  /**
   * Una fase anterior, con su resumen ({@code summary} de su salida) si lo tiene.
   *
   * @param checks las comprobaciones de su verificación, p. ej. {@code tests ✅}
   * @param details el detalle en Markdown que va plegado debajo de la tabla (los pasos de la guía
   *     de pruebas, VA-D), o {@code null}
   */
  record StageLine(
      String key, String name, String status, String summary, List<String> checks, String details) {

    StageLine(String key, String name, String status, String summary, List<String> checks) {
      this(key, name, status, summary, checks, null);
    }
  }

  /** El detalle de una fase cabe holgado; lo que sobra se corta. */
  static final int MAX_DETAILS = 12_000;

  static String of(
      String intro,
      String workItemKey,
      String workItemTitle,
      String workItemDescription,
      List<StageLine> stages,
      BigDecimal costUsd,
      String publicUrl,
      UUID runId,
      String closes) {
    StringBuilder body = new StringBuilder();
    if (intro != null && !intro.isBlank()) {
      body.append(intro.strip()).append("\n\n");
    }
    body.append("## ")
        .append(workItemKey)
        .append(" - ")
        .append(oneLine(workItemTitle))
        .append('\n');
    if (workItemDescription != null && !workItemDescription.isBlank()) {
      body.append('\n');
      for (String line : cut(workItemDescription.strip(), MAX_DESCRIPTION).split("\n", -1)) {
        body.append("> ").append(line).append('\n');
      }
    }
    if (!stages.isEmpty()) {
      body.append(
          "\n## Evidencias\n\n| Fase | Estado | Resumen | Comprobaciones |\n|---|---|---|---|\n");
      for (StageLine stage : stages) {
        body.append("| ")
            .append(cell(stage.name() == null ? stage.key() : stage.name()))
            .append(" | ")
            .append(cell(stage.status()))
            .append(" | ")
            .append(cell(stage.summary() == null ? "" : cut(stage.summary(), MAX_SUMMARY)))
            .append(" | ")
            .append(cell(String.join(", ", stage.checks())))
            .append(" |\n");
      }
      for (StageLine stage : stages) {
        if (stage.details() != null && !stage.details().isBlank()) {
          body.append("\n<details><summary>")
              .append(cell(stage.name() == null ? stage.key() : stage.name()))
              .append("</summary>\n\n")
              .append(cut(stage.details().strip(), MAX_DETAILS))
              .append("\n\n</details>\n");
        }
      }
    }
    if (costUsd != null) {
      body.append("\n**Coste de los agentes:** ")
          .append(costUsd.setScale(2, RoundingMode.HALF_UP).toPlainString())
          .append(" US$\n");
    }
    if (closes != null) {
      body.append("\n").append(closes).append('\n');
    }
    body.append("\n---\nAbierta por Skynet");
    if (publicUrl != null && !publicUrl.isBlank()) {
      body.append(" · [ver la ejecución](")
          .append(publicUrl.replaceAll("/+$", ""))
          .append("/runs/")
          .append(runId)
          .append(')');
    }
    body.append(".\n");
    return cut(body.toString(), MAX_LENGTH);
  }

  /**
   * {@code Closes #n} si el trabajo viene de una issue de GitHub ({@code externalRef} {@code
   * github:owner/nombre#n}, DS-A): al fusionar la PR, GitHub cierra la issue. Si la issue es de
   * otro repositorio (un repositorio de órdenes), {@code Closes owner/nombre#n}. Si no, {@code
   * null}.
   */
  static String closing(String externalRef, String fullName) {
    if (externalRef == null) {
      return null;
    }
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile("github:([A-Za-z0-9-]+/[A-Za-z0-9._-]+)#(\\d+)")
            .matcher(externalRef);
    if (!m.matches()) {
      return null;
    }
    return "Closes " + (m.group(1).equalsIgnoreCase(fullName) ? "" : m.group(1)) + "#" + m.group(2);
  }

  /** Un texto que cabe en una celda de una tabla Markdown. */
  static String cell(String text) {
    return oneLine(text).replace("|", "\\|");
  }

  private static String oneLine(String text) {
    return text == null ? "" : text.replaceAll("\\s*\\R\\s*", " ").strip();
  }

  private static String cut(String text, int max) {
    return text.length() <= max ? text : text.substring(0, max - 1) + "…";
  }
}
