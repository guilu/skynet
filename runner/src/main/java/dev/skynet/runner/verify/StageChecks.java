package dev.skynet.runner.verify;

import dev.skynet.protocol.runner.VerificationCheck;
import dev.skynet.runner.workspace.WorktreeState;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Las comprobaciones de una fase en su worktree ({@link VerificationCheck}). Cada una se informa
 * por separado, con su motivo si no pasa, para que la web enseñe qué falló: «`openspec/proposal.md`
 * no existe», «3 tests fallan».
 */
public final class StageChecks {

  /** Ficheros que se recorren como mucho al buscar los artefactos. */
  static final int MAX_FILES = 100_000;

  /** Ficheros fuera del ámbito que se nombran en el motivo. */
  private static final int MAX_LISTED = 5;

  private StageChecks() {}

  /** Resultado del comando de validación del repositorio, para el check {@code tests}. */
  public record TestsRun(
      boolean configured, Integer exitCode, boolean timedOut, Map<String, Object> totals) {}

  /** Resultado de un check {@code command}. */
  public record CommandRun(Integer exitCode, boolean timedOut, String error) {}

  /** Ejecuta un check {@code command} y devuelve cómo terminó. */
  @FunctionalInterface
  public interface Commands {
    CommandRun run(String command, int index) throws IOException, InterruptedException;
  }

  /**
   * Ejecuta {@code checks} en orden. Cada resultado lleva {@code name}, {@code target} (el comando
   * o los globs, si los tiene), {@code passed} y, si no pasa, {@code reason}.
   */
  public static List<Map<String, Object>> run(
      Path worktree,
      List<VerificationCheck> checks,
      String baseCommit,
      TestsRun tests,
      Commands commands)
      throws IOException, InterruptedException {
    WorktreeState state = null;
    List<Map<String, Object>> results = new ArrayList<>();
    int commandIndex = 0;
    for (VerificationCheck check : checks) {
      String reason;
      switch (check.name()) {
        case VerificationCheck.ARTIFACTS -> reason = artifacts(worktree, check.paths());
        case VerificationCheck.TESTS -> reason = tests(tests);
        case VerificationCheck.COMMAND ->
            reason = command(check, commands.run(check.command(), commandIndex++));
        default -> {
          if (state == null) {
            state = WorktreeState.of(worktree, baseCommit);
          }
          reason = changes(check, state);
        }
      }
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("name", check.name());
      if (check.target() != null) {
        result.put("target", check.target());
      }
      result.put("passed", reason == null);
      if (reason != null) {
        result.put("reason", reason);
      }
      results.add(result);
    }
    return results;
  }

  /** Cada glob tiene que encontrar al menos un fichero; los que no, en el motivo. */
  static String artifacts(Path worktree, List<String> globs) throws IOException {
    List<String> files = files(worktree);
    List<String> missing = new ArrayList<>();
    for (String glob : globs) {
      PathMatcher matcher = matcher(glob);
      if (files.stream().noneMatch(f -> matcher.matches(Path.of(f)))) {
        missing.add("`" + glob + "`");
      }
    }
    if (missing.isEmpty()) {
      return null;
    }
    return String.join(", ", missing) + (missing.size() == 1 ? " no existe" : " no existen");
  }

  private static String tests(TestsRun tests) {
    if (!tests.configured()) {
      return "El repositorio no tiene comando de verificación";
    }
    if (tests.timedOut()) {
      return "Se agotó el tiempo máximo del comando de verificación";
    }
    int failed = 0;
    if (tests.totals() != null) {
      failed = number(tests.totals().get("failed")) + number(tests.totals().get("errors"));
    }
    if (failed > 0) {
      return failed == 1 ? "1 test falla" : failed + " tests fallan";
    }
    if (tests.exitCode() == null || tests.exitCode() != 0) {
      return "El comando de verificación terminó con código " + tests.exitCode();
    }
    return null;
  }

  private static String command(VerificationCheck check, CommandRun run) {
    if (run.error() != null) {
      return run.error();
    }
    if (run.timedOut()) {
      return "Se agotó el tiempo máximo de `" + check.command() + "`";
    }
    if (run.exitCode() == null || run.exitCode() != 0) {
      return "`" + check.command() + "` terminó con código " + run.exitCode();
    }
    return null;
  }

  static String changes(VerificationCheck check, WorktreeState state) {
    return switch (check.name()) {
      case VerificationCheck.DIFF ->
          state.newCommits() == 0 && state.changedFiles().isEmpty()
              ? "No hay cambios respecto al inicio de la fase"
              : null;
      case VerificationCheck.COMMIT -> {
        if (state.newCommits() == 0) {
          yield "No hay commits nuevos";
        }
        yield state.uncommitted() > 0
            ? "Quedan cambios sin confirmar en "
                + plural(state.uncommitted(), "fichero", "ficheros")
            : null;
      }
      case VerificationCheck.SCOPE -> scope(check.paths(), state.changedFiles());
      default -> throw new IllegalArgumentException("No es un check de cambios: " + check.name());
    };
  }

  private static String scope(List<String> globs, List<String> changed) {
    List<PathMatcher> matchers = globs.stream().map(StageChecks::matcher).toList();
    List<String> outside =
        changed.stream()
            .filter(f -> matchers.stream().noneMatch(m -> m.matches(Path.of(f))))
            .toList();
    if (outside.isEmpty()) {
      return null;
    }
    String listed =
        String.join(", ", outside.stream().limit(MAX_LISTED).map(f -> "`" + f + "`").toList());
    String more = outside.size() > MAX_LISTED ? " y " + (outside.size() - MAX_LISTED) + " más" : "";
    return plural(outside.size(), "fichero", "ficheros")
        + " fuera del ámbito permitido: "
        + listed
        + more;
  }

  /** Ficheros del worktree, relativos y con {@code /}, sin {@code .git}. */
  private static List<String> files(Path worktree) throws IOException {
    try (Stream<Path> walk = Files.walk(worktree)) {
      return walk.filter(Files::isRegularFile)
          .map(p -> worktree.relativize(p).toString().replace('\\', '/'))
          .filter(p -> !p.equals(".git") && !p.startsWith(".git/"))
          .limit(MAX_FILES)
          .toList();
    }
  }

  private static PathMatcher matcher(String glob) {
    return FileSystems.getDefault().getPathMatcher("glob:" + glob);
  }

  private static int number(Object value) {
    return value instanceof Number n ? n.intValue() : 0;
  }

  private static String plural(int n, String one, String many) {
    return n + " " + (n == 1 ? one : many);
  }
}
