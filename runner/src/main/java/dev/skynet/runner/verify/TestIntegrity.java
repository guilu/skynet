package dev.skynet.runner.verify;

import dev.skynet.runner.workspace.FileChanges;
import dev.skynet.runner.workspace.FileChanges.Change;
import dev.skynet.runner.workspace.FileChanges.Kind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * El check {@code tests-intact} (VA-A): mira el diff de la fase y dice si debilita los tests para
 * ponerse en verde. Falla si borra un fichero de tests (o lo saca de donde están los tests), si
 * quita casos de uno que sigue existiendo o si los salta ({@code @Disabled}, {@code .skip}, {@code
 * .only}…). Si solo baja el número de aserciones, avisa sin fallar: hay refactors legítimos.
 *
 * <p>Lee el diff, no ejecuta nada: es inmediato y el motivo lleva el fichero y el test.
 */
final class TestIntegrity {

  /** Ficheros que se nombran en el motivo. */
  static final int MAX_LISTED = 5;

  /**
   * El resultado.
   *
   * @param problems lo que hace fallar el check, en el orden en que se encontró
   * @param warnings lo que solo se avisa
   */
  record Result(List<String> problems, List<String> warnings) {

    String reason() {
      if (problems.isEmpty()) {
        return null;
      }
      String listed = String.join("; ", problems.stream().limit(MAX_LISTED).toList());
      return problems.size() > MAX_LISTED
          ? listed + " y " + (problems.size() - MAX_LISTED) + " más"
          : listed;
    }
  }

  /** Un lenguaje: cómo se declara un caso, cómo se salta y cómo se comprueba algo. */
  private record Language(
      Pattern file, Pattern testCase, int nameGroup, List<Skip> skips, Pattern assertion) {}

  /** Una forma de saltar tests, con cómo se nombra en el motivo. */
  private record Skip(Pattern pattern, String label) {}

  /** Un directorio de tests de Gradle o Maven: {@code src/test/}, {@code src/integrationTest/}… */
  private static final Pattern JVM_TEST_DIR = Pattern.compile("(?:^|/)src/[\\w-]*[tT]est[\\w-]*/");

  /** Un fichero que se llama como un test: {@code *Test.java}, {@code *IT.kt}… */
  private static final Pattern JVM_TEST_NAME =
      Pattern.compile("(?:Test|Tests|IT|Spec)\\.(?:java|kt)$");

  private static final Language JVM =
      new Language(
          Pattern.compile("\\.(?:java|kt)$"),
          Pattern.compile(
              "@(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\\b(?:\\([^)]*\\))?"
                  + "[^;]*?\\b(?:void|fun)\\s+(`[^`]+`|\\w+)\\s*\\("),
          1,
          List.of(
              new Skip(Pattern.compile("@(?:[\\w.]+\\.)?Disabled\\w*\\b"), "@Disabled"),
              new Skip(Pattern.compile("@(?:[\\w.]+\\.)?Ignore\\b"), "@Ignore"),
              new Skip(
                  Pattern.compile("\\bassume(?:True|That)?\\s*\\(\\s*false\\s*\\)"),
                  "assumeTrue(false)"),
              new Skip(Pattern.compile("\\bAssumptions\\.abort\\s*\\("), "Assumptions.abort")),
          Pattern.compile(
              "\\bassert\\w*\\s*\\(|\\bverify\\s*\\(|\\bfail\\s*\\(|\\bassertThrows\\b"));

