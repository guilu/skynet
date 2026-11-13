package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.definition.JudgeSpec;
import dev.skynet.controlplane.definition.JudgeSpec.Criterion;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cómo se decide si pasa el juez de una fase {@code verification} (VA-B), sin base de datos ni
 * motor: la salida estructurada que pide a cada juez, su veredicto y el de la fase.
 *
 * <ul>
 *   <li>Por juez: la media ponderada de sus notas (de 1 a 5). Suspende si no llega al umbral, si un
 *       criterio {@code required} saca menos de 3 o si encuentra algo {@code blocking}.
 *   <li>La fase pasa si aprueba la mayoría de los jueces y ninguno encuentra nada que bloquee. Su
 *       nota es la media de las medias.
 * </ul>
 */
final class JudgeVerdicts {

  /** Un hallazgo que hay que arreglar antes de fusionar. */
  static final String BLOCKING = "blocking";

  /** Un hallazgo que no bloquea. */
  static final String MINOR = "minor";

  private JudgeVerdicts() {}

  /**
   * La nota de un criterio.
   *
   * @param id el criterio
   * @param score de 1 a 5
   * @param reason la justificación del juez
   * @param weight su peso en la media
   * @param required si con menos de 3 suspende
   */
  record CriterionScore(String id, int score, String reason, int weight, boolean required) {}

  /**
   * Un problema concreto que encontró un juez.
   *
   * @param file el fichero, si lo dice
   * @param line la línea, si la sabe
   * @param severity {@link #BLOCKING} o {@link #MINOR}
   * @param text qué pasa y por qué
   * @param judge el juez que lo encontró
   * @param refuted si los escépticos lo refutaron (VA-C); {@code null} si no se intentó
   * @param refutation por qué: lo que dijo el primer escéptico que lo refutó (o que no)
   * @param known si se descartó por ya visto (RH-B), el desenlace con el que coincidió; si no,
   *     {@code null}
   */
  /** La clase de un hallazgo que no es de la lista (AP-A). */
  static final String OTHER = "otro";

  /**
   * Las clases de hallazgo (AP-A), con lo que se le explica al juez de cada una. Es una lista
   * cerrada para poder contar lo que se repite en un repositorio.
   */
  static final Map<String, String> CATEGORIES = categories();

  private static Map<String, String> categories() {
    Map<String, String> c = new LinkedHashMap<>();
    c.put("tests", "falta un test o el que hay no prueba lo que dice");
    c.put("caso-limite", "vacío, nulo, cero, desbordes o el primer y el último elemento");
    c.put("errores", "una excepción o un error que no se trata o se trata mal");
    c.put("entradas", "una entrada sin validar o un fallo de seguridad con ella");
    c.put("secretos", "una contraseña, un token o una clave expuestos o mal guardados");
    c.put("concurrencia", "carreras, bloqueos o estado compartido");
    c.put("rendimiento", "trabajo de más, consultas repetidas o algo que no escala");
    c.put("api", "un contrato que cambia o deja de ser compatible");
    c.put("datos", "migraciones, esquema o consultas a la base de datos");
    c.put("alcance", "cambia lo que la tarea no pedía, o le falta lo que pedía");
    c.put("estilo", "nombres, formato o duplicación");
    c.put("documentacion", "documentación que falta o que ya no es verdad");
    c.put(OTHER, "nada de lo anterior");
    return java.util.Collections.unmodifiableMap(c);
  }

  /** {@code category} si es de la lista, sin mayúsculas ni espacios; si no, {@link #OTHER}. */
  static String category(String category) {
    if (category == null) {
      return OTHER;
    }
    String c =
        category.strip().toLowerCase(java.util.Locale.ROOT).replace('_', '-').replace(' ', '-');
    return CATEGORIES.containsKey(c) ? c : OTHER;
  }

