package dev.skynet.controlplane.workflow;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * El juez de una fase {@code verification} (VA-B), para la web: en qué ronda va, la nota de la fase
 * y el veredicto de cada juez en la última ronda juzgada.
 *
 * @param status {@code CHECKING}, {@code JUDGING}, {@code REFUTING}, {@code FIXING}, {@code PASSED}
 *     o {@code FAILED}
 * @param round la ronda en curso, desde 1
 * @param shownRound la ronda de {@code judgements}: la anterior mientras se corrige
 * @param maxRounds rondas de corrección como mucho; 0 sin {@code fix}
 * @param fix la fase que corrige, o {@code null}
 * @param threshold la media mínima
 * @param score la nota de la fase (la media de las medias), o {@code null} sin veredicto
 * @param passed si pasa, o {@code null} sin veredicto
 * @param approvals cuántos jueces aprueban
 * @param judges cuántos juzgaron
 * @param findings los hallazgos de todos, los que bloquean primero
 * @param judgements el veredicto de cada juez
 * @param error por qué falló o suspendió la última ronda
 * @param refute escépticos por hallazgo que bloquea (VA-C); 0 sin verificación adversarial
 * @param refuting cuántos hallazgos se están intentando refutar ahora
 * @param refuteNote el aviso si no se pudieron refutar todos (los topes), o {@code null}
 */
public record JudgeView(
    String status,
    int round,
    int shownRound,
    int maxRounds,
    String fix,
    BigDecimal threshold,
    BigDecimal score,
    Boolean passed,
    Integer approvals,
    Integer judges,
    List<Issue> findings,
    List<Judgement> judgements,
    String error,
    int refute,
    int refuting,
    String refuteNote) {

  /**
   * El veredicto de un juez.
   *
   * @param status {@code PENDING} mientras juzga, {@code DONE} o {@code FAILED}
   */
  public record Judgement(
      String judge,
      String lens,
      UUID agentRunId,
      String status,
      BigDecimal score,
      Boolean passed,
      List<Score> criteria,
      List<Issue> findings,
      String summary,
      String error) {}

  /** La nota de un criterio. */
  public record Score(String id, int score, String reason, int weight, boolean required) {}

  /**
   * Un hallazgo: {@code severity} es {@code blocking} o {@code minor}. {@code refuted} es si lo
   * refutaron los escépticos (VA-C), o {@code null} si no se intentó; {@code refutation}, por qué.
   */
  public record Issue(
      String file,
      Integer line,
      String severity,
      String text,
      String judge,
      Boolean refuted,
      String refutation) {}
}
