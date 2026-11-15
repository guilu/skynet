package dev.skynet.controlplane.memory;

import java.text.Normalizer;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ¿Ya está hecho? (MV-B, docs/memoria.md): compara un trabajo con los que ya se entregaron en su
 * proyecto, es decir, los que tienen una PR fusionada. Sin modelo: palabras del título y de la
 * descripción, cortadas a su raíz, y su parecido (Jaccard).
 *
 * <ul>
 *   <li>Desde {@code skynet.memory.done-similarity} (0,6) es un parecido <b>fuerte</b>: lanzarlo a
 *       mano pide confirmarlo, y un disparador de GitHub no lo lanza.
 *   <li>Desde {@code skynet.memory.done-hint} (0,3) es un parecido <b>leve</b>: solo se avisa en el
 *       bloque de memoria del agente.
 *   <li>El propio trabajo, si ya tiene una PR fusionada, cuenta como parecido fuerte.
 * </ul>
 *
 * <p>Lee las tablas de trabajos, ejecuciones y PRs directamente, para no depender del motor.
 */
@Service
public class DoneWork {

  /** Cuántos trabajos parecidos se devuelven como mucho. */
  static final int MAX_MATCHES = 5;

  /** Letras con las que se queda cada palabra: «exportar» y «exportación» son la misma. */
  static final int ROOT = 5;

  private static final Pattern MARKS = Pattern.compile("\\p{M}+");
  private static final Pattern NOT_LETTER = Pattern.compile("[^a-z0-9]+");
  private static final DateTimeFormatter DAY =
      DateTimeFormatter.ofPattern("d/M/yyyy").withZone(ZoneOffset.UTC);

  /** Palabras que no dicen nada del trabajo. */
  private static final Set<String> STOPWORDS =
      Set.of(
          "los", "las", "del", "que", "con", "una", "uno", "unos", "unas", "por", "sin", "mas",
          "para", "como", "este", "esta", "esto", "pero", "cuando", "donde", "sobre", "entre",
          "desde", "hasta", "cada", "todo", "toda", "todos", "todas", "otro", "otra", "the", "and",
          "for", "with", "from", "into", "that", "this", "when", "add", "fix", "anadir", "hacer",
          "nuevo", "nueva");

  /** Un trabajo ya entregado que se parece. */
  public record Match(
      UUID workItemId,
      String key,
      String title,
      double similarity,
      boolean strong,
      boolean self,
      Integer pullRequestNumber,
      String pullRequestUrl,
      Instant deliveredAt) {

    /** Una línea para un mensaje o para el bloque de memoria. */
    public String describe() {
      StringBuilder s = new StringBuilder(key).append(" «").append(title).append("»");
      s.append(self ? ", este mismo trabajo," : "").append(" se entregó");
      if (pullRequestNumber != null) {
        s.append(" en la PR #").append(pullRequestNumber);
      }
      if (deliveredAt != null) {
        s.append(" el ").append(DAY.format(deliveredAt));
      }
      return s.toString();
    }
  }

  private record Candidate(
      UUID id,
      String key,
      String title,
      String description,
      Integer number,
      String url,
      Instant deliveredAt) {}

  private final JdbcClient jdbc;
  private final double strong;
  private final double hint;

  DoneWork(
      JdbcClient jdbc,
      @Value("${skynet.memory.done-similarity:0.6}") double strong,
      @Value("${skynet.memory.done-hint:0.3}") double hint) {
    this.jdbc = jdbc;
    this.strong = strong;
    this.hint = hint;
  }

  /** Los trabajos entregados que se parecen a {@code workItemId}, del más parecido al menos. */
  @Transactional(readOnly = true)
  public List<Match> matches(UUID workItemId) {
    record Item(UUID projectId, String title, String description) {}
    Optional<Item> item =
        jdbc.sql("SELECT project_id, title, description FROM work_item WHERE id = ?")
            .param(workItemId)
            .query(
                (rs, n) ->
                    new Item(
                        rs.getObject("project_id", UUID.class),
                        rs.getString("title"),
                        rs.getString("description")))
            .optional();
    if (item.isEmpty()) {
      return List.of();
    }
    return matches(
        item.get().projectId(), workItemId, item.get().title(), item.get().description());
  }

