package dev.skynet.controlplane.workflow;

import dev.skynet.controlplane.shared.InvalidRequestException;
import dev.skynet.controlplane.shared.NotFoundException;
import dev.skynet.controlplane.shared.TimeSource;
import dev.skynet.controlplane.workflow.JudgeVerdicts.Finding;
import dev.skynet.controlplane.workflow.JudgeVerdicts.Verdict;
import dev.skynet.protocol.StageStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * El registro de hallazgos (RH-A): cada hallazgo de un juez con su huella y su desenlace.
 *
 * <p>La huella es el repositorio, el fichero, la lente y las palabras del texto, sin la línea, que
 * se mueve. Dos hallazgos son el mismo si coinciden el fichero y la lente y sus palabras se parecen
 * (Jaccard) al menos {@code skynet.findings.similarity}. Dentro de una fase, el mismo hallazgo en
 * rondas seguidas es una sola fila: {@code repeats} cuenta las veces que volvió.
 *
 * <p>Los desenlaces los pone el motor ({@link #OPEN}, {@link #REFUTED}, {@link #FIXED} y {@link
 * #PARKED}) o una persona ({@link #ACCEPTED} e {@link #IRRELEVANT}). Solo registra: no cambia lo
 * que ve el juez ni lo que bloquea.
 */
@Component
class Findings {

  static final String OPEN = "abierto";
  static final String REFUTED = "refutado";
  static final String FIXED = "corregido";
  static final String PARKED = "aparcado";
  static final String ACCEPTED = "aceptado";
  static final String IRRELEVANT = "irrelevante";

  static final String JUDGE = "judge";
  static final String DELTA = "delta";

  /** Quien pone los desenlaces del motor. */
  static final String ACTOR = "skynet";

  private static final Pattern CODE = Pattern.compile("`[^`]*`");
  private static final Pattern MARKS = Pattern.compile("\\p{M}+");
  private static final Pattern NOT_LETTER = Pattern.compile("[^a-z]+");

  /** Palabras de cuatro letras o más que no dicen nada del hallazgo. */
  private static final Set<String> STOPWORDS =
      Set.of(
          "para", "como", "este", "esta", "esto", "estos", "estas", "pero", "porque", "cuando",
          "donde", "sobre", "entre", "desde", "hasta", "tiene", "tienen", "puede", "pueden", "debe",
          "deben", "deberia", "hace", "hacer", "otro", "otra", "otros", "otras", "solo", "tambien",
          "todo", "toda", "todos", "todas", "aqui", "cada", "sino", "segun", "that", "this", "with",
          "from", "should", "would", "could", "have", "there", "which", "when", "where", "into",
          "than", "then", "they", "them", "does", "will", "been", "being", "were", "also", "only",
          "some", "such", "here", "what");

  private final JdbcClient jdbc;
  private final RunTransitions transitions;
  private final TimeSource time;
  private final double similarity;

  Findings(
      JdbcClient jdbc,
      RunTransitions transitions,
      TimeSource time,
      @Value("${skynet.findings.similarity:0.5}") double similarity) {
    this.jdbc = jdbc;
    this.transitions = transitions;
    this.time = time;
    this.similarity = similarity;
  }

  // ---- Huella -----------------------------------------------------------------------------------

  /**
   * Las palabras de un hallazgo: en minúsculas, sin tildes, sin números, sin lo que va entre
   * comillas invertidas y sin palabras vacías; las de cuatro letras o más, sin repetir y en orden.
   */
  static List<String> words(String text) {
    if (text == null) {
      return List.of();
    }
    String plain = CODE.matcher(text).replaceAll(" ");
    plain = MARKS.matcher(Normalizer.normalize(plain, Normalizer.Form.NFD)).replaceAll("");
    plain = NOT_LETTER.matcher(plain.toLowerCase(Locale.ROOT)).replaceAll(" ");
    TreeSet<String> words = new TreeSet<>();
    for (String word : plain.strip().split(" ")) {
      if (word.length() >= 4 && !STOPWORDS.contains(word)) {
        words.add(word);
      }
    }
    return List.copyOf(words);
  }

  /** El hash de las palabras: igual si las palabras son las mismas. */
  static String fingerprint(List<String> words) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of()
          .formatHex(digest.digest(String.join(" ", words).getBytes(StandardCharsets.UTF_8)))
          .substring(0, 16);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Lo que se parecen dos conjuntos de palabras (Jaccard), de 0 a 1. */
  static double similarity(List<String> a, List<String> b) {
    if (a.isEmpty() && b.isEmpty()) {
      return 1;
    }
    Set<String> union = new HashSet<>(a);
    union.addAll(b);
    Set<String> common = new HashSet<>(a);
    common.retainAll(new HashSet<>(b));
    return (double) common.size() / union.size();
  }

  /** Si dos hallazgos son el mismo: mismo fichero y lente, y palabras parecidas. */
  boolean same(String file, String lens, List<String> words, Known known) {
    return same(file, known.file())
        && lensOf(lens).equals(lensOf(known.lens()))
        && similarity(words, known.words()) >= similarity;
  }

  private static boolean same(String a, String b) {
    return blank(a) ? blank(b) : a.equals(b);
  }

  private static boolean blank(String text) {
    return text == null || text.isBlank();
  }

  private static String lensOf(String lens) {
    return lens == null ? "general" : lens;
  }

  /** Un hallazgo ya guardado, con lo que hace falta para compararlo. */
  record Known(UUID id, UUID firstSeenId, String file, String lens, List<String> words) {}

  // ---- Lo que pone el motor ---------------------------------------------------------------------

  /**
   * Guarda los hallazgos de la ronda {@code round} de la fase, ya con lo que dijeron los
   * escépticos. Lo abierto de rondas anteriores que vuelve a salir se actualiza (y sube {@code
   * repeats}); lo que no vuelve se ha corregido. Si la ronda ya se guardó, no hace nada.
   */
  void record(UUID stageRunId, int round, String source, List<Verdict> verdicts, Instant now) {
    Context context =
        jdbc.sql(
                "SELECT s.workflow_run_id, w.repository_id FROM stage_run s JOIN workflow_run w ON"
                    + " w.id = s.workflow_run_id WHERE s.id = ?")
            .param(stageRunId)
            .query(
                (rs, n) ->
                    new Context(
                        rs.getObject("workflow_run_id", UUID.class),
                        rs.getObject("repository_id", UUID.class)))
            .single();
    boolean recorded =
        jdbc.sql(
                "SELECT EXISTS (SELECT 1 FROM finding WHERE stage_run_id = ? AND round = ? AND"
                    + " source = ?)")
            .params(stageRunId, round, source)
            .query(Boolean.class)
            .single();
    if (recorded) {
      return;
    }
    Map<String, Agent> agents = new HashMap<>();
    jdbc.sql(
            "SELECT v.judge, v.agent_run_id, a.provider, a.model FROM stage_verdict v LEFT JOIN"
                + " agent_run a ON a.id = v.agent_run_id WHERE v.stage_run_id = ? AND v.round = ?")
        .params(stageRunId, round)
        .query(
            (rs, n) ->
                agents.put(
                    rs.getString("judge"),
                    new Agent(
                        rs.getObject("agent_run_id", UUID.class),
                        rs.getString("provider"),
                        rs.getString("model"))))
        .list();
    List<Known> previous =
        jdbc.sql(
                "SELECT id, first_seen_id, file, lens, words FROM finding WHERE stage_run_id = ?"
                    + " AND source = ? AND outcome = ? AND round < ? ORDER BY created_at")
            .params(stageRunId, source, OPEN, round)
            .query(Findings::known)
            .list();
    Set<UUID> repeated = new HashSet<>();
    for (Verdict verdict : verdicts) {
      Agent agent = agents.getOrDefault(verdict.judge(), new Agent(null, null, null));
      for (Finding finding : verdict.findings()) {
        List<String> words = words(finding.text());
        Known match =
            previous.stream()
                .filter(k -> !repeated.contains(k.id()))
                .filter(k -> same(finding.file(), verdict.lens(), words, k))
                .findFirst()
                .orElse(null);
        if (match != null) {
          repeated.add(match.id());
          repeat(match.id(), round, verdict, agent, finding, words, now);
        } else {
          insert(context, stageRunId, round, source, verdict, agent, finding, words, now);
        }
      }
    }
    for (Known known : previous) {
      if (!repeated.contains(known.id())) {
        jdbc.sql(
                "UPDATE finding SET outcome = :outcome, outcome_by = :by, outcome_reason ="
                    + " :reason, outcome_at = :now, updated_at = :now WHERE id = :id")
            .param("outcome", FIXED)
            .param("by", ACTOR)
            .param("reason", "La ronda " + round + " ya no lo señala")
            .param("now", Timestamp.from(now))
            .param("id", known.id())
            .update();
      }
    }
  }

  /** La fase ha terminado: lo que sigue abierto queda aparcado. */
  void park(UUID stageRunId, Instant now) {
    jdbc.sql(
            "UPDATE finding SET outcome = :parked, outcome_by = :by, outcome_reason = :reason,"
                + " outcome_at = :now, updated_at = :now WHERE stage_run_id = :stage AND outcome ="
                + " :open")
        .param("parked", PARKED)
        .param("by", ACTOR)
        .param("reason", "La fase terminó sin corregirlo")
        .param("now", Timestamp.from(now))
        .param("stage", stageRunId)
        .param("open", OPEN)
        .update();
  }

  private void repeat(
      UUID id,
      int round,
      Verdict verdict,
      Agent agent,
      Finding finding,
      List<String> words,
      Instant now) {
    boolean refuted = finding.discarded();
    jdbc.sql(
            """
            UPDATE finding SET round = :round, judge = :judge, lens = :lens, agent_run_id = :agent,
              provider = :provider, model = :model, line = :line, severity = :severity,
              text = :text, words = CAST(:words AS text[]), fingerprint = :fingerprint,
              repeats = repeats + 1, outcome = :outcome, outcome_by = :by,
              outcome_reason = :reason, outcome_at = :at, updated_at = :now
            WHERE id = :id
            """)
        .param("round", round)
        .param("judge", verdict.judge())
        .param("lens", verdict.lens())
        .param("agent", agent.id())
        .param("provider", agent.provider())
        .param("model", agent.model())
        .param("line", finding.line())
        .param("severity", finding.severity())
        .param("text", finding.text())
        .param("words", array(words))
        .param("fingerprint", fingerprint(words))
        .param("outcome", refuted ? REFUTED : OPEN)
        .param("by", refuted ? ACTOR : null)
        .param("reason", refuted ? finding.refutation() : null)
        .param("at", refuted ? Timestamp.from(now) : null)
        .param("now", Timestamp.from(now))
        .param("id", id)
        .update();
  }

  private void insert(
      Context context,
      UUID stageRunId,
      int round,
      String source,
      Verdict verdict,
      Agent agent,
      Finding finding,
      List<String> words,
      Instant now) {
    boolean refuted = finding.discarded();
    UUID firstSeen = firstSeen(context.repositoryId(), stageRunId, finding, verdict.lens(), words);
    jdbc.sql(
            """
            INSERT INTO finding (id, repository_id, workflow_run_id, stage_run_id, round, source,
              judge, lens, agent_run_id, provider, model, file, line, severity, text, words,
              fingerprint, outcome, outcome_by, outcome_reason, outcome_at, first_seen_id,
              created_at, updated_at)
            VALUES (:id, :repository, :run, :stage, :round, :source, :judge, :lens, :agent,
              :provider, :model, :file, :line, :severity, :text, CAST(:words AS text[]),
              :fingerprint, :outcome, :by, :reason, :at, :firstSeen, :now, :now)
            """)
        .param("id", UUID.randomUUID())
        .param("repository", context.repositoryId())
        .param("run", context.runId())
        .param("stage", stageRunId)
        .param("round", round)
        .param("source", source)
        .param("judge", verdict.judge())
        .param("lens", verdict.lens())
        .param("agent", agent.id())
        .param("provider", agent.provider())
        .param("model", agent.model())
        .param("file", blank(finding.file()) ? null : finding.file())
        .param("line", finding.line())
        .param("severity", finding.severity())
        .param("text", finding.text() == null ? "" : finding.text())
        .param("words", array(words))
        .param("fingerprint", fingerprint(words))
        .param("outcome", refuted ? REFUTED : OPEN)
        .param("by", refuted ? ACTOR : null)
        .param("reason", refuted ? finding.refutation() : null)
        .param("at", refuted ? Timestamp.from(now) : null)
        .param("firstSeen", firstSeen)
        .param("now", Timestamp.from(now))
        .update();
  }

  /** La primera vez que salió este hallazgo en el repositorio, en otra fase; o {@code null}. */
  private UUID firstSeen(
      UUID repositoryId, UUID stageRunId, Finding finding, String lens, List<String> words) {
    if (repositoryId == null) {
      return null;
    }
    String file = blank(finding.file()) ? null : finding.file();
    List<Known> candidates =
        jdbc.sql(
                "SELECT id, first_seen_id, file, lens, words FROM finding WHERE repository_id = ?"
                    + " AND file IS NOT DISTINCT FROM ? AND stage_run_id <> ? ORDER BY created_at"
                    + " LIMIT 200")
            .params(repositoryId, file, stageRunId)
            .query(Findings::known)
            .list();
    return candidates.stream()
        .filter(k -> same(file, lens, words, k))
        .map(k -> k.firstSeenId() == null ? k.id() : k.firstSeenId())
        .findFirst()
        .orElse(null);
  }

  /** Un literal de array de Postgres: las palabras solo llevan letras de la a a la z. */
  private static String array(List<String> words) {
    return "{" + String.join(",", words) + "}";
  }

  private static Known known(ResultSet rs, int n) throws SQLException {
    return new Known(
        rs.getObject("id", UUID.class),
        rs.getObject("first_seen_id", UUID.class),
        rs.getString("file"),
        rs.getString("lens"),
        words(rs.getArray("words")));
  }

  private static List<String> words(Array array) throws SQLException {
    if (array == null) {
      return List.of();
    }
    Object[] values = (Object[]) array.getArray();
    List<String> words = new ArrayList<>(values.length);
    for (Object value : values) {
      words.add(String.valueOf(value));
    }
    return words;
  }

  private record Context(UUID runId, UUID repositoryId) {}

  private record Agent(UUID id, String provider, String model) {}

  // ---- Lo que ve y decide la persona ------------------------------------------------------------

  /** Los hallazgos de la ejecución, por fase y en el orden en que salieron. */
  @Transactional(readOnly = true)
  List<FindingView> ofRun(UUID runId) {
    if (!jdbc.sql("SELECT EXISTS (SELECT 1 FROM workflow_run WHERE id = ?)")
        .param(runId)
        .query(Boolean.class)
        .single()) {
      throw new NotFoundException("Ejecución", runId);
    }
    return jdbc.sql(SELECT + " WHERE f.workflow_run_id = ? ORDER BY f.created_at, f.id")
        .param(runId)
        .query(Findings::view)
        .list();
  }

  /**
   * Una persona dice qué es un hallazgo: {@link #ACCEPTED} (es real, pero intencionado), {@link
   * #IRRELEVANT} (el juez se equivocó) u {@link #OPEN} para deshacerlo, que lo deja abierto si la
   * fase sigue o aparcado si ya terminó.
   */
  @Transactional
  FindingView decide(UUID id, String outcome, String reason, String actor) {
    FindingView current =
        jdbc.sql(SELECT + " WHERE f.id = ?")
            .param(id)
            .query(Findings::view)
            .optional()
            .orElseThrow(() -> new NotFoundException("Hallazgo", id));
    String wanted = outcome == null ? "" : outcome.strip().toLowerCase(Locale.ROOT);
    if (!Set.of(ACCEPTED, IRRELEVANT, OPEN).contains(wanted)) {
      throw new InvalidRequestException(
          "Desenlace desconocido: " + outcome + ". Admite: aceptado, irrelevante o abierto");
    }
    Instant now = time.now();
    String next = wanted;
    String by = actor;
    String why = reason == null || reason.isBlank() ? null : reason.strip();
    if (OPEN.equals(wanted)) {
      boolean finished =
          StageStatus.valueOf(
                  jdbc.sql("SELECT status FROM stage_run WHERE id = ?")
                      .param(current.stageRunId())
                      .query(String.class)
                      .single())
              .isTerminal();
      next = finished ? PARKED : OPEN;
      by = finished ? ACTOR : null;
      why = finished ? "Se deshizo el desenlace de " + actor : null;
    }
    jdbc.sql(
            "UPDATE finding SET outcome = :outcome, outcome_by = :by, outcome_reason = :reason,"
                + " outcome_at = :at, updated_at = :now WHERE id = :id")
        .param("outcome", next)
        .param("by", by)
        .param("reason", why)
        .param("at", by == null ? null : Timestamp.from(now))
        .param("now", Timestamp.from(now))
        .param("id", id)
        .update();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("findingId", id);
    payload.put("stageKey", current.stageKey());
    payload.put("file", current.file());
    payload.put("previous", current.outcome());
    payload.put("outcome", next);
    payload.put("reason", reason == null || reason.isBlank() ? null : reason.strip());
    payload.put("actor", actor);
    transitions.append("finding", id, "finding.outcome.set", current.runId(), payload, now);
    return jdbc.sql(SELECT + " WHERE f.id = ?").param(id).query(Findings::view).single();
  }

  private static final String SELECT =
      """
      SELECT f.id, f.workflow_run_id, f.stage_run_id, s.stage_key, f.round, f.source, f.judge,
        f.lens, f.provider, f.model, f.file, f.line, f.severity, f.text, f.outcome, f.outcome_by,
        f.outcome_reason, f.outcome_at, f.repeats, f.first_seen_id, o.workflow_run_id AS
        first_seen_run_id, f.created_at
      FROM finding f
      JOIN stage_run s ON s.id = f.stage_run_id
      LEFT JOIN finding o ON o.id = f.first_seen_id
      """;

  /** Un hallazgo, como lo ve la web. */
  record FindingView(
      UUID id,
      UUID runId,
      UUID stageRunId,
      String stageKey,
      int round,
      String source,
      String judge,
      String lens,
      String provider,
      String model,
      String file,
      Integer line,
      String severity,
      String text,
      String outcome,
      String outcomeBy,
      String outcomeReason,
      Instant outcomeAt,
      int repeats,
      UUID firstSeenId,
      UUID firstSeenRunId,
      Instant createdAt) {}

  private static FindingView view(ResultSet rs, int n) throws SQLException {
    Timestamp outcomeAt = rs.getTimestamp("outcome_at");
    return new FindingView(
        rs.getObject("id", UUID.class),
        rs.getObject("workflow_run_id", UUID.class),
        rs.getObject("stage_run_id", UUID.class),
        rs.getString("stage_key"),
        rs.getInt("round"),
        rs.getString("source"),
        rs.getString("judge"),
        rs.getString("lens"),
        rs.getString("provider"),
        rs.getString("model"),
        rs.getString("file"),
        (Integer) rs.getObject("line"),
        rs.getString("severity"),
        rs.getString("text"),
        rs.getString("outcome"),
        rs.getString("outcome_by"),
        rs.getString("outcome_reason"),
        outcomeAt == null ? null : outcomeAt.toInstant(),
        rs.getInt("repeats"),
        rs.getObject("first_seen_id", UUID.class),
        rs.getObject("first_seen_run_id", UUID.class),
        rs.getTimestamp("created_at").toInstant());
  }
}
