package dev.skynet.runner.verify;

import static dev.skynet.runner.TestRepos.git;
import static org.assertj.core.api.Assertions.assertThat;

import dev.skynet.protocol.runner.VerificationCheck;
import dev.skynet.runner.TestRepos;
import dev.skynet.runner.verify.StageChecks.CommandRun;
import dev.skynet.runner.verify.StageChecks.TestsRun;
import dev.skynet.runner.workspace.WorktreeState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StageChecksTest {

  private static final TestsRun PASSING =
      new TestsRun(true, 0, false, Map.of("total", 3, "failed", 0, "errors", 0));

  @TempDir Path dir;

  private Path repo;
  private String base;

  @BeforeEach
  void setUp() throws Exception {
    repo = TestRepos.create(dir.resolve("repo"));
    base = git(repo, "rev-parse", "HEAD");
  }

  @Test
  void withNothingChangedOnlyTheArtifactsThatExistPass() throws Exception {
    List<Map<String, Object>> results =
        run(
            List.of(
                VerificationCheck.withPaths("artifacts", List.of("calc.py", "docs/*.md")),
                VerificationCheck.named("diff"),
                VerificationCheck.named("commit")),
            PASSING);

    assertThat(results)
        .containsExactly(
            Map.of(
                "name",
                "artifacts",
                "target",
                "calc.py, docs/*.md",
                "passed",
                false,
                "reason",
                "`docs/*.md` no existe"),
            Map.of(
                "name", "diff",
                "passed", false,
                "reason", "No hay cambios respecto al inicio de la fase"),
            Map.of("name", "commit", "passed", false, "reason", "No hay commits nuevos"));
  }

  @Test
  void committedChangesInsideTheScopePass() throws Exception {
    Files.createDirectories(repo.resolve("docs"));
    Files.writeString(repo.resolve("docs/spec.md"), "# Spec\n");
    commit("spec");

    List<Map<String, Object>> results =
        run(
            List.of(
                VerificationCheck.withPaths("artifacts", List.of("docs/*.md")),
                VerificationCheck.named("diff"),
                VerificationCheck.named("commit"),
                VerificationCheck.withPaths("scope", List.of("docs/**"))),
            PASSING);

    assertThat(results).allSatisfy(r -> assertThat(r).containsEntry("passed", true));
    assertThat(results).allSatisfy(r -> assertThat(r).doesNotContainKey("reason"));
  }

  @Test
  void uncommittedOrOutOfScopeChangesFail() throws Exception {
    Files.createDirectories(repo.resolve("docs"));
    Files.writeString(repo.resolve("docs/spec.md"), "# Spec\n");
    commit("spec");
    Files.writeString(repo.resolve("calc.py"), "def add(a, b):\n    return a + b\n");
    Files.writeString(repo.resolve("notas.txt"), "pendiente\n");

    List<Map<String, Object>> results =
        run(
            List.of(
                VerificationCheck.named("diff"),
                VerificationCheck.named("commit"),
                VerificationCheck.withPaths("scope", List.of("docs/**"))),
            PASSING);

    assertThat(results.get(0)).containsEntry("passed", true);
    assertThat(results.get(1))
        .containsEntry("reason", "Quedan cambios sin confirmar en 2 ficheros");
    assertThat(results.get(2))
        .containsEntry("reason", "2 ficheros fuera del ámbito permitido: `calc.py`, `notas.txt`");
  }

  @Test
  void aLongListOfFilesOutOfScopeIsCut() {
    WorktreeState state = new WorktreeState(base, 1, List.of("a", "b", "c", "d", "e", "f", "g"), 0);

    assertThat(StageChecks.changes(VerificationCheck.withPaths("scope", List.of("src/**")), state))
        .isEqualTo("7 ficheros fuera del ámbito permitido: `a`, `b`, `c`, `d`, `e` y 2 más");
  }

  @Test
  void testsFollowTheRepositoryCommandAndItsReports() throws Exception {
    VerificationCheck tests = VerificationCheck.named("tests");

    assertThat(reason(tests, new TestsRun(false, null, false, null)))
        .isEqualTo("El repositorio no tiene comando de verificación");
    assertThat(reason(tests, new TestsRun(true, null, true, null)))
        .isEqualTo("Se agotó el tiempo máximo del comando de verificación");
    assertThat(reason(tests, new TestsRun(true, 1, false, Map.of("failed", 2, "errors", 1))))
        .isEqualTo("3 tests fallan");
    assertThat(reason(tests, new TestsRun(true, 1, false, Map.of("failed", 1))))
        .isEqualTo("1 test falla");
    assertThat(reason(tests, new TestsRun(true, 2, false, null)))
        .isEqualTo("El comando de verificación terminó con código 2");
    assertThat(reason(tests, PASSING)).isNull();
  }

  @Test
  void commandsRunInOrderAndFailWithTheirExitCode() throws Exception {
    List<String> ran = new ArrayList<>();
    List<Map<String, Object>> results =
        StageChecks.run(
            repo,
            List.of(
                VerificationCheck.command("npm run lint"),
                VerificationCheck.command("make check"),
                VerificationCheck.command("sleep 99")),
            base,
            PASSING,
            (command, index) -> {
              ran.add(index + ":" + command);
              return switch (index) {
                case 0 -> new CommandRun(0, false, null);
                case 1 -> new CommandRun(2, false, null);
                default -> new CommandRun(null, true, null);
              };
            });

    assertThat(ran).containsExactly("0:npm run lint", "1:make check", "2:sleep 99");
    assertThat(results.get(0))
        .containsEntry("passed", true)
        .containsEntry("target", "npm run lint");
    assertThat(results.get(1)).containsEntry("reason", "`make check` terminó con código 2");
    assertThat(results.get(2)).containsEntry("reason", "Se agotó el tiempo máximo de `sleep 99`");
  }

  private List<Map<String, Object>> run(List<VerificationCheck> checks, TestsRun tests)
      throws Exception {
    return StageChecks.run(
        repo,
        checks,
        base,
        tests,
        (command, index) -> {
          throw new AssertionError("No hay comandos");
        });
  }

  private String reason(VerificationCheck check, TestsRun tests) throws Exception {
    return (String)
        StageChecks.run(
                repo,
                List.of(check),
                base,
                tests,
                (command, index) -> {
                  throw new AssertionError("No hay comandos");
                })
            .getFirst()
            .get("reason");
  }

  private void commit(String message) throws Exception {
    git(repo, "add", ".");
    git(repo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", message);
  }
}