  record Finding(
      String file,
      Integer line,
      String severity,
      String text,
      String judge,
      Boolean refuted,
      String refutation,
      String known,
      String category) {

    Finding(String file, Integer line, String severity, String text, String judge) {
      this(file, line, severity, text, judge, null, null, null, null);
    }

    Finding(
        String file,
        Integer line,
        String severity,
        String text,
        String judge,
        Boolean refuted,
        String refutation,
        String known) {
      this(file, line, severity, text, judge, refuted, refutation, known, null);
    }

    Finding(
        String file,
        Integer line,
        String severity,
        String text,
        String judge,
        Boolean refuted,
        String refutation) {
      this(file, line, severity, text, judge, refuted, refutation, null);
    }

    /** Si bloquea: es {@code blocking} y no lo han refutado. */
    boolean blocking() {
      return BLOCKING.equals(severity) && !Boolean.TRUE.equals(refuted);
    }

    /** Si lo han refutado y se descarta. */
    boolean discarded() {
      return Boolean.TRUE.equals(refuted);
    }

    Finding withRefutation(boolean refuted, String refutation) {
      return new Finding(file, line, severity, text, judge, refuted, refutation, known, category);
    }

    /** El mismo hallazgo con su clase (AP-A), ya normalizada con {@link #category(String)}. */
    Finding withCategory(String category) {
      return new Finding(file, line, severity, text, judge, refuted, refutation, known, category);
    }

    /** Su clase (AP-A); {@code otro} si el juez no dijo ninguna de la lista. */
    String categoryOrOther() {
      return JudgeVerdicts.category(category);
    }

    /**
     * Ya se había visto (RH-B): coincide con uno {@code outcome} (refutado, aceptado o
     * irrelevante), así que se descarta antes de los escépticos con {@code reason}.
     */
    Finding withKnown(String outcome, String reason) {
      return new Finding(file, line, severity, text, judge, true, reason, outcome, category);
    }

    /** {@code fichero:línea}, {@code fichero} o {@code null}. */
    String location() {
      if (file == null || file.isBlank()) {
        return null;
      }
      return line == null ? file : file + ":" + line;
    }
  }

  /**
   * El veredicto de un juez en una ronda.
   *
   * @param judge el agente que juzga
   * @param lens su lente, o {@code null}
   * @param criteria sus notas, en el orden de la rúbrica
   * @param findings lo que encontró
   * @param summary su resumen
   * @param score su media ponderada, con dos decimales
   * @param passed si aprueba
   * @param reasons por qué suspende; vacío si aprueba
   */
  record Verdict(
      String judge,
      String lens,
      List<CriterionScore> criteria,
      List<Finding> findings,
      String summary,
      BigDecimal score,
      boolean passed,
      List<String> reasons) {}

  /**
   * El veredicto de la fase.
   *
   * @param score la media de las medias, con dos decimales
   * @param passed si pasa
   * @param approvals cuántos jueces aprueban
   * @param judges cuántos juzgaron
   * @param findings los hallazgos de todos: los que bloquean, los menores y los refutados
   * @param criteria la nota media de cada criterio entre los jueces
   */
  record Aggregate(
      BigDecimal score,
      boolean passed,
      int approvals,
      int judges,
      List<Finding> findings,
      Map<String, BigDecimal> criteria) {}

