package dev.skynet.runner.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManifestsTest {

  @TempDir Path dir;

  @Test
  void npmWithPnpmAndARealTestScript() throws Exception {
    Files.writeString(
        dir.resolve("package.json"),
        """
        {"name": "@guilu/akademia", "description": "Cursos en línea", "scripts": {"test": "vitest run"}}
        """);
    Files.writeString(dir.resolve("pnpm-lock.yaml"), "");
    Manifests.Facts facts = Manifests.read(dir);
    assertThat(facts.tool()).isEqualTo("pnpm");
    assertThat(facts.name()).isEqualTo("akademia");
    assertThat(facts.nameSource()).isEqualTo("package.json");
    assertThat(facts.description()).isEqualTo("Cursos en línea");
    assertThat(facts.validationCommand()).isEqualTo("pnpm test");
    assertThat(facts.testReportPaths()).isEmpty();
  }

  @Test
  void theNpmPlaceholderTestIsNoValidation() throws Exception {
    Files.writeString(
        dir.resolve("package.json"),
        """
        {"name": "x", "scripts": {"test": "echo \\"Error: no test specified\\" && exit 1"}}
        """);
    assertThat(Manifests.read(dir).validationCommand()).isNull();
    assertThat(Manifests.read(dir).tool()).isEqualTo("npm");
  }

  @Test
  void mavenIgnoresTheParentName() throws Exception {
    Files.writeString(
        dir.resolve("pom.xml"),
        """
        <project>
          <parent><artifactId>spring-boot-starter-parent</artifactId><name>Padre</name></parent>
          <artifactId>tokenmeter</artifactId>
          <description>Mide tokens</description>
        </project>
        """);
    Files.writeString(dir.resolve("mvnw"), "");
    Manifests.Facts facts = Manifests.read(dir);
    assertThat(facts.tool()).isEqualTo("maven");
    assertThat(facts.name()).isEqualTo("tokenmeter");
    assertThat(facts.description()).isEqualTo("Mide tokens");
    assertThat(facts.validationCommand()).isEqualTo("./mvnw -q verify");
    assertThat(facts.testReportPaths()).containsExactly("**/target/surefire-reports/*.xml");
  }

  @Test
  void pythonCargoAndGo() throws Exception {
    Files.writeString(
        dir.resolve("pyproject.toml"),
        "[build-system]\nrequires = []\n\n[project]\nname = \"tokenmeter\"\ndescription = 'Cuenta'\n");
    Manifests.Facts py = Manifests.read(dir);
    assertThat(py.tool()).isEqualTo("python");
    assertThat(py.name()).isEqualTo("tokenmeter");
    assertThat(py.description()).isEqualTo("Cuenta");
    assertThat(py.validationCommand()).isEqualTo("pytest");

    Path rust = Files.createDirectories(dir.resolve("rust"));
    Files.writeString(rust.resolve("Cargo.toml"), "[package]\nname = \"herr\"\n");
    assertThat(Manifests.read(rust).validationCommand()).isEqualTo("cargo test");
    assertThat(Manifests.read(rust).name()).isEqualTo("herr");

    Path go = Files.createDirectories(dir.resolve("go"));
    Files.writeString(go.resolve("go.mod"), "module github.com/guilu/tokenmeter\n\ngo 1.23\n");
    assertThat(Manifests.read(go).name()).isEqualTo("tokenmeter");
    assertThat(Manifests.read(go).validationCommand()).isEqualTo("go test ./...");
  }

  @Test
  void gradleWinsOverAWebPackageJsonButTheDescriptionStillComesFromIt() throws Exception {
    Files.writeString(dir.resolve("build.gradle.kts"), "");
    Files.writeString(dir.resolve("package.json"), "{\"description\": \"La web\"}");
    Manifests.Facts facts = Manifests.read(dir);
    assertThat(facts.tool()).isEqualTo("gradle");
    assertThat(facts.validationCommand()).isEqualTo("gradle check");
    assertThat(facts.description()).isEqualTo("La web");
  }

  @Test
  void neverReadsDotEnv() throws Exception {
    Files.writeString(dir.resolve(".env"), "SECRET=1\n");
    Manifests.Facts facts = Manifests.read(dir);
    assertThat(facts.description()).isNull();
    assertThat(facts.name()).isNull();
  }

  @Test
  void firstParagraphSkipsTitlesBadgesAndCode() {
    String readme =
        """
        Proyecto
        ========

        <p align="center"><img src="logo.png"></p>

        ```sh
        npm install
        ```

        Una `herramienta` para *medir* tokens.

        Más texto.
        """;
    assertThat(Manifests.firstParagraph(readme)).isEqualTo("Una herramienta para medir tokens.");
    assertThat(Manifests.firstParagraph("# Solo título\n")).isNull();
  }

  @Test
  void longDescriptionsAreCutAtAWord() {
    String text = "palabra ".repeat(100);
    String cut = Manifests.shorten(text);
    assertThat(cut).hasSizeLessThanOrEqualTo(Manifests.MAX_DESCRIPTION).endsWith("palabra…");
  }
}
