package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.definition.JudgeSpec;
import dev.skynet.controlplane.workflow.JudgeVerdicts.Finding;
import dev.skynet.controlplane.workflow.JudgeVerdicts.Verdict;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * La guía de pruebas como fase (VA-D), sin base de datos ni motor: el prompt del agente que la
 * ejecuta, la salida estructurada que se le pide y cómo se decide. Se lleva como el veredicto de un
 * juez de un solo agente ({@link StageJudges}), así que tiene sus mismas rondas de {@code fix}.
 *
 * <ul>
 *   <li>Pasa si ningún paso falla. Un paso que no se pudo hacer ({@code blocked}: hace falta una
 *       máquina o una cuenta) es un aviso, no un fallo.
 *   <li>Sin guía en el cambio, falla con «No hay guía de pruebas en el cambio», salvo con {@code
 *       ifMissing: skip}.
 * </ul>
 */
final class GuideSteps {

  static final String PASSED = "passed";
  static final String FAILED = "failed";
  static final String BLOCKED = "blocked";
  static final String SKIPPED = "skipped";

  /** El motivo cuando el cambio no trae guía. */
  static final String MISSING = "No hay guía de pruebas en el cambio";

  private GuideSteps() {}

  /**
   * Un paso de la guía, como lo cuenta el agente.
   *
   * @param guide la guía
   * @param section el título de su sección
   * @param step qué dice el paso, resumido
   * @param status {@link #PASSED}, {@link #FAILED}, {@link #BLOCKED} o {@link #SKIPPED}
   * @param evidence la salida que lo demuestra, o por qué no se pudo
   */
  record Step(String guide, String section, String step, String status, String evidence) {}

  /**
   * Lo que devolvió el agente, ya leído.
   *
   * @param guides las guías que ejecutó (las que cambia el diff)
   * @param steps sus pasos, en orden
   * @param summary su resumen
   */
  record Run(List<String> guides, List<Step> steps, String summary) {

    long count(String status) {
      return steps.stream().filter(s -> status.equals(s.status())).count();
    }
  }

  /** El JSON Schema de la salida del agente que ejecuta la guía. */
  static String outputSchema() {
    Map<String, Object> stepProperties = new LinkedHashMap<>();
    stepProperties.put("guide", Map.of("type", "string"));
    stepProperties.put("section", Map.of("type", "string"));
    stepProperties.put("step", Map.of("type", "string"));
    stepProperties.put(
        "status", Map.of("type", "string", "enum", List.of(PASSED, FAILED, BLOCKED, SKIPPED)));
    stepProperties.put("evidence", Map.of("type", "string"));
    Map<String, Object> step = new LinkedHashMap<>();
    step.put("type", "object");
    step.put("additionalProperties", false);
    step.put("required", List.of("guide", "section", "step", "status", "evidence"));
    step.put("properties", stepProperties);
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("guides", Map.of("type", "array", "items", Map.of("type", "string")));
    properties.put("steps", Map.of("type", "array", "items", step));
    properties.put("summary", Map.of("type", "string"));
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("additionalProperties", false);
    schema.put("required", List.of("guides", "steps", "summary"));
    schema.put("properties", properties);
    return JsonMapper.shared().writeValueAsString(schema);
  }

