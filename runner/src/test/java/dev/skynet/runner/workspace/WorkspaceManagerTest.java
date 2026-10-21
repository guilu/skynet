package dev.skynet.runner.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.skynet.runner.TestRepos;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
  void removeDeletesTheWorktreeButKeepsTheBranch() throws Exception {
    Path repo = TestRepos.create(dir.resolve("repo"));
    WorkspaceManager manager = new WorkspaceManager(dir.resolve("workspaces"));
    Workspace ws = manager.create(repo, "main", UUID.randomUUID(), "TKM-1", UUID.randomUUID());
    Files.writeString(ws.path().resolve("sucio.txt"), "sin confirmar\n");

    assertThat(manager.remove(ws.path())).isTrue();

    assertThat(ws.path()).doesNotExist();
    assertThat(TestRepos.git(repo, "worktree", "list")).doesNotContain(ws.path().toString());
    assertThat(TestRepos.git(repo, "branch", "--list", ws.branch())).contains(ws.branch());
    assertThat(manager.remove(ws.path())).isFalse();
    assertThatThrownBy(() -> manager.remove(repo))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("no es de este runner");
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
  void aBaseBranchThatGitWouldReadAsAnOptionIsRefused() throws Exception {
    Path repo = TestRepos.create(dir.resolve("repo"));
    WorkspaceManager manager = new WorkspaceManager(dir.resolve("workspaces"));

    for (String branch : new String[] {"--detach", "-q", "--orphan=x", "main..HEAD", "a b"}) {
      UUID run = UUID.randomUUID();
      assertThatThrownBy(() -> manager.create(repo, branch, run, "X-1", UUID.randomUUID()))
          .as(branch)
          .isInstanceOf(IOException.class)
          .hasMessage("La rama base `" + branch + "` no es un nombre de rama válido");
      assertThat(dir.resolve("workspaces").resolve(run.toString())).doesNotExist();
    }
    assertThat(TestRepos.git(repo, "worktree", "list").lines()).hasSize(1);
  }

  @Test
  void aSnapshotToRestoreMustBeACommitHash() throws Exception {
    Path repo = TestRepos.create(dir.resolve("repo"));
    WorkspaceManager manager = new WorkspaceManager(dir.resolve("workspaces"));
    Workspace ws = manager.create(repo, "main", UUID.randomUUID(), "X-1", UUID.randomUUID());

    assertThatThrownBy(() -> manager.continued(ws.path(), "--hard", UUID.randomUUID()))
        .isInstanceOf(IOException.class)
        .hasMessage("La instantánea `--hard` no es un hash de git");
    assertThatThrownBy(() -> manager.continued(ws.path(), "HEAD~1", UUID.randomUUID()))
        .isInstanceOf(IOException.class)
        .hasMessage("La instantánea `HEAD~1` no es un hash de git");
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
    // Texto que no es UTF-8: el diff tiene que llegar byte a byte.
    byte[] latin1 = "# año\n".getBytes(StandardCharsets.ISO_8859_1);
    Files.write(parent.path().resolve("calc.py"), latin1, StandardOpenOption.APPEND);
    Files.createDirectories(parent.path().resolve("tests"));
    Files.writeString(parent.path().resolve("tests/test_calc.py"), "nuevo\n");
    Files.writeString(parent.path().resolve(".gitignore"), "*.log\n");
    Files.writeString(parent.path().resolve("debug.log"), "ignorado\n");
    Path outside = Files.writeString(dir.resolve("secreto.txt"), "fuera\n");
    Files.createSymbolicLink(parent.path().resolve("enlace"), outside);

    Workspace fork = manager.fork(parent.path(), UUID.randomUUID(), "TKM-1", UUID.randomUUID());

    assertThat(fork.baseCommit()).isEqualTo(TestRepos.git(parent.path(), "rev-parse", "HEAD"));
    assertThat(fork.branch()).isNotEqualTo(parent.branch());
    assertThat(TestRepos.git(fork.path(), "branch", "--show-current")).isEqualTo(fork.branch());
    assertThat(Files.readString(fork.path().resolve("calc.py"), StandardCharsets.ISO_8859_1))
        .contains("return a + b")
        .contains("# sin confirmar");
    assertThat(fork.path().resolve("tests/test_calc.py")).hasContent("nuevo");
    assertThat(fork.path().resolve("calc.py"))
        .hasSameBinaryContentAs(parent.path().resolve("calc.py"));
    assertThat(fork.path().resolve("debug.log")).doesNotExist();
    assertThat(fork.path().resolve("enlace")).isSymbolicLink();
    assertThat(Files.readSymbolicLink(fork.path().resolve("enlace"))).isEqualTo(outside);
    // El worktree del padre no cambia.
    assertThat(Files.readString(parent.path().resolve("calc.py"), StandardCharsets.ISO_8859_1))
        .contains("# sin confirmar");
  }

  @Test
  void aRetryRestoresTheWorktreeAsTheFailedAttemptFoundIt() throws Exception {
    Path repo = TestRepos.create(dir.resolve("repo"));
    WorkspaceManager manager = new WorkspaceManager(dir.resolve("workspaces"));
    Workspace created = manager.create(repo, "main", UUID.randomUUID(), "TKM-1", UUID.randomUUID());
    Path path = created.path();
    // Lo que dejó la fase anterior: un cambio sin confirmar, un fichero nuevo y otro borrado.
    Files.writeString(
        path.resolve("calc.py"), "# de la fase anterior\n", StandardOpenOption.APPEND);
    Files.writeString(path.resolve("nuevo.txt"), "nuevo\n");
    Files.writeString(path.resolve(".gitignore"), "*.log\n");
    String before = Files.readString(path.resolve("calc.py"));

    UUID attempt = UUID.randomUUID();
    Workspace first = manager.continued(path, null, attempt);
    assertThat(first.snapshot()).isNotBlank();
    assertThat(TestRepos.git(path, "rev-parse", "refs/skynet/snapshots/" + attempt))
        .isEqualTo(first.snapshot());
    // El intento falla a medias: confirma algo, cambia ficheros y deja otros nuevos.
    Files.writeString(path.resolve("calc.py"), "roto\n");
    TestRepos.git(path, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qam", "a medias");
    Files.delete(path.resolve("nuevo.txt"));
    Files.writeString(path.resolve("basura.py"), "basura\n");
    Files.writeString(path.resolve("salida.log"), "ignorado\n");

    Workspace retry = manager.continued(path, first.snapshot(), UUID.randomUUID());

    assertThat(retry.baseCommit()).isEqualTo(first.baseCommit());
    assertThat(Files.readString(path.resolve("calc.py"))).isEqualTo(before);
    assertThat(path.resolve("nuevo.txt")).hasContent("nuevo");
    assertThat(path.resolve("basura.py")).doesNotExist();
    assertThat(path.resolve("salida.log")).exists();
    // Los cambios vuelven a estar sin confirmar, como los dejó la fase anterior.
    assertThat(TestRepos.git(path, "status", "--porcelain"))
        .contains("M calc.py")
        .contains("?? nuevo.txt");
    assertThat(retry.snapshot()).isNotEqualTo(first.snapshot());
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
    Path escaping = dir.resolve("workspaces/../repo");
    assertThatThrownBy(() -> manager.existing(escaping)).isInstanceOf(IOException.class);
    Path missing = dir.resolve("workspaces/nope");
    assertThatThrownBy(() -> manager.existing(missing)).isInstanceOf(IOException.class);
  }
}