  /**
   * Los trabajos entregados del proyecto que se parecen a ese texto. {@code workItemId} es el
   * trabajo que se compara (cuenta como parecido fuerte si ya se entregó), o {@code null}.
   */
  @Transactional(readOnly = true)
  public List<Match> matches(UUID projectId, UUID workItemId, String title, String description) {
    Set<String> titleWords = roots(title);
    Set<String> allWords = roots(title, description);
    List<Match> matches = new ArrayList<>();
    for (Candidate c : delivered(projectId)) {
      boolean self = c.id().equals(workItemId);
      double similarity =
          self
              ? 1
              : Math.max(
                  similarity(titleWords, roots(c.title())),
                  similarity(allWords, roots(c.title(), c.description())));
      if (similarity < hint) {
        continue;
      }
      matches.add(
          new Match(
              c.id(),
              c.key(),
              c.title(),
              Math.round(similarity * 100) / 100.0,
              self || similarity >= strong,
              self,
              c.number(),
              c.url(),
              c.deliveredAt()));
    }
    matches.sort(
        Comparator.comparing(Match::self)
            .thenComparingDouble(Match::similarity)
            .reversed()
            .thenComparing(Match::key));
    return matches.size() > MAX_MATCHES ? List.copyOf(matches.subList(0, MAX_MATCHES)) : matches;
  }

  /**
   * El aviso del bloque de memoria de un agente que trabaja en {@code workItemId}, o vacío si no se
   * parece a nada entregado.
   */
  @Transactional(readOnly = true)
  public Optional<String> notice(UUID workItemId) {
    List<Match> matches = workItemId == null ? List.of() : matches(workItemId);
    if (matches.isEmpty()) {
      return Optional.empty();
    }
    StringBuilder s =
        new StringBuilder(
            "Trabajos parecidos que ya se entregaron en este proyecto (comprueba que no repites lo"
                + " hecho; los detalles, con mem_done o en la bitácora):");
    for (Match m : matches) {
      s.append("\n- ")
          .append(m.describe())
          .append(m.self() ? "" : " (parecido " + Math.round(m.similarity() * 100) + " %)");
    }
    return Optional.of(s.toString());
  }

  /** Los trabajos del proyecto con alguna PR fusionada, con la última. */
  private List<Candidate> delivered(UUID projectId) {
    return jdbc.sql(
            """
            SELECT DISTINCT ON (w.id) w.id, w.key, w.title, w.description, p.number, p.url,
                   p.updated_at
            FROM work_item w
            JOIN workflow_run r ON r.work_item_id = w.id
            JOIN github_pull_request p ON p.workflow_run_id = r.id
            WHERE w.project_id = ? AND p.status = 'MERGED'
            ORDER BY w.id, p.updated_at DESC
            """)
        .param(projectId)
        .query(
            (rs, n) ->
                new Candidate(
                    rs.getObject("id", UUID.class),
                    rs.getString("key"),
                    rs.getString("title"),
                    rs.getString("description"),
                    (Integer) rs.getObject("number"),
                    rs.getString("url"),
                    rs.getTimestamp("updated_at").toInstant()))
        .list();
  }

  /**
   * Las raíces de las palabras: en minúsculas, sin tildes, sin palabras vacías, de tres letras o
   * más y cortadas a {@link #ROOT} letras.
   */
  static Set<String> roots(String... texts) {
    TreeSet<String> roots = new TreeSet<>();
    for (String text : texts) {
      if (text == null) {
        continue;
      }
      String plain = MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("");
      plain = NOT_LETTER.matcher(plain.toLowerCase(Locale.ROOT)).replaceAll(" ");
      for (String word : plain.strip().split(" ")) {
        if (word.length() >= 3 && !STOPWORDS.contains(word)) {
          roots.add(word.length() > ROOT ? word.substring(0, ROOT) : word);
        }
      }
    }
    return roots;
  }

  /** Lo que se parecen dos conjuntos de raíces (Jaccard), de 0 a 1; vacíos, nada. */
  static double similarity(Set<String> a, Set<String> b) {
    if (a.isEmpty() || b.isEmpty()) {
      return 0;
    }
    Set<String> union = new HashSet<>(a);
    union.addAll(b);
    Set<String> common = new HashSet<>(a);
    common.retainAll(b);
    return (double) common.size() / union.size();
  }
}