  /**
   * El prompt del agente: qué guías busca, cómo las ejecuta y qué devuelve. No lleva lo que dijo el
   * autor del cambio: la guía tiene que valer por sí sola.
   */
  static String prompt(
      String workItemKey,
      String title,
      String baseCommit,
      JudgeSpec.GuideSpec guide,
      int round,
      String extra) {
    StringBuilder out = new StringBuilder();
    out.append(
        "Ejecuta la guía de pruebas del cambio paso a paso y di qué pasa en cada uno. Trabajas en"
            + " una copia del worktree: lo que crees o cambies aquí no llega al cambio.\n");
    out.append("\n## La tarea\n\n").append(workItemKey).append(" - ").append(title).append('\n');
    out.append("\n## La guía\n\n");
    out.append("Son los ficheros que cumplen ");
    out.append(String.join(" o ", guide.paths().stream().map(p -> "`" + p + "`").toList()));
    out.append(" y que cambia el diff");
    if (baseCommit != null) {
      out.append(" desde `")
          .append(baseCommit)
          .append("` (`git diff --name-only ")
          .append(baseCommit)
          .append("`)");
    }
    out.append(
        ". Si son varias, ejecútalas todas. Si no hay ninguna, devuelve `guides` vacío y no hagas nada más.\n");
    if (!guide.skip().isEmpty()) {
      out.append("\nNo ejecutes las secciones cuyo título empieza por ");
      out.append(String.join(", ", guide.skip().stream().map(s -> "«" + s + "»").toList()));
      out.append(": apunta sus pasos como `skipped`.\n");
    }
    if (round > 1) {
      out.append("\nEs la ronda ")
          .append(round)
          .append(
              ": se ha corregido lo que falló en la anterior. Ejecuta la guía entera otra vez.\n");
    }
    out.append(
        "\n## Cómo\n\n"
            + "- Sigue la guía tal y como está escrita, en orden, sin saltarte pasos ni cambiarla.\n"
            + "- Cada paso es `passed` si pasa lo que la guía dice que tiene que pasar, `failed` si"
            + " no, `blocked` si no se puede hacer aquí (hace falta otra máquina, una cuenta o un"
            + " servicio que no hay) y `skipped` si es de una sección que no se ejecuta.\n"
            + "- En `evidence`, la salida que lo demuestra (lo justo: las líneas que importan) o,"
            + " si no pasa o no se pudo, qué salió y qué se esperaba.\n"
            + "- No arregles nada para que pase: si falla, es un `failed`.\n");
    if (extra != null && !extra.isBlank()) {
      out.append("\n## Indicaciones\n\n").append(extra.strip()).append('\n');
    }
    out.append(
        "\nDevuelve la salida estructurada: `guides` (las rutas que has ejecutado), `steps` (guide,"
            + " section, step, status y evidence de cada paso) y `summary` (dos o tres frases).");
    return out.toString();
  }

  /** Lo que devolvió el agente; un paso con un estado desconocido cuenta como fallido. */
  static Run run(JsonNode output) {
    List<String> guides = new ArrayList<>();
    for (JsonNode g : output.path("guides")) {
      String text = text(g);
      if (text != null && !guides.contains(text)) {
        guides.add(text);
      }
    }
    List<Step> steps = new ArrayList<>();
    for (JsonNode s : output.path("steps")) {
      String status = text(s.path("status"));
      if (!List.of(PASSED, FAILED, BLOCKED, SKIPPED).contains(status)) {
        status = FAILED;
      }
      steps.add(
          new Step(
              text(s.path("guide")),
              text(s.path("section")),
              text(s.path("step")),
              status,
              text(s.path("evidence"))));
    }
    return new Run(List.copyOf(guides), List.copyOf(steps), text(output.path("summary")));
  }

  /**
   * El veredicto, como el de un juez: los pasos que fallan son hallazgos que bloquean, y la nota es
   * la parte de pasos que pasan (de los que se ejecutaron), de 1 a 5.
   */
  static Verdict verdict(String agent, JudgeSpec.GuideSpec guide, Run run) {
    List<Finding> findings = new ArrayList<>();
    for (Step s : run.steps()) {
      if (FAILED.equals(s.status())) {
        findings.add(
            new Finding(
                s.guide(),
                null,
                JudgeVerdicts.BLOCKING,
                "Falla el paso «"
                    + s.step()
                    + "» ("
                    + s.section()
                    + ")"
                    + (s.evidence() == null ? "" : ": " + s.evidence()),
                agent));
      } else if (BLOCKED.equals(s.status())) {
        findings.add(
            new Finding(
                s.guide(),
                null,
                JudgeVerdicts.MINOR,
                "No se pudo hacer el paso «"
                    + s.step()
                    + "» ("
                    + s.section()
                    + ")"
                    + (s.evidence() == null ? "" : ": " + s.evidence()),
                agent));
      }
    }
    List<String> reasons = new ArrayList<>();
    boolean missing = run.guides().isEmpty();
    if (missing && !guide.skipIfMissing()) {
      reasons.add(MISSING);
    } else if (!missing && run.steps().isEmpty()) {
      reasons.add("La guía no tiene ningún paso");
    }
    long failed = run.count(FAILED);
    if (failed > 0) {
      reasons.add(
          failed == 1 ? "Falla 1 paso de la guía" : "Fallan " + failed + " pasos de la guía");
    }
    return new Verdict(
        agent,
        "guide",
        List.of(),
        List.copyOf(findings),
        run.summary(),
        score(run, missing && guide.skipIfMissing()),
        reasons.isEmpty(),
        List.copyOf(reasons));
  }