  /**
   * El JSON Schema de la salida de un juez. Con la rúbrica en un fichero, cada criterio dice además
   * su peso y si es obligatorio, como los lee el juez.
   */
  static String outputSchema(boolean rubricFile) {
    Map<String, Object> criterionProperties = new LinkedHashMap<>();
    criterionProperties.put("id", Map.of("type", "string"));
    criterionProperties.put("score", Map.of("type", "integer", "minimum", 1, "maximum", 5));
    criterionProperties.put("reason", Map.of("type", "string"));
    List<String> criterionRequired = new ArrayList<>(List.of("id", "score", "reason"));
    if (rubricFile) {
      criterionProperties.put("weight", Map.of("type", "integer", "minimum", 1, "maximum", 10));
      criterionProperties.put("required", Map.of("type", "boolean"));
      criterionRequired.addAll(List.of("weight", "required"));
    }
    Map<String, Object> finding = new LinkedHashMap<>();
    finding.put("type", "object");
    finding.put("additionalProperties", false);
    finding.put("required", List.of("file", "line", "severity", "category", "text"));
    Map<String, Object> findingProperties = new LinkedHashMap<>();
    findingProperties.put("file", Map.of("type", "string"));
    findingProperties.put("line", Map.of("type", List.of("integer", "null")));
    findingProperties.put("severity", Map.of("type", "string", "enum", List.of(BLOCKING, MINOR)));
    findingProperties.put(
        "category", Map.of("type", "string", "enum", List.copyOf(CATEGORIES.keySet())));
    findingProperties.put("text", Map.of("type", "string"));
    finding.put("properties", findingProperties);
    Map<String, Object> criterion = new LinkedHashMap<>();
    criterion.put("type", "object");
    criterion.put("additionalProperties", false);
    criterion.put("required", criterionRequired);
    criterion.put("properties", criterionProperties);
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("criteria", Map.of("type", "array", "items", criterion));
    properties.put("findings", Map.of("type", "array", "items", finding));
    properties.put("summary", Map.of("type", "string"));
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("additionalProperties", false);
    schema.put("required", List.of("criteria", "findings", "summary"));
    schema.put("properties", properties);
    return JsonMapper.shared().writeValueAsString(schema);
  }

  /**
   * El veredicto de un juez a partir de su salida estructurada. Un criterio de la rúbrica que no
   * puntúa cuenta como un 1: no se puede aprobar sin mirarlo.
   */
  static Verdict verdict(String judge, String lens, JudgeSpec spec, JsonNode output) {
    Map<String, JsonNode> given = new LinkedHashMap<>();
    for (JsonNode item : output.path("criteria")) {
      String id = text(item.path("id"));
      if (id != null && !given.containsKey(id)) {
        given.put(id, item);
      }
    }
    List<CriterionScore> criteria = new ArrayList<>();
    if (spec.rubricFile() != null) {
      given.forEach(
          (id, item) ->
              criteria.add(
                  new CriterionScore(
                      id,
                      clamp(item.path("score").asInt(1), 1, 5),
                      text(item.path("reason")),
                      clamp(item.path("weight").asInt(1), 1, 10),
                      item.path("required").asBoolean(false))));
    } else {
      for (Criterion c : spec.criteria()) {
        JsonNode item = given.get(c.id());
        criteria.add(
            item == null
                ? new CriterionScore(c.id(), 1, "El juez no lo puntuó", c.weight(), c.required())
                : new CriterionScore(
                    c.id(),
                    clamp(item.path("score").asInt(1), 1, 5),
                    text(item.path("reason")),
                    c.weight(),
                    c.required()));
      }
    }
    List<Finding> findings = new ArrayList<>();
    for (JsonNode item : output.path("findings")) {
      String text = text(item.path("text"));
      if (text == null) {
        continue;
      }
      String severity = MINOR.equals(text(item.path("severity"))) ? MINOR : BLOCKING;
      Integer line = item.path("line").isNumber() ? item.path("line").asInt() : null;
      findings.add(
          new Finding(text(item.path("file")), line, severity, text, judge)
              .withCategory(category(text(item.path("category")))));
    }
    return judged(judge, lens, criteria, findings, text(output.path("summary")), spec);
  }

