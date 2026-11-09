package dev.skynet.runner.verify;

import dev.skynet.protocol.runner.RiskRules;
import dev.skynet.runner.workspace.FileChanges;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * El nivel de riesgo de un cambio (AU-A), calculado del diff y nunca del agente: qué rutas toca y
 * qué líneas añade. Ver {@link RiskRules}. No falla nunca: es una medida, no una comprobación.
 */
public final class RiskClassifier {

  /** Lo que se lee como mucho de las líneas añadidas de cada fichero. */
  static final int MAX_BYTES_PER_FILE = 256 * 1024;

  /** Motivos que se devuelven como mucho, los de más nivel primero. */
  static final int MAX_REASONS = 20;

  /** Lo que se cita de la línea que casa con un patrón. */
  private static final int MAX_MATCH = 80;

  /**
   * Un motivo del nivel: un fichero, su clase y, si lo subió una línea, lo que casó.
   *
   * @param path el fichero
   * @param tier su nivel
   * @param kind {@code docs}, {@code tests}, {@code code} o {@code level-2}
   * @param label por qué es nivel 2 («ruta», «esquema de BD», «secretos»…); {@code null} si no
   * @param match el trozo de la línea que casó con un patrón; {@code null} si fue la ruta
   */
  public record Reason(String path, int tier, String kind, String label, String match) {

    Map<String, Object> toMap() {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("path", path);
      map.put("tier", tier);
      map.put("kind", kind);
      if (label != null) {
        map.put("label", label);
      }
      if (match != null) {
        map.put("match", match);
      }
      return map;
    }
  }

  /**
   * El nivel de un cambio.
   *
   * @param tier 0, 1 o 2
   * @param files ficheros cambiados
   * @param lines líneas añadidas y quitadas, sin tests ni documentación
   * @param reasons los motivos, los de más nivel primero y como mucho {@link #MAX_REASONS}
   * @param more motivos que no caben
   * @param warnings patrones del repositorio o del workflow que no se pudieron usar
   */
  public record Result(
      int tier, int files, int lines, List<Reason> reasons, int more, List<String> warnings) {

    /** Lo que va en el resultado del check, bajo {@code risk}. */
    public Map<String, Object> toMap() {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("tier", tier);
      map.put("files", files);
      map.put("lines", lines);
      map.put("reasons", reasons.stream().map(Reason::toMap).toList());
      if (more > 0) {
        map.put("more", more);
      }
      return map;
    }

    /** Una línea para la web: «Nivel 2: `V49__risk.sql` (ruta)». */
    public String summary() {
      if (files == 0) {
        return "Nivel 0: sin cambios";
      }
      Reason top = reasons.isEmpty() ? null : reasons.getFirst();
      String why =
          switch (tier) {
            case 0 -> "solo documentación";
            case 1 -> lines + (lines == 1 ? " línea" : " líneas") + " de código";
            default ->
                top == null
                    ? "nivel 2"
                    : "`" + top.path() + "` (" + (top.label() == null ? "ruta" : top.label()) + ")";
          };
      return "Nivel " + tier + ": " + why;
    }
  }

  private record Rule(Pattern pattern, String label) {}

  private RiskClassifier() {}

  /**
   * El nivel del cambio de {@code worktree} respecto a {@code baseCommit} (o a {@code HEAD} si no
   * hay base), con las rutas y los patrones de nivel 2 que se suman a {@link RiskRules}.
   */
  public static Result assess(
      Path worktree, String baseCommit, List<String> extraPaths, List<String> extraPatterns)
      throws IOException, InterruptedException {
    String base = FileChanges.base(worktree, baseCommit);
    List<FileChanges.Change> changes = FileChanges.of(worktree, base);
    Map<String, FileChanges.Lines> lines = FileChanges.lines(worktree, base, MAX_BYTES_PER_FILE);
    List<String> paths = new ArrayList<>();
    for (FileChanges.Change change : changes) {
      if (change.oldPath() != null && !change.oldPath().equals(change.path())) {
        paths.add(change.oldPath());
      }
      paths.add(change.path());
    }
    return classify(paths, lines, extraPaths, extraPatterns);
  }

