package dev.skynet.runner.workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Crea un {@code git worktree} por invocación en {@code <root>/<workflow-run>/<agent-run>/}, sobre
 * la rama {@code skynet/<work-item>/<agent-run>}. Los worktrees se conservan al terminar; su
 * limpieza llega con la política de retención (M6).
 */
public class WorkspaceManager {

  private static final long GIT_TIMEOUT_SECONDS = 120;

  private final Path root;

  public WorkspaceManager(Path root) {
    this.root = root;
  }

  public Workspace create(
      Path repository, String baseBranch, UUID workflowRunId, String workItemKey, UUID agentRunId)
      throws IOException, InterruptedException {
    if (!Files.isDirectory(repository)) {
      throw new IOException("El repositorio no existe en este runner: " + repository);
    }
    Path path = root.resolve(workflowRunId.toString()).resolve(agentRunId.toString());
    if (Files.exists(path)) {
      throw new IOException("El worktree ya existe: " + path);
    }
    Files.createDirectories(path.getParent());
    String branch = branchName(workItemKey, agentRunId);
    List<String> add = new ArrayList<>(List.of("worktree", "add", "-b", branch, path.toString()));
    if (baseBranch != null && !baseBranch.isBlank()) {
      add.add(baseBranch);
    }
    git(repository, add);
    String baseCommit = git(path, List.of("rev-parse", "HEAD")).trim();
    return new Workspace(path, branch, baseCommit);
  }

  static String branchName(String workItemKey, UUID agentRunId) {
    String item =
        workItemKey == null || workItemKey.isBlank()
            ? "adhoc"
            : workItemKey.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");
    return "skynet/" + item + "/" + agentRunId.toString().substring(0, 8);
  }

  private static String git(Path directory, List<String> args)
      throws IOException, InterruptedException {
    List<String> command = new ArrayList<>(List.of("git", "-C", directory.toString()));
    command.addAll(args);
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    process.getOutputStream().close();
    byte[] output = process.getInputStream().readAllBytes();
    if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new IOException("git " + String.join(" ", args) + ": tiempo agotado");
    }
    String text = new String(output, StandardCharsets.UTF_8);
    if (process.exitValue() != 0) {
      throw new IOException("git " + String.join(" ", args) + " falló: " + text.trim());
    }
    return text;
  }
}