  /**
   * El veredicto de un juez tal como se guardó en {@code stage_verdict} (con lo que se descartó por
   * ya visto, RH-B): sus notas y sus hallazgos, leídos otra vez.
   */
  static Verdict saved(
      String judge,
      String lens,
      JsonNode criteria,
      JsonNode findings,
      String summary,
      JudgeSpec spec) {
    List<CriterionScore> scores = new ArrayList<>();
    for (JsonNode c : criteria) {
      scores.add(
          new CriterionScore(
              text(c.path("id")),
              c.path("score").asInt(),
              text(c.path("reason")),
              c.path("weight").asInt(1),
              c.path("required").asBoolean(false)));
    }
    List<Finding> list = new ArrayList<>();
    for (JsonNode f : findings) {
      JsonNode refuted = f.path("refuted");
      list.add(
          new Finding(
              text(f.path("file")),
              f.path("line").isNumber() ? f.path("line").asInt() : null,
              text(f.path("severity")),
              text(f.path("text")),
              text(f.path("judge")),
              refuted.isBoolean() ? refuted.asBoolean() : null,
              text(f.path("refutation")),
              text(f.path("known")),
              text(f.path("category"))));
    }
    return judged(judge, lens, scores, list, summary, spec);
  }

  /** El veredicto de un juez con sus notas ya leídas. */
  static Verdict judged(
      String judge,
      String lens,
      List<CriterionScore> criteria,
      List<Finding> findings,
      String summary,
      JudgeSpec spec) {
    List<String> reasons = new ArrayList<>();
    BigDecimal score = weightedMean(criteria);
    if (criteria.isEmpty()) {
      reasons.add("No ha puntuado ningún criterio");
    } else if (score.compareTo(spec.threshold()) < 0) {
      reasons.add("La nota es " + format(score) + " y el umbral " + format(spec.threshold()));
    }
    for (CriterionScore c : criteria) {
      if (c.required() && c.score() < JudgeSpec.REQUIRED_MINIMUM) {
        reasons.add("`" + c.id() + "` es obligatorio y saca " + c.score());
      }
    }
    long blocking = findings.stream().filter(Finding::blocking).count();
    if (blocking > 0) {
      reasons.add(
          blocking == 1
              ? "Hay 1 hallazgo que bloquea"
              : "Hay " + blocking + " hallazgos que bloquean");
    }
    return new Verdict(
        judge,
        lens,
        List.copyOf(criteria),
        List.copyOf(findings),
        summary,
        score,
        reasons.isEmpty(),
        List.copyOf(reasons));
  }

