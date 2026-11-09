package dev.skynet.runner.verify;

import static dev.skynet.runner.TestRepos.git;
import static org.assertj.core.api.Assertions.assertThat;

import dev.skynet.runner.TestRepos;
import dev.skynet.runner.workspace.FileChanges;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RiskClassifierTest {

  @TempDir Path dir;

  private Path repo;
  private String base;

  @BeforeEach
  void setUp() throws Exception {
    repo = TestRepos.create(dir.resolve("repo"));
    base = git(repo, "rev-parse", "HEAD");
  }

  private static FileChanges.Lines added(String... lines) {
    return new FileChanges.Lines(List.of(lines), lines.length, 0);
  }

  @Test
  void onlyDocsIsLevelZero() {
    RiskClassifier.Result result =
        RiskClassifier.classify(
            List.of("README.md", "docs/guia.md", "plans/x.png"),
            Map.of("README.md", added("CREATE TABLE en un ejemplo")),
            List.of(),
            List.of());

    assertThat(result.tier()).isZero();
    assertThat(result.lines()).isZero();
    assertThat(result.summary()).isEqualTo("Nivel 0: solo documentación");
  }

  @Test
  void ordinaryCodeIsLevelOneAndTestsDoNotCountLines() {
    RiskClassifier.Result result =
        RiskClassifier.classify(
            List.of("src/main/java/demo/Calc.java", "src/test/java/demo/CalcTest.java"),
            Map.of(
                "src/main/java/demo/Calc.java",
                new FileChanges.Lines(List.of("a", "b"), 2, 1),
                "src/test/java/demo/CalcTest.java",
                added("String password = \"x\";")),
            List.of(),
            List.of());

    assertThat(result.tier()).isEqualTo(1);
    assertThat(result.lines()).isEqualTo(3);
    assertThat(result.summary()).isEqualTo("Nivel 1: 3 líneas de código");
  }

  @Test
  void level2PathsRaiseTheTier() {
    RiskClassifier.Result result =
        RiskClassifier.classify(
            List.of(
                "src/main/java/demo/Calc.java",
                "control-plane/src/main/resources/db/migration/V49__risk.sql"),
            Map.of(),
            List.of(),
            List.of());

    assertThat(result.tier()).isEqualTo(2);
    assertThat(result.reasons().getFirst().path())
        .isEqualTo("control-plane/src/main/resources/db/migration/V49__risk.sql");
    assertThat(result.summary())
        .isEqualTo("Nivel 2: `control-plane/src/main/resources/db/migration/V49__risk.sql` (ruta)");
  }

  @Test
  void rootGlobsMatchTooAndDefaultsCoverCiAndDependencies() {
    for (String path :
        List.of(
            ".github/workflows/ci.yml",
            "build.gradle.kts",
            "web/package.json",
            "workflows/implementar-tarea.yaml",
            "deploy/compose.yaml",
            "src/main/java/demo/auth/Login.java",
            ".env.local")) {
      assertThat(RiskClassifier.classify(List.of(path), Map.of(), List.of(), List.of()).tier())
          .as(path)
          .isEqualTo(2);
    }
  }

  @Test
  void contentPatternsRaiseCodeButNotPlainTokens() {
    assertThat(
            RiskClassifier.classify(
                    List.of("src/App.java"),
                    Map.of("src/App.java", added("http.authorizeHttpRequests(a -> a)")),
                    List.of(),
                    List.of())
                .reasons()
                .getFirst()
                .label())
        .isEqualTo("autenticación");
    assertThat(
            RiskClassifier.classify(
                    List.of("src/Usage.java"),
                    Map.of("src/Usage.java", added("long tokens = usage.inputTokens();")),
                    List.of(),
                    List.of())
                .tier())
        .isEqualTo(1);
  }

  @Test
  void repositoryRulesOnlyAdd() {
    RiskClassifier.Result result =
        RiskClassifier.classify(
            List.of("billing/Invoice.java", "src/Pay.java"),
            Map.of("src/Pay.java", added("stripe.charge(amount)")),
            List.of("billing/**"),
            List.of("stripe\\.", "(sin cerrar"));

    assertThat(result.tier()).isEqualTo(2);
    assertThat(result.reasons()).extracting(RiskClassifier.Reason::tier).containsOnly(2);
    assertThat(result.warnings())
        .containsExactly("Patrón de riesgo no válido, no se usa: `(sin cerrar`");
  }

  @Test
  void assessesTheWorktreeAgainstItsBase() throws Exception {
    Files.writeString(repo.resolve("calc.py"), "def add(a, b):\n    return a + b\n");
    Files.createDirectories(repo.resolve("db/migration"));
    Files.writeString(repo.resolve("db/migration/V2__x.sql"), "-- nada\n");
    git(repo, "add", "calc.py");
    git(repo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "fix");

    RiskClassifier.Result result = RiskClassifier.assess(repo, base, List.of(), List.of());

    assertThat(result.tier()).isEqualTo(2);
    assertThat(result.files()).isEqualTo(2);
    assertThat(result.lines()).isEqualTo(3);
  }

  @Test
  void removedSqlCommentsAreNotReadAsHeaders() throws Exception {
    Files.writeString(repo.resolve("q.sql"), "-- uno\nselect 1;\n");
    git(repo, "add", "q.sql");
    git(repo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "sql");
    String head = git(repo, "rev-parse", "HEAD");
    Files.writeString(repo.resolve("q.sql"), "select 1;\n");

    Map<String, FileChanges.Lines> lines = FileChanges.lines(repo, head, 1024);

    assertThat(lines).containsOnlyKeys("q.sql");
    assertThat(lines.get("q.sql").removed()).isEqualTo(1);
  }
}
