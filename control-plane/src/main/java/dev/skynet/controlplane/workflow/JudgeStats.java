package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.shared.InvalidRequestException;
import dev.skynet.controlplane.shared.TimeSource;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * El ruido de cada juez (RH-C): de los hallazgos del registro, cuántos tumbó un escéptico o una
 * persona dijo que no aplicaban (ruido) y cuántos corrigió el agente (acierto), por proveedor,
 * modelo, juez y lente. Con menos de {@link #FEW} hallazgos la cifra no dice mucho.
 *
 * <p>EV-B: el desenlace de cada cambio que el juez aprobó, con el veredicto de su última ronda en
 * la fase. Se escapó si tiene escape (EV-A); se rechazó si después del juez alguien dijo {@code
 * REJECT} o {@code REQUEST_CHANGES} en una puerta; se quedó si su PR se fusionó; cerrada si se
 * cerró sin fusionar. Si no, aún no tiene desenlace y no cuenta. Y la calibración: esos desenlaces
 * por tramos de nota.
 */
@Component
class JudgeStats {

  /** Por debajo de esto, «pocos datos». */
  static final int FEW = 10;

  /** Los últimos hallazgos con ruido que se enseñan de cada fila. */
  static final int SAMPLES = 5;

  static final int MAX_DAYS = 365;

  static final String KEPT = "se-quedó";
  static final String ESCAPED = "escape";
  static final String REJECTED = "rechazado";
  static final String CLOSED = "cerrada";

  /** Los tramos de nota de la calibración: por debajo de 4, de 4 a 4,5 y de 4,5 en adelante. */
  static final double[] BANDS = {4.0, 4.5};

  private final JdbcClient jdbc;
  private final TimeSource time;

  JudgeStats(JdbcClient jdbc, TimeSource time) {
    this.jdbc = jdbc;
    this.time = time;
  }

  /** Una fila: un juez con su lente, en un proveedor y un modelo. */
  record JudgeRow(
      String provider,
      String model,
      String judge,
      String lens,
      int findings,
      int refuted,
      int irrelevant,
      int fixed,
      int accepted,
      int parked,
      int open,
      double noise,
      double hits,
      boolean fewData,
      List<Sample> noisy,
      int judged,
      int kept,
      int escaped,
      int rejected,
      int closed,
      boolean fewOutcomes,
      List<Band> calibration,
      List<EscapeSample> escapes) {}

  /**
   * Un tramo de nota (EV-B): de los cambios que el juez aprobó con una nota en {@code [from, to)},
   * cuántos tienen desenlace y cuántos se quedaron, se escaparon o se rechazaron.
   */
  record Band(
      String label,
      double from,
      Double to,
      int judged,
      int kept,
      int escaped,
      int rejected,
      int closed,
      double keptRatio) {}

  /** Un cambio que el juez aprobó y luego se escapó (EV-B), para ver qué se le pasó. */
  record EscapeSample(
      UUID runId,
      Integer pullRequestNumber,
      String origin,
      String seenRef,
      String note,
      Double score,
      Instant createdAt) {}

  /** Un hallazgo refutado o que no aplicaba, para ver de qué va el ruido. */
  record Sample(
      UUID id,
      UUID runId,
      String file,
      Integer line,
      String text,
      String outcome,
      String outcomeBy,
      String outcomeReason,
      Instant createdAt) {}

  private record Key(String provider, String model, String judge, String lens) {}

  List<JudgeRow> since(int days) {
    return since(days, null);
  }

  /** Lo mismo, solo con los hallazgos de la clase {@code category} (AP-A); {@code null}: todos. */
  List<JudgeRow> since(int days, String category) {
    if (days < 1 || days > MAX_DAYS) {
      throw new InvalidRequestException("`days` va de 1 a " + MAX_DAYS + ", no " + days);
    }
    if (category != null && !JudgeVerdicts.CATEGORIES.containsKey(category)) {
      throw new InvalidRequestException(
          "Clase desconocida: "
              + category
              + ". Admite: "
              + String.join(", ", JudgeVerdicts.CATEGORIES.keySet()));
    }
    Timestamp from = Timestamp.from(time.now().minus(Duration.ofDays(days)));
    Map<Key, List<Sample>> samples = new HashMap<>();
    jdbc.sql(
            """
            SELECT * FROM (
              SELECT f.provider, f.model, f.judge, f.lens, f.id, f.workflow_run_id, f.file, f.line,
                f.text, f.outcome, f.outcome_by, f.outcome_reason, f.created_at,
                row_number() OVER (PARTITION BY f.provider, f.model, f.judge, f.lens
                                   ORDER BY f.created_at DESC, f.id) AS n
              FROM finding f
              WHERE f.created_at >= :from AND f.outcome IN ('refutado', 'irrelevante')
                AND (CAST(:category AS text) IS NULL OR f.category = :category)
            ) s WHERE s.n <= :samples ORDER BY s.created_at DESC, s.id
            """)
        .param("from", from)
        .param("category", category)
        .param("samples", SAMPLES)
        .query(
            (rs, n) -> {
              samples.computeIfAbsent(key(rs), k -> new ArrayList<>()).add(sample(rs));
              return null;
            })
        .list();
    List<JudgeRow> rows =
        jdbc.sql(
                """
                SELECT f.provider, f.model, f.judge, f.lens, count(*) AS findings,
                  count(*) FILTER (WHERE f.outcome = 'refutado') AS refuted,
                  count(*) FILTER (WHERE f.outcome = 'irrelevante') AS irrelevant,
                  count(*) FILTER (WHERE f.outcome = 'corregido') AS fixed,
                  count(*) FILTER (WHERE f.outcome = 'aceptado') AS accepted,
                  count(*) FILTER (WHERE f.outcome = 'aparcado') AS parked,
                  count(*) FILTER (WHERE f.outcome = 'abierto') AS open
                FROM finding f
                WHERE f.created_at >= :from
                  AND (CAST(:category AS text) IS NULL OR f.category = :category)
                GROUP BY f.provider, f.model, f.judge, f.lens
                """)
            .param("from", from)
            .param("category", category)
            .query((rs, n) -> row(rs, samples.getOrDefault(key(rs), List.of())))
            .list();
    Map<Key, List<Judged>> judged = judged(from);
    List<JudgeRow> all = new ArrayList<>();
    Set<Key> seen = new HashSet<>();
    for (JudgeRow r : rows) {
      Key k = new Key(r.provider(), r.model(), r.judge(), r.lens());
      seen.add(k);
      all.add(withOutcomes(r, judged.getOrDefault(k, List.of())));
    }
    if (category == null) {
      // Un juez que aprobó sin decir nada también tiene desenlaces.
      judged.forEach(
          (k, list) -> {
            if (!seen.contains(k)) {
              all.add(
                  withOutcomes(
                      new JudgeRow(
                          k.provider(),
                          k.model(),
                          k.judge(),
                          k.lens(),
                          0,
                          0,
                          0,
                          0,
                          0,
                          0,
                          0,
                          0,
                          0,
                          true,
                          List.of(),
                          0,
                          0,
                          0,
                          0,
                          0,
                          true,
                          List.of(),
                          List.of()),
                      list));
            }
          });
    }
    return all.stream()
        .sorted(
            Comparator.comparingDouble(JudgeRow::noise)
                .reversed()
                .thenComparing(Comparator.comparingInt(JudgeRow::findings).reversed())
                .thenComparing(JudgeRow::judge))
        .toList();
  }

  /**
   * Un cambio que el juez aprobó en la última ronda de su fase, con su nota y su desenlace. Lo que
   * suspendió no cuenta: no se sabe qué habría pasado.
   */
  private record Judged(
      Double score,
      String outcome,
      UUID runId,
      Integer number,
      String origin,
      String seenRef,
      String note,
      Instant escapedAt) {}

  private Map<Key, List<Judged>> judged(Timestamp from) {
    Map<Key, List<Judged>> out = new HashMap<>();
    jdbc.sql(
            """
            WITH last AS (
              SELECT DISTINCT ON (v.stage_run_id, v.judge) v.stage_run_id, v.workflow_run_id,
                v.judge, v.lens, v.score, v.passed, v.updated_at, a.provider, a.model
              FROM stage_verdict v LEFT JOIN agent_run a ON a.id = v.agent_run_id
              WHERE v.status = 'DONE' AND v.created_at >= :from
              ORDER BY v.stage_run_id, v.judge, v.round DESC
            )
            SELECT l.provider, l.model, l.judge, l.lens, l.score, l.passed, l.workflow_run_id,
              pr.status AS pr_status, pr.number, e.origin, e.seen_ref, e.note,
              e.created_at AS escaped_at,
              EXISTS (
                SELECT 1 FROM approval_decision d
                JOIN approval_request q ON q.id = d.approval_request_id
                WHERE q.workflow_run_id = l.workflow_run_id
                  AND d.action IN ('REJECT', 'REQUEST_CHANGES') AND d.created_at > l.updated_at
              ) AS rejected
            FROM last l
            LEFT JOIN judge_escape e ON e.workflow_run_id = l.workflow_run_id
            LEFT JOIN LATERAL (
              SELECT p.status, p.number FROM github_pull_request p
              WHERE p.workflow_run_id = l.workflow_run_id ORDER BY p.created_at DESC LIMIT 1
            ) pr ON true
            ORDER BY e.created_at DESC NULLS LAST, l.updated_at DESC
            """)
        .param("from", from)
        .query(
            (rs, n) -> {
              // Cada juez con veredictos tiene su fila, aunque aún no haya desenlaces.
              List<Judged> list = out.computeIfAbsent(key(rs), k -> new ArrayList<>());
              String outcome = rs.getBoolean("passed") ? outcome(rs) : null;
              if (outcome != null) {
                Timestamp at = rs.getTimestamp("escaped_at");
                BigDecimal score = rs.getBigDecimal("score");
                list.add(
                    new Judged(
                        score == null ? null : score.doubleValue(),
                        outcome,
                        rs.getObject("workflow_run_id", UUID.class),
                        (Integer) rs.getObject("number"),
                        rs.getString("origin"),
                        rs.getString("seen_ref"),
                        rs.getString("note"),
                        at == null ? null : at.toInstant()));
              }
              return null;
            })
        .list();
    return out;
  }

  /** El desenlace de un cambio aprobado; {@code null} si aún no lo tiene. */
  private static String outcome(ResultSet rs) throws SQLException {
    if (rs.getString("origin") != null) return ESCAPED;
    if (rs.getBoolean("rejected")) return REJECTED;
    String pr = rs.getString("pr_status");
    if ("MERGED".equals(pr)) return KEPT;
    if ("CLOSED".equals(pr)) return CLOSED;
    return null;
  }

  private static JudgeRow withOutcomes(JudgeRow r, List<Judged> judged) {
    List<Band> calibration = new ArrayList<>();
    for (int i = 0; i <= BANDS.length; i++) {
      double lo = i == 0 ? 0 : BANDS[i - 1];
      Double hi = i == BANDS.length ? null : BANDS[i];
      List<Judged> in =
          judged.stream()
              .filter(j -> j.score() != null && j.score() >= lo && (hi == null || j.score() < hi))
              .toList();
      calibration.add(
          new Band(
              bandLabel(lo, hi),
              lo,
              hi,
              in.size(),
              count(in, KEPT),
              count(in, ESCAPED),
              count(in, REJECTED),
              count(in, CLOSED),
              ratio(count(in, KEPT), in.size())));
    }
    List<EscapeSample> escapes =
        judged.stream()
            .filter(j -> ESCAPED.equals(j.outcome()))
            .limit(SAMPLES)
            .map(
                j ->
                    new EscapeSample(
                        j.runId(),
                        j.number(),
                        j.origin(),
                        j.seenRef(),
                        j.note(),
                        j.score(),
                        j.escapedAt()))
            .toList();
    return new JudgeRow(
        r.provider(),
        r.model(),
        r.judge(),
        r.lens(),
        r.findings(),
        r.refuted(),
        r.irrelevant(),
        r.fixed(),
        r.accepted(),
        r.parked(),
        r.open(),
        r.noise(),
        r.hits(),
        r.fewData(),
        r.noisy(),
        judged.size(),
        count(judged, KEPT),
        count(judged, ESCAPED),
        count(judged, REJECTED),
        count(judged, CLOSED),
        judged.size() < FEW,
        calibration,
        escapes);
  }

  private static int count(List<Judged> judged, String outcome) {
    return (int) judged.stream().filter(j -> outcome.equals(j.outcome())).count();
  }

  /** «< 4», «4–4,5», «≥ 4,5». */
  static String bandLabel(double from, Double to) {
    if (from == 0) return "< " + number(to);
    if (to == null) return "≥ " + number(from);
    return number(from) + "–" + number(to);
  }

  private static String number(double n) {
    return n == Math.rint(n) ? String.valueOf((long) n) : String.valueOf(n).replace('.', ',');
  }

  private static Key key(ResultSet rs) throws SQLException {
    return new Key(
        rs.getString("provider"),
        rs.getString("model"),
        rs.getString("judge"),
        rs.getString("lens"));
  }

  private static JudgeRow row(ResultSet rs, List<Sample> noisy) throws SQLException {
    int findings = rs.getInt("findings");
    int refuted = rs.getInt("refuted");
    int irrelevant = rs.getInt("irrelevant");
    int fixed = rs.getInt("fixed");
    return new JudgeRow(
        rs.getString("provider"),
        rs.getString("model"),
        rs.getString("judge"),
        rs.getString("lens"),
        findings,
        refuted,
        irrelevant,
        fixed,
        rs.getInt("accepted"),
        rs.getInt("parked"),
        rs.getInt("open"),
        ratio(refuted + irrelevant, findings),
        ratio(fixed, findings),
        findings < FEW,
        noisy,
        0,
        0,
        0,
        0,
        0,
        true,
        List.of(),
        List.of());
  }

  private static Sample sample(ResultSet rs) throws SQLException {
    return new Sample(
        rs.getObject("id", UUID.class),
        rs.getObject("workflow_run_id", UUID.class),
        rs.getString("file"),
        (Integer) rs.getObject("line"),
        rs.getString("text"),
        rs.getString("outcome"),
        rs.getString("outcome_by"),
        rs.getString("outcome_reason"),
        rs.getTimestamp("created_at").toInstant());
  }

  /** Redondeado a tres decimales; sin hallazgos, 0. */
  static double ratio(int part, int whole) {
    return whole == 0 ? 0 : Math.round(1000.0 * part / whole) / 1000.0;
  }
}
