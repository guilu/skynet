package dev.skynet.runner.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import dev.skynet.runner.verify.DeclaredFiles.Limits;
import dev.skynet.runner.verify.DeclaredFiles.Match;
import dev.skynet.runner.verify.DeclaredFiles.Omitted;
import dev.skynet.runner.verify.DeclaredFiles.Selection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeclaredFilesTest {

  @TempDir Path dir;

  private Path worktree;

  @BeforeEach
  void setUp() throws Exception {
    worktree = Files.createDirectories(dir.resolve("worktree"));
    write("docs/skynet/mapa.md", "# Mapa\n");
    write("src/Main.java", "class Main {}\n");
  }

  @Test
  void onlyTheDeclaredFilesAreSelected() throws Exception {
    Selection selection = select(List.of("docs/skynet/mapa.md"));

    assertThat(selection.files())
        .extracting(Match::path, Match::glob, Match::size, Match::mediaType)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                "docs/skynet/mapa.md", "docs/skynet/mapa.md", 7L, "text/markdown"));
    assertThat(selection.omitted()).isEmpty();
    assertThat(selection.missing()).isEmpty();
  }

  @Test
  void eachFileOfAPatternIsSelectedOnceWithItsRelativePath() throws Exception {
    write("docs/informe.json", "{}");
    write("docs/img/grafo.png", "png");

    Selection selection = select(List.of("docs/**", "docs/*.json"));

    assertThat(selection.files())
        .extracting(Match::path)
        .containsExactly("docs/img/grafo.png", "docs/informe.json", "docs/skynet/mapa.md");
    assertThat(selection.files()).allSatisfy(m -> assertThat(m.glob()).isEqualTo("docs/**"));
  }

  @Test
  void globsThatFindNothingAreMissing() throws Exception {
    Selection selection = select(List.of("docs/skynet/mapa.md", "openspec/*.md"));

    assertThat(selection.missing()).containsExactly("openspec/*.md");
  }

  @Test
  void pathsOutsideTheWorktreeNeverMatch() throws Exception {
    Files.writeString(dir.resolve("secreto.txt"), "no");

    Selection selection =
        select(List.of("../secreto.txt", dir.resolve("secreto.txt").toString(), "**/../*"));

    assertThat(selection.files()).isEmpty();
    assertThat(selection.missing()).hasSize(3);
  }

  @Test
  void theGitDirectoryIsNeverSelected() throws Exception {
    write(".git/config", "[core]\n");

    assertThat(select(List.of("**")).files())
        .extracting(Match::path)
        .containsExactly("docs/skynet/mapa.md", "src/Main.java");
  }

  @Test
  void linksOutsideTheWorktreeAreOmittedAndDoNotCount() throws Exception {
    Path secret = Files.writeString(dir.resolve("secreto.txt"), "token");
    Files.createSymbolicLink(worktree.resolve("docs/fuga.md"), secret);
    Files.createSymbolicLink(worktree.resolve("docs/fuera"), dir);

    Selection selection = select(List.of("docs/fuga.md", "docs/fuera/secreto.txt"));

    assertThat(selection.files()).isEmpty();
    assertThat(selection.omitted())
        .containsExactly(
            new Omitted("docs/fuga.md", "Es un enlace simbólico que sale del repositorio"));
    assertThat(selection.missing()).containsExactly("docs/fuga.md", "docs/fuera/secreto.txt");
  }

  @Test
  void linksInsideTheWorktreeReadTheirTarget() throws Exception {
    Files.createSymbolicLink(worktree.resolve("docs/ultimo.md"), Path.of("skynet/mapa.md"));

    Selection selection = select(List.of("docs/ultimo.md"));

    assertThat(selection.files()).hasSize(1);
    Match match = selection.files().getFirst();
    assertThat(match.path()).isEqualTo("docs/ultimo.md");
    assertThat(match.file()).isEqualTo(worktree.toRealPath().resolve("docs/skynet/mapa.md"));
  }

  @Test
  void linksIntoTheGitDirectoryAreOmitted() throws Exception {
    write(".git/config", "[core]\n");
    Files.createSymbolicLink(worktree.resolve("docs/config"), Path.of("../.git/config"));

    Selection selection = select(List.of("docs/config"));

    assertThat(selection.files()).isEmpty();
    assertThat(selection.omitted()).extracting(Omitted::path).containsExactly("docs/config");
  }

  @Test
  void specialFilesAreOmitted() throws Exception {
    Path fifo = worktree.resolve("docs/tuberia");
    int exit = new ProcessBuilder("mkfifo", fifo.toString()).start().waitFor();
    assumeThat(exit).as("mkfifo disponible").isZero();

    Selection selection = select(List.of("docs/*"));

    assertThat(selection.files()).isEmpty();
    assertThat(selection.omitted())
        .containsExactly(new Omitted("docs/tuberia", "No es un fichero regular"));
    assertThat(selection.missing()).containsExactly("docs/*");
  }

  @Test
  void filesBeyondTheLimitsAreOmittedWhole() throws Exception {
    write("out/a.txt", "1234");
    write("out/b.txt", "12345678901");
    write("out/c.txt", "123456");
    write("out/d.txt", "12");
    write("out/e.txt", "1");

    Selection selection = DeclaredFiles.select(worktree, List.of("out/*"), new Limits(10, 12, 3));

    assertThat(selection.files())
        .extracting(Match::path)
        .containsExactly("out/a.txt", "out/c.txt", "out/d.txt");
    assertThat(selection.omitted())
        .containsExactly(
            new Omitted("out/b.txt", "Supera el máximo de 10 bytes por fichero"),
            new Omitted("out/e.txt", "Supera el máximo de 3 ficheros por fase"));
    // Los omitidos por tamaño existen: el check `artifacts` pasa.
    assertThat(selection.missing()).isEmpty();
  }

  @Test
  void theStageLimitOmitsWhatDoesNotFit() throws Exception {
    write("out/a.txt", "123456");
    write("out/b.txt", "123456");

    Selection selection = DeclaredFiles.select(worktree, List.of("out/*"), new Limits(10, 10, 5));

    assertThat(selection.files()).extracting(Match::path).containsExactly("out/a.txt");
    assertThat(selection.omitted())
        .containsExactly(new Omitted("out/b.txt", "Supera el máximo de 10 bytes por fase"));
  }

  @Test
  void theMediaTypeComesFromAClosedListOfExtensions() {
    assertThat(DeclaredFiles.mediaType("docs/mapa.md")).isEqualTo("text/markdown");
    assertThat(DeclaredFiles.mediaType("informe.JSON")).isEqualTo("application/json");
    assertThat(DeclaredFiles.mediaType("a/grafo.png")).isEqualTo("image/png");
    assertThat(DeclaredFiles.mediaType("foto.jpeg")).isEqualTo("image/jpeg");
    assertThat(DeclaredFiles.mediaType("plan.pdf")).isEqualTo("application/pdf");
    // El HTML nunca se sirve como página.
    assertThat(DeclaredFiles.mediaType("index.html")).isEqualTo("text/plain");
    assertThat(DeclaredFiles.mediaType("binario.exe")).isEqualTo("application/octet-stream");
    assertThat(DeclaredFiles.mediaType(".env")).isEqualTo("application/octet-stream");
    assertThat(DeclaredFiles.mediaType("Makefile")).isEqualTo("application/octet-stream");
  }

  private Selection select(List<String> globs) throws Exception {
    return DeclaredFiles.select(worktree, globs, Limits.DEFAULT);
  }

  private void write(String path, String content) throws Exception {
    Path file = worktree.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }
}
