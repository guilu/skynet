package dev.skynet.runner.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skynet.protocol.runner.DiscoveredRepository;
import dev.skynet.protocol.runner.RepositoryCatalog;
import dev.skynet.runner.TestRepos;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryScannerTest {

  @TempDir Path dir;

  private static DiscoveredRepository named(RepositoryCatalog catalog, String name) {
    return catalog.repositories().stream()
        .filter(r -> r.name().equals(name))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void findsRepositoriesUpToTwoLevelsAndSkipsWhatItShouldNot() throws Exception {
    Path code = dir.resolve("code");
    TestRepos.create(code.resolve("forma"));
    TestRepos.create(code.resolve("clientes/akademia"));
    TestRepos.create(code.resolve("a/b/demasiado-hondo"));
    TestRepos.create(code.resolve(".oculto"));
    TestRepos.create(code.resolve("node_modules/paquete"));
    // Un repositorio dentro de otro no se busca: es parte de él.
    TestRepos.create(code.resolve("forma/sub"));
    // Un worktree tiene un fichero .git, no un directorio: no cuenta.
    TestRepos.git(code.resolve("forma"), "worktree", "add", "-q", code.resolve("wt").toString());
    Files.createDirectories(code.resolve("vacia"));

    RepositoryCatalog catalog = new RepositoryScanner(List.of(code), null).scan();

    assertThat(catalog.repositories())
        .extracting(DiscoveredRepository::name)
        .containsExactly("akademia", "forma");
    assertThat(catalog.roots()).containsExactly(code.toString());
    assertThat(catalog.truncated()).isFalse();
  }

  @Test
  void stopsAtTheMaximumAndSaysSo() throws Exception {
    Path code = dir.resolve("code");
    for (String n : List.of("a", "b", "c")) {
      TestRepos.create(code.resolve(n));
    }
    RepositoryCatalog catalog = new RepositoryScanner(List.of(code), null, 2).scan();
    assertThat(catalog.repositories()).hasSize(2);
    assertThat(catalog.truncated()).isTrue();
  }

  @Test
  void neverLooksInsideTheRunnerHome() throws Exception {
    Path home = dir.resolve("runner-home");
    TestRepos.create(dir.resolve("forma"));
    TestRepos.create(home.resolve("x"));
    RepositoryCatalog catalog = new RepositoryScanner(List.of(dir), home).scan();
    assertThat(catalog.repositories())
        .extracting(DiscoveredRepository::name)
        .containsExactly("forma");
  }

  @Test
  void aMissingRootIsIgnored() throws Exception {
    RepositoryCatalog catalog =
        new RepositoryScanner(List.of(dir.resolve("no-existe")), null).scan();
    assertThat(catalog.repositories()).isEmpty();
  }

  @Test
  void readsRemoteBranchCommitAndGradleFacts() throws Exception {
    Path repo = TestRepos.create(dir.resolve("forma"));
    Files.writeString(repo.resolve("settings.gradle.kts"), "rootProject.name = \"forma-app\"\n");
    Files.writeString(repo.resolve("gradlew"), "#!/bin/sh\n");
    Files.createDirectories(repo.resolve(".github/workflows"));
    Files.writeString(repo.resolve("CLAUDE.md"), "# Notas\n");
    Files.writeString(
        repo.resolve("README.md"),
        "# Forma\n\n[![CI](https://x/badge.svg)](https://x)\n\nGestor de **formularios**"
            + " con [validación](https://x).\nSegunda línea.\n\n## Uso\n");
    TestRepos.git(
        repo, "remote", "add", "origin", "https://usuario:ghp_secreto@github.com/guilu/forma.git");

    DiscoveredRepository found = RepositoryScanner.inspect(repo.toRealPath());

    assertThat(found.path()).isEqualTo(repo.toRealPath().toString());
    assertThat(found.name()).isEqualTo("forma");
    assertThat(found.projectName()).isEqualTo("forma-app");
    assertThat(found.projectNameSource()).isEqualTo("settings.gradle.kts");
    assertThat(found.description())
        .isEqualTo("Gestor de formularios con validación. Segunda línea.");
    assertThat(found.descriptionSource()).isEqualTo("README.md");
    assertThat(found.remoteUrl()).isEqualTo("https://github.com/guilu/forma.git");
    assertThat(found.defaultBranch()).isEqualTo("main");
    assertThat(found.defaultBranchSource()).isEqualTo("current");
    assertThat(found.tool()).isEqualTo("gradle");
    assertThat(found.validationCommand()).isEqualTo("./gradlew check");
    assertThat(found.testReportPaths()).containsExactly("**/build/test-results/**/*.xml");
    assertThat(found.protectedPaths()).containsExactly(".github/workflows/**");
    assertThat(found.lastCommit()).isEqualTo(TestRepos.git(repo, "rev-parse", "HEAD"));
    assertThat(found.lastCommitSubject()).isEqualTo("init");
    assertThat(found.lastCommitAt()).isNotNull();
    assertThat(found.dirty()).isTrue();
    assertThat(found.agentInstructions()).isTrue();
  }

  @Test
  void theDefaultBranchComesFromOriginHead() throws Exception {
    Path upstream = TestRepos.create(dir.resolve("upstream"));
    TestRepos.git(upstream, "branch", "-q", "-m", "main", "trunk");
    Path clone = dir.resolve("clon");
    TestRepos.git(dir, "clone", "-q", upstream.toString(), clone.toString());
    TestRepos.git(clone, "checkout", "-q", "-b", "trabajo");

    DiscoveredRepository found = RepositoryScanner.inspect(clone.toRealPath());

    assertThat(found.defaultBranch()).isEqualTo("trunk");
    assertThat(found.defaultBranchSource()).isEqualTo("origin/HEAD");
    assertThat(found.remoteUrl()).isEqualTo(upstream.toString());
    assertThat(found.dirty()).isFalse();
    assertThat(found.agentInstructions()).isFalse();
  }

  @Test
  void withoutRemotesOrManifestItFallsBackToTheFolder() throws Exception {
    Path repo = dir.resolve("notas");
    Files.createDirectories(repo);
    TestRepos.git(repo, "init", "-q", "-b", "main");

    DiscoveredRepository found = RepositoryScanner.inspect(repo.toRealPath());

    assertThat(found.remoteUrl()).isNull();
    assertThat(found.projectName()).isEqualTo("notas");
    assertThat(found.projectNameSource()).isEqualTo("directory");
    assertThat(found.tool()).isNull();
    assertThat(found.validationCommand()).isNull();
    assertThat(found.lastCommit()).isNull();
    assertThat(found.defaultBranch()).isEqualTo("main");
  }
}