  private static final Language JS =
      new Language(
          Pattern.compile("\\.[cm]?[jt]sx?$"),
          Pattern.compile(
              "(?<![.\\w])[xf]?(?:it|test)(?:\\.(?:only|skip|todo|concurrent))?(?:\\.each\\s*\\([^)]*\\))?\\s*\\(\\s*(['\"`])(.*?)\\1"),
          2,
          List.of(
              new Skip(
                  Pattern.compile("(?<![.\\w])(?:it|test|describe)\\.(?:skip|todo)\\b"), ".skip"),
              new Skip(Pattern.compile("(?<![.\\w])(?:it|test|describe)\\.only\\b"), ".only"),
              new Skip(Pattern.compile("(?<![.\\w])x(?:it|test|describe)\\s*\\("), "xit"),
              new Skip(Pattern.compile("(?<![.\\w])f(?:it|describe)\\s*\\("), "fit")),
          Pattern.compile("\\bexpect\\s*\\(|\\bassert\\w*(?:\\.\\w+)?\\s*\\(|\\.should\\b"));

  private static final Language PYTHON =
      new Language(
          Pattern.compile("\\.py$"),
          Pattern.compile("^\\s*(?:async\\s+)?def\\s+(test\\w*)\\s*\\(", Pattern.MULTILINE),
          1,
          List.of(
              new Skip(
                  Pattern.compile("@pytest\\.mark\\.(?:skip|skipif|xfail)\\b"),
                  "@pytest.mark.skip"),
              new Skip(Pattern.compile("@unittest\\.skip\\w*\\b"), "@unittest.skip"),
              new Skip(Pattern.compile("\\bpytest\\.skip\\s*\\("), "pytest.skip()")),
          Pattern.compile(
              "(?<![\\w.])assert\\s|\\bself\\.assert\\w+\\s*\\(|\\bpytest\\.raises\\b"));

  private static final Language GO =
      new Language(
          Pattern.compile("_test\\.go$"),
          Pattern.compile("^func\\s+(Test\\w*)\\s*\\(", Pattern.MULTILINE),
          1,
          List.of(new Skip(Pattern.compile("\\bt\\.Skip(?:Now|f)?\\s*\\("), "t.Skip()")),
          Pattern.compile(
              "\\bt\\.(?:Error|Errorf|Fatal|Fatalf|Fail|FailNow)\\s*\\(|\\b(?:assert|require)\\.\\w+\\s*\\("));

  private static final List<Language> LANGUAGES = List.of(JVM, JS, PYTHON, GO);

  private TestIntegrity() {}

  /** Si {@code path} es un fichero de tests de un lenguaje que se sabe leer. */
  static boolean isTestFile(String path) {
    return language(path).isPresent();
  }

  private static Optional<Language> language(String path) {
    String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
    if (JVM.file().matcher(path).find()
        && (JVM_TEST_DIR.matcher(path).find() || JVM_TEST_NAME.matcher(path).find())) {
      return Optional.of(JVM);
    }
    if (JS.file().matcher(path).find()
        && (name.matches(".*\\.(?:test|spec)\\.[cm]?[jt]sx?")
            || path.matches("(?:.*/)?(?:__tests__|tests?|e2e)/.*"))) {
      return Optional.of(JS);
    }
    if (PYTHON.file().matcher(path).find()
        && (name.startsWith("test_")
            || name.endsWith("_test.py")
            || path.matches("(?:.*/)?tests?/.*"))) {
      return Optional.of(PYTHON);
    }
    if (GO.file().matcher(path).find()) {
      return Optional.of(GO);
    }
    return Optional.empty();
  }

  /** Comprueba los cambios de {@code worktree} desde {@code baseCommit}. */
  static Result check(Path worktree, String baseCommit) throws IOException, InterruptedException {
    String base = FileChanges.base(worktree, baseCommit);
    List<String> problems = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    for (Change change : FileChanges.of(worktree, base)) {
      if (change.oldPath() == null) {
        continue; // Un fichero nuevo no quita nada.
      }
      Optional<Language> before = language(change.oldPath());
      if (before.isEmpty()) {
        continue;
      }
      if (change.kind() == Kind.DELETED) {
        problems.add(
            deleted(change.oldPath(), FileChanges.atBase(worktree, base, change.oldPath())));
        continue;
      }
      if (change.kind() == Kind.RENAMED && language(change.path()).isEmpty()) {
        problems.add(
            "Se ha sacado `"
                + change.oldPath()
                + "` de los tests (ahora es `"
                + change.path()
                + "`)");
        continue;
      }
      String old = FileChanges.atBase(worktree, base, change.oldPath());
      Path now = worktree.resolve(change.path());
      if (old == null || !Files.isRegularFile(now)) {
        continue;
      }
      compare(
          before.get(),
          change.path(),
          old,
          Files.readString(now, StandardCharsets.UTF_8),
          problems,
          warnings);
    }
    return new Result(List.copyOf(problems), List.copyOf(warnings));
  }

