package dev.skynet.runner.verify;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * El check {@code spec} (ES-A): la propuesta dice lo que hace falta para juzgarla. Tiene que tener
 * cinco secciones de Markdown (de nivel 2 o más), cada una con texto: qué se podrá hacer, qué
 * resultado se obtiene, qué debe respetar, cómo se demuestra y qué queda fuera de alcance.
 *
 * <p>Los títulos se reconocen por sus palabras, sin tildes ni mayúsculas y también en inglés: «Qué
 * podrá hacer el usuario», «## 4. Cómo se demuestra:» u «Out of scope» valen.
 */
public final class SpecSections {

  /** Más que esto no es una propuesta: se lee solo el principio. */
  static final int MAX_BYTES = 256 * 1024;

  private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");
  private static final Pattern MARKS = Pattern.compile("\\p{M}+");
  private static final Pattern NOT_WORD = Pattern.compile("[^a-z]+");
  private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

  /** Lo que no cuenta como texto: un guion, puntos suspensivos, «TODO», «TBD», «pendiente». */
  private static final Pattern PLACEHOLDER =
      Pattern.compile("^[-*_.…\\s]*(todo|tbd|pendiente|por escribir|n/?a)?[-*_.…\\s]*$");

  /** Una sección que hace falta: su nombre y las palabras que la reconocen en un título. */
  private record Section(String name, List<String> words) {

    boolean matches(String title) {
      return words.stream().anyMatch(w -> (" " + title + " ").contains(" " + w));
    }
  }

  /** En este orden: «Fuera de alcance» primero, que si no «qué se podrá hacer» se lo llevaría. */
  private static final List<Section> SECTIONS =
      List.of(
          new Section(
              "Fuera de alcance",
              List.of(
                  "fuera de alcance",
                  "fuera del alcance",
                  "out of scope",
                  "non goals",
                  "no incluye")),
          new Section(
              "Cómo se demuestra",
              List.of(
                  "demuestr",
                  "demostr",
                  "como se comprueba",
                  "como se prueba",
                  "how it is proven",
                  "how to prove",
                  "how it is tested",
                  "how to test",
                  "proof",
                  "verification")),
          new Section(
              "Qué debe respetar",
              List.of("respet", "restriccion", "must respect", "constraint", "must not break")),
          new Section("Qué resultado se obtiene", List.of("resultado", "result", "outcome")),
          new Section(
              "Qué se podrá hacer",
              List.of("hacer", "podra", "can do", "user can", "what the user")));

  private SpecSections() {}

  /** {@code null} si la propuesta tiene todo; si no, el motivo, con lo que falta. */
  public static String check(Path worktree, String file) throws IOException {
    Path path = worktree.resolve(file).normalize();
    if (!path.startsWith(worktree.normalize()) || !Files.isRegularFile(path)) {
      return "`" + file + "` no existe";
    }
    String text;
    try (var in = Files.newInputStream(path)) {
      text = new String(in.readNBytes(MAX_BYTES), StandardCharsets.UTF_8);
    }
    return missing(file, text);
  }

  static String missing(String file, String markdown) {
    Set<Section> titled = new HashSet<>();
    Set<Section> written = new HashSet<>();
    String[] lines = COMMENT.matcher(markdown).replaceAll("").split("\\R", -1);
    boolean fenced = false;
    for (int i = 0; i < lines.length; i++) {
      if (lines[i].strip().startsWith("```")) {
        fenced = !fenced;
        continue;
      }
      var heading = fenced ? null : HEADING.matcher(lines[i]);
      if (heading == null || !heading.matches() || heading.group(1).length() < 2) {
        continue;
      }
      String title = normalize(heading.group(2));
      Section section = SECTIONS.stream().filter(s -> s.matches(title)).findFirst().orElse(null);
      if (section != null) {
        titled.add(section);
        if (hasText(lines, i + 1, heading.group(1).length())) {
          written.add(section);
        }
      }
    }
    List<String> empty = new ArrayList<>();
    List<String> absent = new ArrayList<>();
    for (Section s : SECTIONS.reversed()) {
      if (!written.contains(s)) {
        (titled.contains(s) ? empty : absent).add("«" + s.name() + "»");
      }
    }
    if (empty.isEmpty() && absent.isEmpty()) {
      return null;
    }
    String vacias =
        empty.isEmpty() ? "" : (empty.size() == 1 ? "está vacía " : "están vacías ") + join(empty);
    if (absent.isEmpty()) {
      return "En la propuesta `" + file + "` " + vacias;
    }
    return "A la propuesta `"
        + file
        + "` le falta "
        + join(absent)
        + (empty.isEmpty() ? "" : "; " + vacias);
  }

  /** Hay texto entre el título y el siguiente del mismo nivel o más alto. */
  private static boolean hasText(String[] lines, int from, int level) {
    for (int i = from; i < lines.length; i++) {
      var heading = HEADING.matcher(lines[i]);
      if (heading.matches() && heading.group(1).length() <= level) {
        return false;
      }
      if (heading.matches()) {
        continue;
      }
      if (!PLACEHOLDER.matcher(lines[i].strip().toLowerCase(Locale.ROOT)).matches()) {
        return true;
      }
    }
    return false;
  }

  static String normalize(String title) {
    String plain = MARKS.matcher(Normalizer.normalize(title, Normalizer.Form.NFD)).replaceAll("");
    return NOT_WORD.matcher(plain.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
  }

  private static String join(List<String> names) {
    if (names.size() == 1) {
      return names.getFirst();
    }
    return String.join(", ", names.subList(0, names.size() - 1)) + " y " + names.getLast();
  }
}
