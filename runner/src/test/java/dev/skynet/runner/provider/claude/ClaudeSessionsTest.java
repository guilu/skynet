package dev.skynet.runner.provider.claude;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClaudeSessionsTest {

  @TempDir Path dir;

  private ClaudeSessions sessions;
  private Path from;
  private Path to;

  @BeforeEach
  void setUp() throws IOException {
    sessions = new ClaudeSessions(dir.resolve("config"));
    from = Files.createDirectories(dir.resolve("w/a"));
    to = Files.createDirectories(dir.resolve("w/b"));
  }

  @Test
  void copiesTheTranscriptAndItsDirectoryToTheForksWorktree() throws IOException {
    Path source = Files.createDirectories(sessions.projectDir(from));
    Files.writeString(source.resolve("s-1.jsonl"), "{}\n");
    Files.createDirectories(source.resolve("s-1/tools"));
    Files.writeString(source.resolve("s-1/tools/out.txt"), "salida");

    assertThat(sessions.copy("s-1", from, to)).isTrue();

    Path target = sessions.projectDir(to);
    assertThat(target.resolve("s-1.jsonl")).hasContent("{}");
    assertThat(target.resolve("s-1/tools/out.txt")).hasContent("salida");
  }

  @Test
  void aSessionIdThatCouldLeaveTheProjectDirectoryIsRefused() throws IOException {
    // Un transcript fuera del directorio del proyecto que un id con ../ alcanzaría.
    Path outside = Files.createDirectories(dir.resolve("config/projects"));
    Files.writeString(outside.resolve("robado.jsonl"), "secreto");

    for (String id : new String[] {"../robado", "../../../config/projects/robado", "-x", ""}) {
      assertThatThrownBy(() -> sessions.copy(id, from, to))
          .as(id)
          .isInstanceOf(IOException.class)
          .hasMessageContaining("no es un id de sesión válido");
    }
    assertThat(sessions.projectDir(to)).doesNotExist();
  }

  @Test
  void symlinksInTheSessionAreNotFollowed() throws IOException {
    Path secret = dir.resolve("secreto.txt");
    Files.writeString(secret, "token");
    Path source = Files.createDirectories(sessions.projectDir(from));
    Files.writeString(source.resolve("s-1.jsonl"), "{}\n");
    Files.createDirectories(source.resolve("s-1"));
    Files.createSymbolicLink(source.resolve("s-1/enlace.txt"), secret);
    Files.createSymbolicLink(source.resolve("s-1/carpeta"), dir);

    assertThat(sessions.copy("s-1", from, to)).isTrue();

    Path target = sessions.projectDir(to);
    assertThat(target.resolve("s-1/enlace.txt")).doesNotExist();
    assertThat(target.resolve("s-1/carpeta")).doesNotExist();
  }

  @Test
  void aTranscriptThatIsASymlinkIsNotCopied() throws IOException {
    Path secret = dir.resolve("secreto.jsonl");
    Files.writeString(secret, "token");
    Path source = Files.createDirectories(sessions.projectDir(from));
    Files.createSymbolicLink(source.resolve("s-1.jsonl"), secret);

    assertThat(sessions.copy("s-1", from, to)).isFalse();
    assertThat(sessions.projectDir(to).resolve("s-1.jsonl")).doesNotExist();
  }
}
