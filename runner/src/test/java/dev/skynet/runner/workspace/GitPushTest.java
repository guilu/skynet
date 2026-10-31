package dev.skynet.runner.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skynet.protocol.runner.PushBranch;
import dev.skynet.runner.TestRepos;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitPushTest {

  static final String TOKEN = "ghs_pushTokenQueNoDebeQuedarEnNingunSitio0";

  @TempDir Path dir;

  private Path remote() throws Exception {
    Path remote = dir.resolve("remote.git");
    TestRepos.git(dir, "init", "-q", "--bare", remote.toString());
    return remote;
  }

  private Workspace workspace() throws Exception {
    Path repo = TestRepos.create(dir.resolve("repo"));
    return new WorkspaceManager(dir.resolve("workspaces"))
        .create(repo, "main", UUID.randomUUID(), "DEMO-7", UUID.randomUUID());
  }

  private static PushBranch push(Path remote, Workspace ws, boolean force) {
    return new PushBranch(
        remote.toUri().toString(), "skynet/demo-7", ws.baseCommit(), force, "DEMO-7: cambios");
  }

  @Test
  void commitsWhatIsPendingAndPushesHeadToTheBranch() throws Exception {
    Path remote = remote();
    Workspace ws = workspace();
    Files.writeString(ws.path().resolve("calc.py"), "def sub(a, b):\n    return a - b - 0\n");
    Files.writeString(ws.path().resolve("nuevo.txt"), "nuevo\n");

    GitPush.Result result = GitPush.push(ws.path(), push(remote, ws, false), TOKEN);

    assertThat(result.committed()).isTrue();
    assertThat(result.commits()).isEqualTo(1);
    assertThat(result.sha()).isEqualTo(TestRepos.git(ws.path(), "rev-parse", "HEAD"));
    assertThat(TestRepos.git(remote, "rev-parse", "refs/heads/skynet/demo-7"))
        .isEqualTo(result.sha());
    assertThat(TestRepos.git(ws.path(), "status", "--porcelain")).isEmpty();
    assertThat(TestRepos.git(ws.path(), "log", "-1", "--format=%s")).isEqualTo("DEMO-7: cambios");
  }

  @Test
  void theTokenNeverReachesTheRepositoryConfigOrTheOutput() throws Exception {
    Path remote = remote();
    Workspace ws = workspace();
    Files.writeString(ws.path().resolve("nuevo.txt"), "nuevo\n");

    GitPush.Result result = GitPush.push(ws.path(), push(remote, ws, false), TOKEN);

    assertThat(result.output()).doesNotContain(TOKEN);
    Path common = Path.of(TestRepos.git(ws.path(), "rev-parse", "--git-common-dir"));
    Path gitDir = common.isAbsolute() ? common : ws.path().resolve(common);
    try (Stream<Path> files = Files.walk(gitDir)) {
      for (Path file : files.filter(Files::isRegularFile).toList()) {
        assertThat(Files.readAllBytes(file)).asString().doesNotContain(TOKEN);
      }
    }
    assertThat(GitPush.environment(TOKEN).get("GIT_CONFIG_VALUE_1"))
        .startsWith("Authorization: Basic ")
        .doesNotContain(TOKEN);
  }

  @Test
  void aSecondPushAddsCommitsToTheSameBranch() throws Exception {
    Path remote = remote();
    Workspace ws = workspace();
    Files.writeString(ws.path().resolve("a.txt"), "1\n");
    GitPush.push(ws.path(), push(remote, ws, false), TOKEN);
    Files.writeString(ws.path().resolve("a.txt"), "2\n");
    TestRepos.git(
        ws.path(), "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qam", "segundo");

    GitPush.Result second = GitPush.push(ws.path(), push(remote, ws, false), TOKEN);

    assertThat(second.committed()).isFalse();
    assertThat(second.commits()).isEqualTo(2);
    assertThat(TestRepos.git(remote, "rev-parse", "refs/heads/skynet/demo-7"))
        .isEqualTo(second.sha());
  }

  @Test
  void rewrittenHistoryNeedsForce() throws Exception {
    Path remote = remote();
    Workspace ws = workspace();
    Files.writeString(ws.path().resolve("a.txt"), "1\n");
    GitPush.push(ws.path(), push(remote, ws, false), TOKEN);
    TestRepos.git(ws.path(), "reset", "-q", "--hard", ws.baseCommit());
    Files.writeString(ws.path().resolve("b.txt"), "otro\n");

    assertThatThrownBy(() -> GitPush.push(ws.path(), push(remote, ws, false), TOKEN))
        .isInstanceOf(GitPush.PushException.class)
        .hasMessageContaining("git push");
    GitPush.Result forced = GitPush.push(ws.path(), push(remote, ws, true), TOKEN);
    assertThat(TestRepos.git(remote, "rev-parse", "refs/heads/skynet/demo-7"))
        .isEqualTo(forced.sha());
  }

  @Test
  void refusesChangesToTheWorkflowsAndPushesNothing() throws Exception {
    Path remote = remote();
    Workspace ws = workspace();
    Files.createDirectories(ws.path().resolve(".github/workflows"));
    Files.writeString(ws.path().resolve(".github/workflows/ci.yml"), "on: push\n");

    assertThatThrownBy(() -> GitPush.push(ws.path(), push(remote, ws, false), TOKEN))
        .isInstanceOf(GitPush.PushException.class)
        .hasMessageContaining(".github/workflows/ci.yml")
        .hasMessageContaining("Workflows");
    assertThat(TestRepos.git(remote, "for-each-ref")).isEmpty();
  }

  @Test
  void nothingNewIsAnError() throws Exception {
    Workspace ws = workspace();
    assertThatThrownBy(() -> GitPush.push(ws.path(), push(remote(), ws, false), TOKEN))
        .isInstanceOf(GitPush.PushException.class)
        .hasMessageContaining("No hay ningún cambio");
  }

  @Test
  void onlyHttpsOrALocalRemoteWithoutCredentials() {
    String base = "0123456789abcdef0123456789abcdef01234567";
    for (String url :
        List.of(
            "http://github.com/o/r.git",
            "git@github.com:o/r.git",
            "https://user:pass@github.com/o/r.git",
            "ext::sh -c touch% /tmp/x")) {
      assertThatThrownBy(() -> GitPush.check(new PushBranch(url, "skynet/x", base, false, "m")))
          .as(url)
          .isInstanceOf(GitPush.PushException.class);
    }
    assertThatThrownBy(
            () ->
                GitPush.check(new PushBranch("https://github.com/o/r.git", "-f", base, false, "m")))
        .hasMessageContaining("rama");
    assertThatThrownBy(() -> new PushBranch("https://github.com/o/r.git", "main", base, true, "m"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void scrubsTheTokenFromErrors() {
    assertThat(GitPush.scrub("fatal: " + TOKEN + " rechazado", TOKEN))
        .isEqualTo("fatal: [REDACTED] rechazado");
  }
}
