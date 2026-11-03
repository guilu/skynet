package dev.skynet.runner.verify;

import static dev.skynet.runner.TestRepos.git;
import static org.assertj.core.api.Assertions.assertThat;

import dev.skynet.runner.TestRepos;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestIntegrityTest {

  private static final String CALC_TEST =
      """
      package demo;

      import static org.junit.jupiter.api.Assertions.assertEquals;
      import org.junit.jupiter.api.Test;
      import org.junit.jupiter.params.ParameterizedTest;
      import org.junit.jupiter.params.provider.ValueSource;

      class CalculadoraTest {

        @Test
        void suma() {
          assertEquals(3, new Calculadora().suma(1, 2));
        }

        @Test
        void divideEntreCero() {
          assertEquals(0, new Calculadora().divide(1, 0));
        }

        @ParameterizedTest
        @ValueSource(ints = {1, 2})
        void resta(int n) {
          assertEquals(0, new Calculadora().resta(n, n));
        }
      }
      """;

  @TempDir Path dir;

  private Path repo;
  private String base;

  @BeforeEach
  void setUp() throws Exception {
    repo = TestRepos.create(dir.resolve("repo"));
    Files.createDirectories(repo.resolve("src/test/java/demo"));
    Files.writeString(repo.resolve(TEST_FILE), CALC_TEST);
    commit("tests");
    base = git(repo, "rev-parse", "HEAD");
  }

  private static final String TEST_FILE = "src/test/java/demo/CalculadoraTest.java";

  @Test
  void recognisesTestFilesByLanguage() {
    assertThat(TestIntegrity.isTestFile("src/test/java/demo/CalculadoraTest.java")).isTrue();
    assertThat(TestIntegrity.isTestFile("app/src/integrationTest/kotlin/ApiIT.kt")).isTrue();
    assertThat(TestIntegrity.isTestFile("web/src/format.test.ts")).isTrue();
    assertThat(TestIntegrity.isTestFile("web/e2e/run.e2e.ts")).isTrue();
    assertThat(TestIntegrity.isTestFile("tests/test_calc.py")).isTrue();
    assertThat(TestIntegrity.isTestFile("calc_test.go")).isTrue();
    assertThat(TestIntegrity.isTestFile("src/main/java/demo/Calculadora.java")).isFalse();
    assertThat(TestIntegrity.isTestFile("web/src/format.ts")).isFalse();
    assertThat(TestIntegrity.isTestFile("tests/fixtures/data.json")).isFalse();
    assertThat(TestIntegrity.isTestFile("calc.py")).isFalse();
  }

  @Test
  void nothingChangedPasses() throws Exception {
    TestIntegrity.Result result = TestIntegrity.check(repo, base);

    assertThat(result.problems()).isEmpty();
    assertThat(result.reason()).isNull();
  }

  @Test
  void deletingTheFailingTestFails() throws Exception {
    Files.writeString(
        repo.resolve(TEST_FILE),
        CALC_TEST.replace(
            """
              @Test
              void divideEntreCero() {
                assertEquals(0, new Calculadora().divide(1, 0));
              }

            """,
            ""));
    commit("quita el test que falla");

    assertThat(TestIntegrity.check(repo, base).reason())
        .isEqualTo("Se ha borrado el test `CalculadoraTest.divideEntreCero`");
  }

  @Test
  void deletingTheWholeFileFails() throws Exception {
    git(repo, "rm", "-q", TEST_FILE);
    commit("fuera tests");

    assertThat(TestIntegrity.check(repo, base).reason())
        .isEqualTo("Se ha borrado el fichero de tests `" + TEST_FILE + "` (3 tests)");
  }

  @Test
  void uncommittedDeletionCountsToo() throws Exception {
    Files.delete(repo.resolve(TEST_FILE));

    assertThat(TestIntegrity.check(repo, base).problems()).hasSize(1);
  }

  @Test
  void disablingATestFails() throws Exception {
    Files.writeString(
        repo.resolve(TEST_FILE),
        CALC_TEST.replace(
            "  @Test\n  void divideEntreCero",
            "  @org.junit.jupiter.api.Disabled\n  @Test\n  void divideEntreCero"));
    commit("desactiva");

    assertThat(TestIntegrity.check(repo, base).reason())
        .isEqualTo("Se ha añadido `@Disabled` en `" + TEST_FILE + "`");
  }

  @Test
  void movingATestFileOutOfTheTestsFails() throws Exception {
    Files.createDirectories(repo.resolve("attic"));
    git(repo, "mv", TEST_FILE, "attic/Calculadora.txt");
    commit("aparta");

    assertThat(TestIntegrity.check(repo, base).reason())
        .isEqualTo(
            "Se ha sacado `" + TEST_FILE + "` de los tests (ahora es `attic/Calculadora.txt`)");
  }

  @Test
  void renamingInsideTheTestsAndAddingTestsPasses() throws Exception {
    git(repo, "mv", TEST_FILE, "src/test/java/demo/CalculoTest.java");
    Files.writeString(
        repo.resolve("src/test/java/demo/CalculoTest.java"),
        CALC_TEST
            .replace("CalculadoraTest", "CalculoTest")
            .replace(
                "  @ParameterizedTest",
                "  @Test\n  void multiplica() {\n    assertEquals(4, new"
                    + " Calculadora().multiplica(2, 2));\n  }\n\n  @ParameterizedTest"));
    commit("renombra y añade");

    TestIntegrity.Result result = TestIntegrity.check(repo, base);

    assertThat(result.problems()).isEmpty();
    assertThat(result.warnings()).isEmpty();
  }

  @Test
  void fewerAssertionsOnlyWarns() throws Exception {
    Files.writeString(
        repo.resolve(TEST_FILE),
        CALC_TEST.replace("assertEquals(3, new Calculadora().suma(1, 2));", "new Calculadora();"));
    commit("sin aserción");

    TestIntegrity.Result result = TestIntegrity.check(repo, base);

    assertThat(result.problems()).isEmpty();
    assertThat(result.warnings())
        .containsExactly("Hay menos aserciones en `" + TEST_FILE + "` (de 3 a 2)");
  }

  @Test
  void jsSkipsAndOnlyFail() {
    List<String> problems = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    String before =
        """
        describe('formato', () => {
          it('pone el euro', () => { expect(f(1)).toBe('1 €') })
          it('redondea', () => { expect(f(1.005)).toBe('1,01 €') })
        })
        """;

    TestIntegrity.compare(
        "web/src/format.test.ts",
        before,
        before.replace("it('redondea'", "it.skip('redondea'").replace("it('pone", "it.only('pone"),
        problems,
        warnings);

    assertThat(problems)
        .containsExactly(
            "Se ha añadido `.skip` en `web/src/format.test.ts`",
            "Se ha añadido `.only` en `web/src/format.test.ts`");
  }

  @Test
  void jsRemovedCaseIsNamed() {
    List<String> problems = new ArrayList<>();
    String before =
        """
        test('pone el euro', () => { expect(f(1)).toBe('1 €') })
        test("redondea", () => { expect(f(1.005)).toBe('1,01 €') })
        """;

    TestIntegrity.compare(
        "web/src/format.test.ts",
        before,
        "test('pone el euro', () => { expect(f(1)).toBe('1 €') })\n",
        problems,
        new ArrayList<>());

    assertThat(problems).containsExactly("Se ha borrado el test `redondea`");
  }

  @Test
  void pythonAndGoSkipsFail() {
    List<String> problems = new ArrayList<>();
    TestIntegrity.compare(
        "tests/test_calc.py",
        "def test_add():\n    assert add(1, 2) == 3\n",
        "import pytest\n\n@pytest.mark.skip\ndef test_add():\n    assert add(1, 2) == 3\n",
        problems,
        new ArrayList<>());
    TestIntegrity.compare(
        "calc_test.go",
        "func TestAdd(t *testing.T) {\n\tif Add(1, 2) != 3 {\n\t\tt.Fatal(\"mal\")\n\t}\n}\n",
        "func TestAdd(t *testing.T) {\n\tt.Skip(\"luego\")\n\tif Add(1, 2) != 3 {\n\t\tt.Fatal(\"mal\")\n\t}\n}\n",
        problems,
        new ArrayList<>());

    assertThat(problems)
        .containsExactly(
            "Se ha añadido `@pytest.mark.skip` en `tests/test_calc.py`",
            "Se ha añadido `t.Skip()` en `calc_test.go`");
  }

  private void commit(String message) throws Exception {
    git(repo, "add", "-A");
    git(repo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", message);
  }
}
