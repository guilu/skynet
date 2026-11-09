package dev.skynet.controlplane.definition;

import java.math.BigDecimal;
import java.util.List;

/**
 * El juez de una fase {@code verification} (VA-B): el bloque {@code judge} del YAML más {@code fix}
 * y {@code maxRounds} de la fase. Uno o varios agentes de solo lectura puntúan el cambio con una
 * rúbrica después de las comprobaciones; si suspende, corrige la fase de {@code fix}.
 *
 * @param agents los jueces, en paralelo; cada uno con su lente
 * @param rubric los criterios en el YAML; vacío si se leen de {@code rubricFile} o son los de
 *     siempre ({@link #DEFAULT_RUBRIC})
 * @param rubricFile fichero del repositorio con la rúbrica, que el juez lee del worktree; {@code
 *     null} si va en el YAML
 * @param threshold media ponderada mínima, de 1 a 5
 * @param fix fase {@code agent} que corrige si suspende; {@code null} si no se corrige
 * @param maxRounds rondas de corrección como mucho
 * @param refute escépticos por cada hallazgo {@code blocking} (VA-C); 0 sin verificación
 *     adversarial
 * @param refuteAgent el agente de los escépticos; {@code null} para el del primer juez
 * @param guide la guía de pruebas que ejecuta el agente (VA-D), o {@code null} en un juez con
 *     rúbrica; con guía, {@code agents} es solo ese agente
 * @param minRisk el nivel de riesgo (AU-A) desde el que se juzga (AU-C): por debajo, la fase pasa
 *     sin juez; 0 juzga siempre
 * @param refuteMinRisk el nivel desde el que corren los escépticos (AU-C); 0 siempre que haya
 *     {@code refute}
 */
public record JudgeSpec(
    List<Judge> agents,
    List<Criterion> rubric,
    String rubricFile,
    BigDecimal threshold,
    String fix,
    int maxRounds,
    int refute,
    String refuteAgent,
    GuideSpec guide,
    int minRisk,
    int refuteMinRisk) {

  /** Media ponderada mínima si no se dice otra. */
  public static final BigDecimal DEFAULT_THRESHOLD = new BigDecimal("3.5");

  /** Jueces por fase como mucho. */
  public static final int MAX_JUDGES = 5;

  /** Un criterio {@code required} con menos de esto suspende al juez, sea cual sea la media. */
  public static final int REQUIRED_MINIMUM = 3;

  /** Rondas de corrección si no se dice otra, como en {@code github-check}. */
  public static final int DEFAULT_MAX_ROUNDS = CiSpec.DEFAULT_MAX_ROUNDS;

  /** Las lentes: qué mira el juez además de la rúbrica. */
  public static final List<String> LENSES =
      List.of("correctness", "security", "performance", "reproduce", "completeness");

  /** La rúbrica si la fase no dice ninguna. */
  public static final List<Criterion> DEFAULT_RUBRIC =
      List.of(
          new Criterion("resuelve", "Resuelve la tarea tal y como se pidió", 3, true),
          new Criterion("tests", "Los tests cubren el cambio y sus casos límite", 2, false),
          new Criterion("alcance", "No cambia nada que la tarea no pida", 1, false));

  /** Escépticos por hallazgo como mucho. */
  public static final int MAX_REFUTE = 5;

  /** Hallazgos que se intentan refutar por ronda como mucho (VA-C). */
  public static final int MAX_REFUTED_FINDINGS = 8;

  /** Llamadas a agentes (jueces y escépticos) por fase como mucho, contando todas las rondas. */
  public static final int MAX_AGENT_CALLS = 20;

  /** Sin verificación adversarial (VA-B). */
  public JudgeSpec(
      List<Judge> agents,
      List<Criterion> rubric,
      String rubricFile,
      BigDecimal threshold,
      String fix,
      int maxRounds) {
    this(agents, rubric, rubricFile, threshold, fix, maxRounds, 0, null, null, 0, 0);
  }

  /** Un juez con rúbrica, sin guía (VA-C). */
  public JudgeSpec(
      List<Judge> agents,
      List<Criterion> rubric,
      String rubricFile,
      BigDecimal threshold,
      String fix,
      int maxRounds,
      int refute,
      String refuteAgent) {
    this(agents, rubric, rubricFile, threshold, fix, maxRounds, refute, refuteAgent, null, 0, 0);
  }

  /** Sin niveles de riesgo (VA-D). */
  public JudgeSpec(
      List<Judge> agents,
      List<Criterion> rubric,
      String rubricFile,
      BigDecimal threshold,
      String fix,
      int maxRounds,
      int refute,
      String refuteAgent,
      GuideSpec guide) {
    this(agents, rubric, rubricFile, threshold, fix, maxRounds, refute, refuteAgent, guide, 0, 0);
  }

  /**
   * Si con el nivel {@code tier} del cambio la fase pasa sin juez (AU-C). Un nivel sin medir
   * ({@code null}) se juzga; una guía, siempre.
   */
  public boolean skipsAt(Integer tier) {
    return guide == null && tier != null && tier < minRisk;
  }

  /** Si con el nivel {@code tier} corren los escépticos (AU-C). Sin medir, sí. */
  public boolean refutesAt(Integer tier) {
    return refute > 0 && (tier == null || tier >= refuteMinRisk);
  }

  /** Si es la guía de pruebas como fase (VA-D) y no un juez con rúbrica. */
  public boolean isGuide() {
    return guide != null;
  }

  public JudgeSpec {
    agents = List.copyOf(agents);
    rubric = rubric == null ? List.of() : List.copyOf(rubric);
    threshold = threshold == null ? DEFAULT_THRESHOLD : threshold;
  }

  /**
   * Los criterios con los que se juzga: los del YAML o los de siempre; vacío con un fichero o con
   * una guía.
   */
  public List<Criterion> criteria() {
    if (rubricFile != null || guide != null) {
      return List.of();
    }
    return rubric.isEmpty() ? DEFAULT_RUBRIC : rubric;
  }

  /** El agente de los escépticos: {@code refuteAgent} o el del primer juez. */
  public String skeptic() {
    return refuteAgent != null ? refuteAgent : agents.isEmpty() ? null : agents.getFirst().agent();
  }

  /**
   * Un juez.
   *
   * @param agent el agente con nombre del YAML
   * @param lens qué mira además de la rúbrica ({@link #LENSES}); {@code null} para todo
   */
  public record Judge(String agent, String lens) {}

  /**
   * Un criterio de la rúbrica.
   *
   * @param id cómo se nombra en los datos ({@code <fase>.criteria.<id>.score})
   * @param text lo que se puntúa
   * @param weight su peso en la media
   * @param required si con menos de {@link #REQUIRED_MINIMUM} suspende
   */
  public record Criterion(String id, String text, int weight, boolean required) {}

  /**
   * La guía de pruebas como fase (VA-D): el bloque {@code guide} de una fase {@code verification}.
   *
   * @param paths los globs de la guía, p. ej. {@code docs/tests/probar-*.md}: se ejecutan las que
   *     cambia el diff
   * @param agent el agente que la ejecuta, en una copia del worktree
   * @param skip las secciones que no se ejecutan, por cómo empieza su título (p. ej. {@code ## 4.})
   * @param skipIfMissing si sin guía en el diff la fase pasa ({@code ifMissing: skip}) en vez de
   *     fallar
   */
  public record GuideSpec(
      List<String> paths, String agent, List<String> skip, boolean skipIfMissing) {

    public GuideSpec {
      paths = List.copyOf(paths);
      skip = skip == null ? List.of() : List.copyOf(skip);
    }
  }
}
