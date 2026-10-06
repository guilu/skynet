package dev.skynet.runner.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skynet.runner.TestRepos;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitIndexerTest {

  @TempDir Path dir;

  @Test
  void indexesCommitsUncommittedChangesNewFilesRenamesAndBinariesWithoutTouchingTheIndex()
      throws Exception {
    Path repo = TestRepos.create(dir.resolve("repo"));
    Files.writeString(repo.resolve("old.txt"), "uno\ndos\ntres\ncuatro\n");
    TestRepos.git(repo, "add", ".");
    commit(repo, "old");
    String base = TestRepos.git(repo, "rev-parse", "HEAD");

    Files.writeString(repo.resolve("calc.py"), "def add(a, b):\n    return a + b\n");
    commit(repo, "fix add", "-a");
    TestRepos.git(repo, "mv", "old.txt", "new.txt");
    Files.writeString(repo.resolve("notes.md"), "sin confirmar\n");
    Files.write(repo.resolve("logo.bin"), new byte[] {0, 1, 2, 0, (byte) 255});
    Files.writeString(repo.resolve(".gitignore"), "*.log\n");
    Files.writeString(repo.resolve("debug.log"), "ignorado\n");
    String statusBefore = TestRepos.git(repo, "status", "--porcelain");

    Path diff = dir.resolve("changes.diff");
    GitIndexer.Changes changes = GitIndexer.index(repo, base, diff);

    assertThat(TestRepos.git(repo, "status", "--porcelain")).isEqualTo(statusBefore);
    Map<String, Object> summary = changes.summary();
    assertThat(summary)
        .containsEntry("branch", "main")
        .containsEntry("baseCommit", base)
        .containsEntry("headCommit", TestRepos.git(repo, "rev-parse", "HEAD"))
        .containsEntry("commits", 1)
        .containsEntry("files", 5)
        .containsEntry("uncommittedFiles", 4);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> commits =
        (List<Map<String, Object>>) changes.changes().get("commits");
    assertThat(commits)
        .singleElement()
        .satisfies(c -> assertThat(c).containsEntry("subject", "fix add"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> files = (List<Map<String, Object>>) changes.changes().get("files");
    assertThat(files)
        .extracting(f -> f.get("path"))
        .containsExactlyInAnyOrder("calc.py", "new.txt", "notes.md", "logo.bin", ".gitignore");
    Map<String, Object> calc = file(files, "calc.py");
    assertThat(calc)
        .containsEntry("status", "M")
        .containsEntry("insertions", 1L)
        .containsEntry("deletions", 1L);
    assertThat(file(files, "new.txt"))
        .containsEntry("status", "R")
        .containsEntry("oldPath", "old.txt");
    assertThat(file(files, "notes.md"))
        .containsEntry("status", "A")
        .containsEntry("insertions", 1L);
    assertThat(file(files, "logo.bin")).containsEntry("binary", true);

    byte[] bytes = Files.readAllBytes(diff);
    long total = 0;
    for (Map<String, Object> f : files) {
      int offset = Math.toIntExact((Long) f.get("diffOffset"));
      int length = Math.toIntExact((Long) f.get("diffLength"));
      String piece =
          new String(Arrays.copyOfRange(bytes, offset, offset + length), StandardCharsets.UTF_8);
      assertThat(piece).startsWith("diff --git ").contains((String) f.get("path"));
      total += length;
    }
    assertThat(total).isEqualTo(bytes.length);
    assertThat(new String(bytes, StandardCharsets.UTF_8))
        .contains("+    return a + b")
        .doesNotContain("ignorado");
  }

  @Test
  void aCleanWorktreeHasNoChanges() throws Exception {
    Path repo = TestRepos.create(dir.resolve("repo"));
    Path diff = dir.resolve("changes.diff");

    GitIndexer.Changes changes = GitIndexer.index(repo, null, diff);

    assertThat(changes.summary())
        .containsEntry("commits", 0)
        .containsEntry("files", 0)
        .containsEntry("insertions", 0L)
        .containsEntry("uncommittedFiles", 0);
    assertThat(Files.size(diff)).isZero();
  }

  private static Map<String, Object> file(List<Map<String, Object>> files, String path) {
    return files.stream().filter(f -> path.equals(f.get("path"))).findFirst().orElseThrow();
  }

  private static void commit(Path repo, String message, String... extra) throws Exception {
    List<String> args =
        new java.util.ArrayList<>(
            List.of("-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", message));
    args.addAll(List.of(extra));
    TestRepos.git(repo, args.toArray(String[]::new));
  }
}
