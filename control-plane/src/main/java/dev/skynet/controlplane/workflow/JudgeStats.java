package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.shared.InvalidRequestException;
import dev.skynet.controlplane.shared.TimeSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * El ruido de cada juez (RH-C): de los hallazgos del registro, cuántos tumbó un escéptico o una
 * persona dijo que no aplicaban (ruido) y cuántos corrigió el agente (acierto), por proveedor,
 * modelo, juez y lente. Con menos de {@link #FEW} hallazgos la cifra no dice mucho.
 */
@Component
class JudgeStats {

  /** Por debajo de esto, «pocos datos». */
  static final int FEW = 10;

  /** Los últimos hallazgos con ruido que se enseñan de cada fila. */
  static final int SAMPLES = 5;

  static final int MAX_DAYS = 365;

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
      List<Sample> noisy) {}

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
    return rows.stream()
        .sorted(
            Comparator.comparingDouble(JudgeRow::noise)
                .reversed()
                .thenComparing(Comparator.comparingInt(JudgeRow::findings).reversed())
                .thenComparing(JudgeRow::judge))
        .toList();
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
        noisy);
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