  /**
   * El veredicto de la fase: pasa si aprueba la mayoría y nadie encuentra nada que bloquee. Con un
   * juez, manda él.
   */
  static Aggregate aggregate(List<Verdict> verdicts) {
    int approvals = (int) verdicts.stream().filter(Verdict::passed).count();
    List<Finding> findings = new ArrayList<>();
    verdicts.forEach(v -> v.findings().stream().filter(Finding::blocking).forEach(findings::add));
    verdicts.forEach(
        v ->
            v.findings().stream()
                .filter(f -> !f.blocking() && !f.discarded())
                .forEach(findings::add));
    verdicts.forEach(v -> v.findings().stream().filter(Finding::discarded).forEach(findings::add));
    boolean blocked = findings.stream().anyMatch(Finding::blocking);
    BigDecimal score =
        verdicts.isEmpty()
            ? BigDecimal.ZERO.setScale(2)
            : verdicts.stream()
                .map(Verdict::score)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(verdicts.size()), 2, RoundingMode.HALF_UP);
    Map<String, List<Integer>> byCriterion = new LinkedHashMap<>();
    for (Verdict v : verdicts) {
      for (CriterionScore c : v.criteria()) {
        byCriterion.computeIfAbsent(c.id(), k -> new ArrayList<>()).add(c.score());
      }
    }
    Map<String, BigDecimal> criteria = new LinkedHashMap<>();
    byCriterion.forEach(
        (id, scores) ->
            criteria.put(
                id,
                BigDecimal.valueOf(scores.stream().mapToInt(Integer::intValue).sum())
                    .divide(BigDecimal.valueOf(scores.size()), 2, RoundingMode.HALF_UP)));
    boolean passed = !verdicts.isEmpty() && approvals * 2 > verdicts.size() && !blocked;
    return new Aggregate(
        score, passed, approvals, verdicts.size(), List.copyOf(findings), Map.copyOf(criteria));
  }

  /** «3,8 / 5 · aprobado por 2 de 2», como en la web. */
  static String note(Aggregate aggregate) {
    return format(aggregate.score())
        + " / 5 · "
        + (aggregate.passed() ? "aprobado por " : "suspenso: aprueba ")
        + aggregate.approvals()
        + " de "
        + aggregate.judges();
  }

  // ---- Verificación adversarial (VA-C) --------------------------------------------------------

  /**
   * Un hallazgo que van a intentar refutar los escépticos.
   *
   * @param verdict el juez que lo encontró, por su orden en la ronda
   * @param index su orden entre los hallazgos de ese juez
   * @param finding el hallazgo
   */
  record Target(int verdict, int index, Finding finding) {}

  /**
   * Qué hallazgos se refutan en la ronda: los que bloquean, en el orden de los jueces, como mucho
   * {@link JudgeSpec#MAX_REFUTED_FINDINGS} y los que quepan en {@code budget} llamadas con {@code
   * refute} escépticos cada uno.
   */
  static List<Target> targets(List<Verdict> verdicts, int refute, int budget) {
    return cap(blockingTargets(verdicts), refute, budget);
  }

  /**
   * Los primeros de {@code all} que se refutan: como mucho {@link JudgeSpec#MAX_REFUTED_FINDINGS} y
   * los que quepan en {@code budget} llamadas con {@code refute} escépticos cada uno.
   */
  static List<Target> cap(List<Target> all, int refute, int budget) {
    int fit = refute <= 0 ? 0 : Math.max(0, budget) / refute;
    return all.subList(0, Math.min(all.size(), Math.min(JudgeSpec.MAX_REFUTED_FINDINGS, fit)));
  }

  /** Todos los hallazgos que bloquean de la ronda, en el orden de los jueces. */
  static List<Target> blockingTargets(List<Verdict> verdicts) {
    List<Target> all = new ArrayList<>();
    for (int v = 0; v < verdicts.size(); v++) {
      List<Finding> findings = verdicts.get(v).findings();
      for (int i = 0; i < findings.size(); i++) {
        if (findings.get(i).blocking()) {
          all.add(new Target(v, i, findings.get(i)));
        }
      }
    }
    return all;
  }

  /**
   * El aviso si no se refutan todos los hallazgos que bloquean (nada de recortes en silencio), o
   * {@code null} si se refutan todos.
   */
  static String trimmed(int refuted, int blocking, boolean budgetSpent) {
    if (refuted >= blocking) {
      return null;
    }
    return "Se han intentado refutar "
        + refuted
        + " de "
        + blocking
        + " hallazgos que bloquean"
        + (budgetSpent
            ? " (no quedan llamadas: como mucho " + JudgeSpec.MAX_AGENT_CALLS + " por fase)"
            : " (como mucho " + JudgeSpec.MAX_REFUTED_FINDINGS + " por ronda)")
        + "; los demás cuentan sin refutar";
  }

  /**
   * Lo que dijo un escéptico de un hallazgo.
   *
   * @param refuted si lo refuta
   * @param reason por qué
   */
  record Refutation(boolean refuted, String reason) {}

  /**
   * Si la mayoría de los escépticos lo refuta, el hallazgo se descarta. Un escéptico que no terminó
   * bien no lo refuta.
   */
  static Finding decide(Finding finding, List<Refutation> refutations, int skeptics) {
    long yes = refutations.stream().filter(Refutation::refuted).count();
    boolean refuted = skeptics > 0 && yes * 2 > skeptics;
    String reason =
        refutations.stream()
            .filter(r -> r.refuted() == refuted && r.reason() != null && !r.reason().isBlank())
            .map(Refutation::reason)
            .findFirst()
            .orElse(null);
    return finding.withRefutation(refuted, reason);
  }

  /** El veredicto del juez con sus hallazgos ya pasados por los escépticos. */
  static Verdict refuted(Verdict verdict, Map<Integer, Finding> decided, JudgeSpec spec) {
    List<Finding> findings = new ArrayList<>(verdict.findings());
    decided.forEach(findings::set);
    return judged(
        verdict.judge(), verdict.lens(), verdict.criteria(), findings, verdict.summary(), spec);
  }

  /** La salida estructurada de un escéptico. */
  static String refutationSchema() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("refuted", Map.of("type", "boolean"));
    properties.put("reason", Map.of("type", "string"));
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("additionalProperties", false);
    schema.put("required", List.of("refuted", "reason"));
    schema.put("properties", properties);
    return JsonMapper.shared().writeValueAsString(schema);
  }

  /** Lo que dijo un escéptico, o {@code null} si su salida no sirve. */
  static Refutation refutation(JsonNode output) {
    if (output == null || !output.isObject() || !output.path("refuted").isBoolean()) {
      return null;
    }
    return new Refutation(output.path("refuted").asBoolean(), text(output.path("reason")));
  }

  /** Lo que se le dice a la fase de {@code fix}: por qué suspendió y qué encontraron. */
  static String report(List<Verdict> verdicts, Aggregate aggregate) {
    StringBuilder out = new StringBuilder();
    out.append("Nota: ").append(note(aggregate)).append(".\n");
    List<Finding> blocking = aggregate.findings().stream().filter(Finding::blocking).toList();
    List<Finding> minor =
        aggregate.findings().stream().filter(f -> !f.blocking() && !f.discarded()).toList();
    if (!blocking.isEmpty()) {
      out.append("\n## Lo que hay que arreglar\n\n");
      blocking.forEach(f -> out.append(findingLine(f)).append('\n'));
    }
    List<String> low = new ArrayList<>();
    for (Verdict v : verdicts) {
      for (CriterionScore c : v.criteria()) {
        if (c.score() < JudgeSpec.REQUIRED_MINIMUM || (c.required() && c.score() < 4)) {
          low.add(
              "- `"
                  + c.id()
                  + "`: "
                  + c.score()
                  + " / 5"
                  + (c.reason() == null ? "" : ". " + c.reason())
                  + " ("
                  + v.judge()
                  + ")");
        }
      }
    }
    if (!low.isEmpty()) {
      out.append("\n## Criterios con nota baja\n\n");
      low.forEach(l -> out.append(l).append('\n'));
    }
    if (!minor.isEmpty()) {
      out.append("\n## Además (no bloquea)\n\n");
      minor.forEach(f -> out.append(findingLine(f)).append('\n'));
    }
    return out.toString().strip();
  }

  private static String findingLine(Finding f) {
    return "- "
        + (f.location() == null ? "" : "`" + f.location() + "`: ")
        + f.text()
        + " ("
        + f.judge()
        + ")";
  }

  /** La media ponderada con dos decimales; 0 si no hay criterios. */
  static BigDecimal weightedMean(List<CriterionScore> criteria) {
    int weights = criteria.stream().mapToInt(CriterionScore::weight).sum();
    if (weights == 0) {
      return BigDecimal.ZERO.setScale(2);
    }
    int total = criteria.stream().mapToInt(c -> c.score() * c.weight()).sum();
    return BigDecimal.valueOf(total).divide(BigDecimal.valueOf(weights), 2, RoundingMode.HALF_UP);
  }

  /** Una nota en español, con coma y sin ceros de más: {@code 3,8}, {@code 4}, {@code 3,75}. */
  static String format(BigDecimal value) {
    DecimalFormat format =
        new DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.of("es")));
    return format.format(value);
  }

  private static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }

  private static String text(JsonNode node) {
    if (node == null || !node.isString()) {
      return null;
    }
    String value = node.asString().strip();
    return value.isEmpty() ? null : value;
  }
}
