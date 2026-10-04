package dev.skynet.runner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Repositorios git temporales para los tests. */
public final class TestRepos {

  private TestRepos() {}

  /** Crea un repositorio con un commit en {@code main}. */
  public static Path create(Path dir) throws IOException, InterruptedException {
    Files.createDirectories(dir);
    git(dir, "init", "-q", "-b", "main");
    Files.writeString(dir.resolve("calc.py"), "def add(a, b):\n    return a - b\n");
    git(dir, "add", ".");
    git(dir, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "init");
    return dir;
  }

  public static String git(Path dir, String... args) throws IOException, InterruptedException {
    List<String> command = new ArrayList<>(List.of("git", "-C", dir.toString()));
    command.addAll(List.of(args));
    Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
    String out = new String(p.getInputStream().readAllBytes());
    if (p.waitFor() != 0) {
      throw new IOException(String.join(" ", command) + ": " + out);
    }
    return out.trim();
  }
}
