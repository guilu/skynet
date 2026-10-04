package dev.skynet.runner.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skynet.runner.TestRepos;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