  /** {@link #assess} sin git: las rutas cambiadas y sus líneas. */
  static Result classify(
      List<String> paths,
      Map<String, FileChanges.Lines> lines,
      List<String> extraPaths,
      List<String> extraPatterns) {
    List<PathMatcher> docs = matchers(RiskRules.DOCS);
    List<PathMatcher> tests = matchers(RiskRules.TESTS);
    List<String> level2Globs = new ArrayList<>(RiskRules.LEVEL_2_PATHS);
    level2Globs.addAll(extraPaths == null ? List.of() : extraPaths);
    List<PathMatcher> level2 = matchers(level2Globs);
    List<String> warnings = new ArrayList<>();
    List<Rule> rules = new ArrayList<>();
    for (RiskRules.Pattern p : RiskRules.LEVEL_2_PATTERNS) {
      rules.add(new Rule(compile(p.regex()), p.label()));
    }
    for (String regex : extraPatterns == null ? List.<String>of() : extraPatterns) {
      try {
        rules.add(new Rule(compile(regex), "patrón del repositorio"));
      } catch (PatternSyntaxException e) {
        warnings.add("Patrón de riesgo no válido, no se usa: `" + regex + "`");
      }
    }

    List<Reason> reasons = new ArrayList<>();
    int tier = 0;
    int counted = 0;
    List<String> seen = new ArrayList<>();
    for (String path : paths) {
      if (seen.contains(path)) {
        continue;
      }
      seen.add(path);
      Reason reason;
      boolean countLines = false;
      if (matches(level2, path)) {
        reason = new Reason(path, 2, "level-2", "ruta", null);
        countLines =
            !matches(docs, path) && !TestIntegrity.isTestFile(path) && !matches(tests, path);
      } else if (matches(docs, path)) {
        reason = new Reason(path, 0, "docs", null, null);
      } else if (TestIntegrity.isTestFile(path) || matches(tests, path)) {
        reason = new Reason(path, 1, "tests", null, null);
      } else {
        countLines = true;
        reason = content(path, lines.get(path), rules);
        if (reason == null) {
          reason = new Reason(path, 1, "code", null, null);
        }
      }
      if (countLines) {
        FileChanges.Lines changed = lines.get(path);
        if (changed != null) {
          counted += changed.addedCount() + changed.removed();
        }
      }
      tier = Math.max(tier, reason.tier());
      reasons.add(reason);
    }
    reasons.sort(Comparator.comparingInt(Reason::tier).reversed());
    int more = Math.max(0, reasons.size() - MAX_REASONS);
    return new Result(
        tier,
        seen.size(),
        counted,
        List.copyOf(reasons.subList(0, Math.min(reasons.size(), MAX_REASONS))),
        more,
        List.copyOf(warnings));
  }

  /** La primera línea añadida que casa con un patrón de nivel 2, o {@code null}. */
  private static Reason content(String path, FileChanges.Lines changed, List<Rule> rules) {
    if (changed == null) {
      return null;
    }
    for (String line : changed.added()) {
      for (Rule rule : rules) {
        Matcher m = rule.pattern().matcher(line);
        if (m.find()) {
          String match = m.group().strip();
          if (match.length() > MAX_MATCH) {
            match = match.substring(0, MAX_MATCH);
          }
          return new Reason(path, 2, "level-2", rule.label(), match);
        }
      }
    }
    return null;
  }

  private static Pattern compile(String regex) {
    return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  }

  private static List<PathMatcher> matchers(List<String> globs) {
    List<PathMatcher> matchers = new ArrayList<>();
    for (String glob : globs) {
      matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + glob));
      // «**/x/**» también vale en la raíz: «x/…».
      if (glob.startsWith("**/")) {
        matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + glob.substring(3)));
      }
    }
    return matchers;
  }

  private static boolean matches(List<PathMatcher> matchers, String path) {
    Path p = Path.of(path);
    return matchers.stream().anyMatch(m -> m.matches(p));
  }
}