  /** Un fichero de tests borrado, con sus casos si se pueden leer. */
  private static String deleted(String path, String content) {
    Set<String> cases = content == null ? Set.of() : cases(language(path).orElseThrow(), content);
    if (cases.size() == 1) {
      return "Se ha borrado el test `" + testName(path, cases.iterator().next()) + "`";
    }
    return "Se ha borrado el fichero de tests `"
        + path
        + "`"
        + (cases.isEmpty() ? "" : " (" + cases.size() + " tests)");
  }

  /** Compara un fichero de tests antes y después. Visible para los tests. */
  static void compare(
      String path, String before, String after, List<String> problems, List<String> warnings) {
    compare(language(path).orElseThrow(), path, before, after, problems, warnings);
  }

  private static void compare(
      Language language,
      String path,
      String before,
      String after,
      List<String> problems,
      List<String> warnings) {
    Set<String> casesBefore = cases(language, before);
    Set<String> casesAfter = cases(language, after);
    int countBefore = count(language.testCase(), before);
    int countAfter = count(language.testCase(), after);
    boolean removed = false;
    if (countAfter < countBefore) {
      removed = true;
      List<String> gone = casesBefore.stream().filter(c -> !casesAfter.contains(c)).toList();
      if (gone.size() == 1) {
        problems.add("Se ha borrado el test `" + testName(path, gone.getFirst()) + "`");
      } else {
        int n = countBefore - countAfter;
        problems.add(
            "Se han quitado "
                + n
                + (n == 1 ? " test" : " tests")
                + " de `"
                + path
                + "` (de "
                + countBefore
                + " a "
                + countAfter
                + ")"
                + (gone.isEmpty()
                    ? ""
                    : ": "
                        + String.join(
                            ", ",
                            gone.stream().limit(MAX_LISTED).map(g -> "`" + g + "`").toList())));
      }
    }
    for (Skip skip : language.skips()) {
      int added = count(skip.pattern(), after) - count(skip.pattern(), before);
      if (added > 0) {
        problems.add("Se ha añadido `" + skip.label() + "` en `" + path + "`");
      }
    }
    if (!removed) {
      int assertionsBefore = count(language.assertion(), before);
      int assertionsAfter = count(language.assertion(), after);
      if (assertionsAfter < assertionsBefore) {
        warnings.add(
            "Hay menos aserciones en `"
                + path
                + "` (de "
                + assertionsBefore
                + " a "
                + assertionsAfter
                + ")");
      }
    }
  }

  /** {@code Clase.metodo} en Java y Kotlin; el nombre del caso en los demás. */
  private static String testName(String path, String name) {
    if (path.endsWith(".java") || path.endsWith(".kt")) {
      String file = path.substring(path.lastIndexOf('/') + 1);
      return file.substring(0, file.lastIndexOf('.')) + "." + name.replace("`", "");
    }
    return name;
  }

  private static Set<String> cases(Language language, String content) {
    Set<String> names = new LinkedHashSet<>();
    Matcher m = language.testCase().matcher(content);
    while (m.find()) {
      String name = m.group(language.nameGroup());
      if (name != null) {
        names.add(name);
      }
    }
    return names;
  }

  private static int count(Pattern pattern, String content) {
    int n = 0;
    Matcher m = pattern.matcher(content);
    while (m.find()) {
      n++;
    }
    return n;
  }
}
