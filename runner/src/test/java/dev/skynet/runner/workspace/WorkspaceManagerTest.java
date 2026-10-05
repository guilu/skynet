package dev.skynet.runner.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skynet.runner.TestRepos;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceManagerTest {

  @TempDir Path dir;

  @Test
  void createsAWorktreeOnItsOwnBranch() throws Exception {
    Path repo = TestRepos.create(dir.resolve("repo"));
    WorkspaceManager manager = new WorkspaceManager(dir.resolve("workspaces"));
    UUID run = UUID.randomUUID();
    UUID agent = UUID.fromString("0123abcd-0000-4000-8000-000000000000");

    Workspace ws = manager.create(repo, "main", run, "TKM-1", agent);

    assertThat(ws.path())
        .isEqualTo(dir.resolve("workspaces").resolve(run.toString()).resolve(agent.toString()));
    assertThat(ws.branch()).isEqualTo("skynet/tkm-1/0123abcd");
    assertThat(ws.baseCommit()).isEqualTo(TestRepos.git(repo, "rev-parse", "main"));
    assertThat(Files.readString(ws.path().resolve("calc.py"))).contains("return a - b");
    assertThat(TestRepos.git(ws.path(), "branch", "--show-current")).isEqualTo(ws.branch());

    assertThatThrownBy(() -> manager.create(repo, "main", run, "TKM-1", agent))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("ya existe");
  }

  @Test
  void failsClearlyWhenTheRepositoryIsMissingOrTheBranchDoesNotExist() throws Exception {
    WorkspaceManager manager = new WorkspaceManager(dir.resolve("workspaces"));
    assertThatThrownBy(
            () ->
                manager.create(
                    dir.resolve("nope"), "main", UUID.randomUUID(), "X-1", UUID.randomUUID()))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("no existe");

    Path repo = TestRepos.create(dir.resolve("repo"));
    assertThatThrownBy(
            () -> manager.create(repo, "nope", UUID.randomUUID(), "X-1", UUID.randomUUID()))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("git worktree add");
  }

  @Test
  void forkStartsFromTheParentHeadWithItsUncommittedChanges() throws Exception {
    Path repo = TestRepos.create(dir.resolve("repo"));
    WorkspaceManager manager = new WorkspaceManager(dir.resolve("workspaces"));
    UUID run = UUID.randomUUID();
    Workspace parent = manager.create(repo, "main", run, "TKM-1", UUID.randomUUID());
    Files.writeString(parent.path().resolve("calc.py"), "def add(a, b):\n    return a + b\n");
    TestRepos.git(
        parent.path(), "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qam", "fix");
    Files.writeString(
        parent.path().resolve("calc.py"), "# sin confirmar\n", StandardOpenOption.APPEND);
    Files.createDirectories(parent.path().resolve("tests"));
    Files.writeString(parent.path().resolve("tests/test_calc.py"), "nuevo\n");
    Files.writeString(parent.path().resolve(".gitignore"), "*.log\n");
    Files.writeString(parent.path().resolve("debug.log"), "ignorado\n");

    Workspace fork = manager.fork(parent.path(), UUID.randomUUID(), "TKM-1", UUID.randomUUID());

    assertThat(fork.baseCommit()).isEqualTo(TestRepos.git(parent.path(), "rev-parse", "HEAD"));
    assertThat(fork.branch()).isNotEqualTo(parent.branch());
    assertThat(TestRepos.git(fork.path(), "branch", "--show-current")).isEqualTo(fork.branch());
    assertThat(Files.readString(fork.path().resolve("calc.py")))
        .contains("return a + b")
        .contains("# sin confirmar");
    assertThat(fork.path().resolve("tests/test_calc.py")).hasContent("nuevo");
    assertThat(fork.path().resolve("debug.log")).doesNotExist();
    // El worktree del padre no cambia.
    assertThat(Files.readString(parent.path().resolve("calc.py"))).contains("# sin confirmar");
  }

  @Test
  void onlyReusesWorktreesUnderItsRoot() throws Exception {
    Path repo = TestRepos.create(dir.resolve("repo"));
    WorkspaceManager manager = new WorkspaceManager(dir.resolve("workspaces"));
    Workspace created = manager.create(repo, "main", UUID.randomUUID(), "TKM-1", UUID.randomUUID());

    Workspace existing = manager.existing(created.path());
    assertThat(existing.path()).isEqualTo(created.path());
    assertThat(existing.branch()).isEqualTo(created.branch());

    assertThatThrownBy(() -> manager.existing(repo))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("no existe en este runner");
    assertThatThrownBy(() -> manager.existing(dir.resolve("workspaces/../repo")))
        .isInstanceOf(IOException.class);
    assertThatThrownBy(() -> manager.existing(dir.resolve("workspaces/nope")))
        .isInstanceOf(IOException.class);
  }
}