  /** 5 si pasan todos los pasos que se ejecutaron; 1 si no pasa ninguno. */
  private static BigDecimal score(Run run, boolean skipped) {
    long passed = run.count(PASSED);
    long ran = passed + run.count(FAILED);
    if (skipped || ran == 0) {
      return skipped ? new BigDecimal("5.00") : BigDecimal.ONE.setScale(2);
    }
    return BigDecimal.ONE
        .add(
            BigDecimal.valueOf(4 * passed).divide(BigDecimal.valueOf(ran), 2, RoundingMode.HALF_UP))
        .setScale(2, RoundingMode.HALF_UP);
  }

  /** «3 de 3 pasos pasan», «2 de 3 pasos pasan · 1 no se pudo», «No hay guía…». */
  static String note(Run run, JudgeSpec.GuideSpec guide) {
    if (run.guides().isEmpty()) {
      return guide.skipIfMissing() ? "Sin guía en el cambio: se omite" : MISSING;
    }
    long passed = run.count(PASSED);
    long ran = passed + run.count(FAILED) + run.count(BLOCKED);
    StringBuilder out =
        new StringBuilder().append(passed).append(" de ").append(ran).append(" pasos pasan");
    long blocked = run.count(BLOCKED);
    if (blocked > 0) {
      out.append(" · ").append(blocked).append(blocked == 1 ? " no se pudo" : " no se pudieron");
    }
    long skipped = run.count(SKIPPED);
    if (skipped > 0) {
      out.append(" · ").append(skipped).append(skipped == 1 ? " saltado" : " saltados");
    }
    return out.toString();
  }

  /** La evidencia de la guía en Markdown, para el artefacto {@code guia.md} y la PR. */
  static String markdown(Run run, JudgeSpec.GuideSpec guide) {
    StringBuilder out = new StringBuilder("# Guía de pruebas\n\n");
    out.append(note(run, guide)).append(".\n");
    if (!run.guides().isEmpty()) {
      out.append("\nGuías: ")
          .append(String.join(", ", run.guides().stream().map(g -> "`" + g + "`").toList()))
          .append(".\n");
    }
    String section = null;
    for (Step s : run.steps()) {
      if (s.section() != null && !s.section().equals(section)) {
        section = s.section();
        out.append("\n## ").append(section.replaceFirst("^#+\\s*", "")).append("\n\n");
      }
      out.append("- ")
          .append(icon(s.status()))
          .append(' ')
          .append(s.step() == null ? "(sin descripción)" : s.step());
      if (s.evidence() != null && !SKIPPED.equals(s.status())) {
        out.append("\n\n  ```\n  ")
            .append(s.evidence().strip().replace("\n", "\n  "))
            .append("\n  ```");
      }
      out.append('\n');
    }
    if (run.summary() != null) {
      out.append("\n").append(run.summary()).append('\n');
    }
    return out.toString();
  }

  private static String icon(String status) {
    return switch (status) {
      case PASSED -> "✓";
      case FAILED -> "✗";
      case BLOCKED -> "⚠";
      default -> "–";
    };
  }

  private static String text(JsonNode node) {
    if (node == null || !node.isString()) {
      return null;
    }
    String value = node.asString().strip();
    return value.isEmpty() ? null : value;
  }
}
